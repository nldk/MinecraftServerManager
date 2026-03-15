package net.klokhuis7dev.MCSM

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.net.URL

enum class ServerType {
    VANILLA, PAPER, FORGE, FABRIC, NEOFORGE,
}
class VallinaServerProperties{
    var properties: Map<String, String> = emptyMap()
    var file: File? = null
    constructor(serverDir: File){
        serverDir.walkTopDown().filter { it.isFile }.forEach {
            if (it.name == "server.properties"){
                file = it
                properties = it.readLines().filter { line -> line.isNotBlank() && !line.startsWith("#") }
                    .associate { line ->
                        val (key, value) = line.split("=", limit = 2)
                        key to value
                    }
            }
        }
    }
    fun setProperty(key: String, value: String) {
        properties = properties + (key to value)
    }
    fun save() {
        if (file == null) {
            println("server.properties file not found!")
            return
        }
        file!!.bufferedWriter().use { writer ->
            properties.forEach { (key, value) ->
                writer.write("$key=$value")
                writer.newLine()
            }
        }
    }
}
class MCServer {
    private val _consoleStream = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val consoleStream: SharedFlow<String> = _consoleStream.asSharedFlow()
    val name: String
    var procces: Process? = null
    var running: Boolean = false
    val type: ServerType
    val serverDir: File
    var serverJar: File? = null
    var port: Int
    var properties: VallinaServerProperties? = null
    constructor(dir: String, type: ServerType, name: String, acceptedEula: Boolean = false, port: Int = 25565) {
        this.port = port
        this.type = type
        this.name = name
        var firstCrearion = false
        serverDir = File(dir)
        if (!serverDir.exists()){
            serverDir.mkdirs()
            serverJar = downloadServerJar(serverDir, type)
            firstCrearion = true

        }else{

            serverJar = resolveLaunchJar()
            if (serverJar == null){
                _consoleStream.tryEmit("downloading server jar...")
                serverJar = downloadServerJar(serverDir, type)
            }
        }
        loadProperties()
        if (firstCrearion){
            setProperty("server-port", port.toString())
        }
        /*
        start(false)
        procces?.waitFor()
        setEula(acceptedEula)
        properties = loadProperties()

         */
    }
    fun restart() {
        stop()
        start()
    }
    fun setEula(accepted: Boolean) {
        val eulaFile = File(serverDir, "eula.txt")
        eulaFile.writeText("eula=${accepted}")
    }
    fun start(redirect: Boolean = true) {
        if (procces != null && procces!!.isAlive) {
            println("Server is already running!")
            return
        }
        if (type == ServerType.NEOFORGE) {
            val processBuilder = ProcessBuilder("./run.sh", "nogui")
            processBuilder.directory(serverDir)
            procces = processBuilder.start()
            println("Server started with ${serverJar!!.name}!")
            _consoleStream.tryEmit("Server started with ${serverJar!!.name}!")
            if (redirect) {
                redirectOutput(procces!!)
            }
            running = true
            return
        }
        serverJar = resolveLaunchJar() ?: serverJar
        if (serverJar == null || !serverJar!!.exists()) {
            println("Server jar not found!")
            _consoleStream.tryEmit("Server jar not found!")
            return
        }
        val processBuilder = ProcessBuilder("java", "-jar", serverJar!!.absolutePath, "nogui")
        processBuilder.directory(serverDir)
        procces = processBuilder.start()
        println("Server started with ${serverJar!!.name}!")
        _consoleStream.tryEmit("Server started with ${serverJar!!.name}!")
        if (redirect) {
            redirectOutput(procces!!)
        }
        running = true
    }
    fun installForgeServer(installerJar: File, targetDir: File) {
        println("Installing Forge server with installer ${installerJar.name}...")
        ProcessBuilder("java", "-jar", installerJar.absolutePath, "--installServer")
            .directory(targetDir)
            .inheritIO() // optional: prints output to console
            .start()
            .waitFor()
    }
    fun installFabricServer(installerJar: File, targetDir: File, mcVersion: String = "1.21.11") {
        ProcessBuilder(
            "java", "-jar", installerJar.absolutePath,
            "server", "-mcversion", mcVersion, "-downloadMinecraft"
        )
            .directory(targetDir)
            .inheritIO()
            .start()
            .waitFor()
    }
    fun stop() {
        val process = procces
        if (process == null || !process.isAlive) {
            running = false
            procces = null
            return
        }

        try {
            sendCommand("stop", false)
            process.waitFor()
        } catch (_: Exception) {
            process.destroy()
            process.waitFor()
        } finally {
            println("Server stopped!")
            running = false
            procces = null
        }
    }
    fun updateRunningStatus() {
        val process = procces
        if (process == null || !process.isAlive) {
            running = false
            procces = null
        } else {
            running = true
        }
    }
    private fun resolveLaunchJar(): File? {
        val topLevelJars = serverDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .orEmpty()

        val preferred = topLevelJars.firstOrNull { jar ->
            val lower = jar.name.lowercase()
            when (type) {
                ServerType.PAPER -> lower.contains("paper") && !lower.contains("mojang")
                ServerType.FORGE -> lower.contains("forge")
                ServerType.FABRIC -> lower.contains("fabric")
                ServerType.NEOFORGE -> lower.contains("neoforge")
                ServerType.VANILLA -> lower == "server.jar" || lower.contains("vanilla") || lower.contains("minecraft")
            }
        }
        if (preferred != null) {
            return preferred
        }

        val fallbackTopLevel = topLevelJars.firstOrNull()
        if (fallbackTopLevel != null) {
            return fallbackTopLevel
        }

        return serverDir.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("libraries", "cache", "versions", "plugins", "logs") }
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .firstOrNull()
    }
    fun downloadServerJar(dir: File, type: ServerType): File {
        val url = when (type) {
            ServerType.VANILLA -> "https://piston-data.mojang.com/v1/objects/64bb6d763bed0a9f1d632ec347938594144943ed/server.jar"
            ServerType.PAPER -> "https://fill-data.papermc.io/v1/objects/da497e12b43e5b61c5df150e4bfd0de0f53043e57d2ac98dd59289ee9da4ad68/paper-1.21.11-127.jar"
            ServerType.FORGE -> "https://adfoc.us/serve/sitelinks/?id=271228&url=https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.11-61.1.3/forge-1.21.11-61.1.3-installer.jar"
            ServerType.FABRIC -> "https://meta.fabricmc.net/v2/versions/loader/1.21.11/0.18.4/1.1.1/server/jar"
            ServerType.NEOFORGE -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.219/neoforge-21.1.219-installer.jar"
        }
        val fileName = url.substringAfterLast('/')
        downloadFile(url, dir.absolutePath, fileName)
        val file = File(dir, fileName)
        if (type == ServerType.NEOFORGE||type== ServerType.FORGE) {
            installForgeServer(file, dir)
             return resolveLaunchJar() ?: file
        }
        if (type == ServerType.VANILLA||type == ServerType.PAPER) {
            return file
        }
        if(type == ServerType.FABRIC){
            installFabricServer(file, dir)
            return resolveLaunchJar() ?: file
        }
        return file
    }
    fun redirectOutput(process: Process) {
        Thread {
            if (process.isAlive) {process.inputStream.bufferedReader().forEachLine {_consoleStream.tryEmit(it) }}
        }.start()
        Thread {
            if (process.isAlive) {process.errorStream.bufferedReader().forEachLine { _consoleStream.tryEmit(it) }}
        }.start()
    }
    fun sendCommand(command: String, showInStdout: Boolean = true) {
        val process = procces
        if (process == null || !process.isAlive) {
            println("Server is not running!")
            running = false
            procces = null
            return
        }
        val writer = process.outputStream.bufferedWriter()
        writer.write(command)
        writer.newLine()
        writer.flush()
        if (showInStdout) {
            _consoleStream.tryEmit(command)
        }
    }

    fun loadProperties() {
        properties = VallinaServerProperties(serverDir)
        if (properties==null){
            println("Failed to load server.properties!")
            return
        }
        //println("port: ${properties!!.properties.get("server-port")}")
        port = properties!!.properties.get("server-port")?.toInt() ?: 25565
    }
    fun setProperty(key: String,value: String, save: Boolean = true) {
        if (properties == null) {
            println("Properties not loaded!")
            return
        }
        properties!!.setProperty(key, value)
        if (save){
            properties!!.save()
        }
    }
    fun saveProperties() {
        if (properties == null) {
            println("Properties not loaded!")
            return
        }
        properties!!.save()
        loadProperties()
    }
    fun delete() {
        if (procces?.isAlive == true) {
            stop()
        } else {
            running = false
            procces = null
        }
        serverDir.deleteRecursively()
    }
    override fun toString(): String {
        return "MCServer(name='$name', type=$type, port=$port, running=$running)"
    }
}
fun downloadFile(url: String, outputDir: String, fileName: String) {
    val connection = URL(url).openConnection()
    connection.getInputStream().use { input ->
        val dir = File(outputDir)
        if (!dir.exists()) dir.mkdirs() // create directory if it doesn't exist
        val file = File(dir, fileName)
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    println("File downloaded to $outputDir/$fileName")
}