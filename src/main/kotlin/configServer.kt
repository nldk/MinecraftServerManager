package net.klokhuis7dev.MCSM

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respond
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.text.get
import kotlin.time.Duration.Companion.seconds

@Serializable
data class NMCServer(val name: String, val type: ServerType, val running: Boolean, val port: Int)

@Serializable
data class CommandRequest(val command: String)

@Serializable
data class EulaRequest(val accepted: Boolean = true)

@Serializable
data class ServerProperty(val key: String, val value: String)

@Serializable
data class ServerPropertiesResponse(val properties: List<ServerProperty>)

@Serializable
data class FileEntryResponse(val name: String, val path: String, val directory: Boolean, val size: Long)

@Serializable
data class FileListResponse(val path: String, val entries: List<FileEntryResponse>)

@Serializable
data class FileWriteRequest(val path: String, val content: String)

class configServer {

    val server: EmbeddedServer<*, *>
    var MCServers: MutableList<MCServer> = mutableListOf()
    val serversDir: File

    constructor(port: Int, serversDir: String = "servers") {
        this.serversDir = File(serversDir)
        if (!this.serversDir.exists()) {
            this.serversDir.mkdirs()
        }
        for (server in this.serversDir.listFiles()!!) {
            if (server.isDirectory) {
                val type = when {
                    server.listFiles()
                        ?.any { it.name.contains("neoforge", ignoreCase = true) } == true -> ServerType.NEOFORGE
                    server.listFiles()?.any { it.name.contains("paper", ignoreCase = true) } == true -> ServerType.PAPER
                    server.listFiles()?.any { it.name.contains("forge", ignoreCase = true) } == true -> ServerType.FORGE
                    server.listFiles()
                        ?.any { it.name.contains("fabric", ignoreCase = true) } == true -> ServerType.FABRIC
                    else -> ServerType.VANILLA
                }
                val port = 25565 // Default port, you can implement a way to read this from a config file if needed
                MCServers.add(MCServer(server.path, type, server.name, false, port))
            }
        }
        server = embeddedServer(Netty, port) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                fun findServerOrNull(name: String?): MCServer? = MCServers.find { it.name == name }

                fun resolveSafeServerPath(server: MCServer, relativePath: String?): File? {
                    val base = server.serverDir.canonicalFile
                    val cleanRelative = (relativePath ?: "").trim().replace('\\', '/')
                    val target = if (cleanRelative.isBlank()) base else File(base, cleanRelative).canonicalFile
                    return if (target.path == base.path || target.path.startsWith(base.path + File.separator)) target else null
                }

                get("/isRunning/{name}") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    server.updateRunningStatus()
                    call.respondText(server.running.toString())
                }
                get("/") {
                    call.respondText(
                        this::class.java.classLoader.getResource("web/index.html")?.readText()
                            ?: "Dashboard not found",
                        contentType = io.ktor.http.ContentType.Text.Html
                    )
                }
                staticResources("/web", "web")
                get("/servers") {
                    call.respondText(Json.encodeToString(MCServers.map {
                        NMCServer(
                            it.name,
                            it.type,
                            it.running,
                            it.port
                        )
                    }))
                }
                post("/servers/{name}/start") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@post
                    }
                    server.start()
                    call.respondText("Server started")
                }
                post("/server/{name}/stop") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }
                    server.stop()
                    call.respondText("Server stopped", status = HttpStatusCode.OK)
                }
                post("/servers/{name}/sendCommand") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@post
                    }
                    val commandRequest = call.receive<CommandRequest>()
                    server.sendCommand(commandRequest.command)
                    call.respondText("Command sent")
                }
                post("/server/create") {
                    val request = call.receive<NMCServer>()
                    if (MCServers.any { it.name == request.name }) {
                        call.respondText(
                            "Server with this name already exists",
                            status = io.ktor.http.HttpStatusCode.BadRequest
                        )
                        return@post
                    }
                    val newServer =
                        MCServer("servers/" + request.name, request.type, request.name, request.running, request.port)
                    MCServers.add(newServer)
                    call.respondText("Server created")
                }
                post("/server/delete/{name}") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@post
                    }
                    server.delete()
                    MCServers.remove(server)
                    call.respondText("Server deleted")
                }
                post("/servers/{name}/eula") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }
                    val request = runCatching { call.receive<EulaRequest>() }.getOrElse { EulaRequest(true) }
                    server.setEula(request.accepted)
                    call.respondText("EULA updated", status = HttpStatusCode.OK)
                }

                get("/server/agreeToEULA/{name}") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@get
                    }
                    server.setEula(true)
                    call.respondText("EULA accepted")

                }
                get("/server/{name}/info"){
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    server.updateRunningStatus()
                    server.loadProperties()
                    //println(server.toString())
                    call.respondText(Json.encodeToString(NMCServer(server.name, server.type, server.running, server.port)))
                }
                webSocket("/servers/{name}") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Server not found"))
                        return@webSocket
                    }

                    if (!server.running) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Server is not running"))
                        return@webSocket
                    }

                    try {
                        server.consoleStream.collect { line ->
                            if (!isActive) return@collect
                            outgoing.send(Frame.Text(line))
                        }
                    } catch (_: Exception) {
                        // socket closed or collector cancelled
                    }
                }
                get("/servers/{name}/config") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    server.loadProperties()
                    val properties = server.properties!!.properties
                        .entries
                        .sortedBy { it.key }
                        .map { ServerProperty(it.key, it.value) }

                    call.respondText(
                        Json.encodeToString(ServerPropertiesResponse(properties)),
                        contentType = io.ktor.http.ContentType.Application.Json,
                        status = HttpStatusCode.OK
                    )
                }

                post("/servers/{name}/config") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }

                    val request = call.receive<ServerProperty>()
                    if (request.key.isBlank()) {
                        call.respondText("Missing key", status = HttpStatusCode.BadRequest)
                        return@post
                    }

                    server.loadProperties()
                    server.setProperty(request.key, request.value, save = true)
                    call.respondText("Property updated", status = HttpStatusCode.OK)
                }

                post("/servers/{name}/sendCommand") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@post
                    }
                    val commandRequest = call.receive<CommandRequest>()
                    server.sendCommand(commandRequest.command)
                    call.respondText("Command sent")
                }

                post("/servers/{name}/eula") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }
                    val request = runCatching { call.receive<EulaRequest>() }.getOrElse { EulaRequest(true) }
                    server.setEula(request.accepted)
                    call.respondText("EULA updated", status = HttpStatusCode.OK)
                }

                get("/server/agreeToEULA/{name}") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = io.ktor.http.HttpStatusCode.NotFound)
                        return@get
                    }
                    server.setEula(true)
                    call.respondText("EULA accepted")

                }

                get("/servers/{name}/getVanilaServerProperties") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    server.loadProperties()
                    val properties = server.properties!!.properties
                    call.respondText(properties.entries.joinToString("\n") { "${it.key}=${it.value}" }
                        .ifBlank { "No properties found" }, contentType = io.ktor.http.ContentType.Text.Plain)
                }
                post("/servers/{name}/setVanilaServerProperty") {
                    val name = call.parameters["name"]
                    val server = MCServers.find { it.name == name }
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }
                    val params = call.receiveParameters()
                    val key = params["key"]
                    val value = params["value"]
                    if (key == null || value == null) {
                        call.respondText("Missing key or value", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    server.loadProperties()
                    server.setProperty(key, value)
                    server.saveProperties()
                    call.respondText("Property updated", status = HttpStatusCode.OK)
                }
                get("/servers/{name}/files") {
                    val name = call.parameters["name"]
                    val server = findServerOrNull(name)
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    val requestedPath = call.request.queryParameters["path"]
                    val target = resolveSafeServerPath(server, requestedPath)
                    if (target == null) {
                        call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (!target.exists() || !target.isDirectory) {
                        call.respondText("Directory not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    val base = server.serverDir.canonicalFile
                    val entries = target.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).map {
                        FileEntryResponse(
                            name = it.name,
                            path = base.toPath().relativize(it.toPath()).toString().replace('\\', '/'),
                            directory = it.isDirectory,
                            size = if (it.isFile) it.length() else 0L
                        )
                    }
                    val currentPath = base.toPath().relativize(target.toPath()).toString().replace('\\', '/')
                    call.respond(FileListResponse(currentPath, entries))
                }

                get("/servers/{name}/file") {
                    val name = call.parameters["name"]
                    val server = findServerOrNull(name)
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    val path = call.request.queryParameters["path"]
                    if (path.isNullOrBlank()) {
                        call.respondText("Missing path", status = HttpStatusCode.BadRequest)
                        return@get
                    }

                    val target = resolveSafeServerPath(server, path)
                    if (target == null) {
                        call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (!target.exists() || !target.isFile) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    call.respondText(target.readText(), contentType = ContentType.Text.Plain)
                }

                post("/servers/{name}/upload") {
                    val name = call.parameters["name"]
                    val server = findServerOrNull(name)
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@post
                    }

                    val destinationPath = call.request.queryParameters["path"]
                    val destinationDir = resolveSafeServerPath(server, destinationPath)
                    if (destinationDir == null) {
                        call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    destinationDir.mkdirs()
                    if (!destinationDir.isDirectory) {
                        call.respondText("Upload target is not a directory", status = HttpStatusCode.BadRequest)
                        return@post
                    }

                    val multipart = call.receiveMultipart()
                    var uploadedCount = 0
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val originalName = part.originalFileName ?: "upload_${System.currentTimeMillis()}"
                            val safeName = File(originalName).name
                            val destinationFile = resolveSafeServerPath(
                                server,
                                File(destinationDir, safeName).canonicalFile.relativeTo(server.serverDir.canonicalFile).path
                            )
                            if (destinationFile != null) {
                                part.streamProvider().use { input ->
                                    Files.copy(input, destinationFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                }
                                uploadedCount++
                            }
                        }
                        part.dispose()
                    }

                    if (uploadedCount == 0) {
                        call.respondText("No files uploaded", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    call.respondText("Uploaded $uploadedCount file(s)", status = HttpStatusCode.OK)
                }

                get("/servers/{name}/download") {
                    val name = call.parameters["name"]
                    val server = findServerOrNull(name)
                    if (server == null) {
                        call.respondText("Server not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    val path = call.request.queryParameters["path"]
                    if (path.isNullOrBlank()) {
                        call.respondText("Missing path", status = HttpStatusCode.BadRequest)
                        return@get
                    }

                    val target = resolveSafeServerPath(server, path)
                    if (target == null) {
                        call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (!target.exists() || !target.isFile) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    call.response.headers.append("Content-Disposition", "attachment; filename=\"${target.name}\"")
                    call.respondBytes(target.readBytes(), contentType = ContentType.Application.OctetStream)
                }
            }
        }
    }
    fun start(wait: Boolean = false) {
        server.start(wait = wait)
    }

    fun stop() {
        server.stop(1000, 2000)
    }
}