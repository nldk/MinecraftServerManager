package net.klokhuis7dev.MCSM

fun main(args: Array<String>) {
    val configServer = configServer(8080)
    configServer.start(true)
    /*
    val server = MCServer("server", ServerType.PAPER,"test",true)
    while (true) {
        print(">>> ")
        val commandU = readLine()
        if (commandU == null) {
            println("Invalid command")
            continue
        }
        val command = commandU.split(" ")[0]
        when (command) {
            "start" -> server.start()
            "stop" -> server.stop()
            "restart" -> server.restart()
            "setEula" -> {
                if (commandU.split(" ").size < 2) {
                    println("Usage: setEula <true|false>")
                    continue
                }
                val accepted = commandU.split(" ")[1].toBooleanStrictOrNull()
                if (accepted == null) {
                    println("Invalid value for setEula. Use true or false.")
                    continue
                }
                server.setEula(accepted)
            }
            "setProperty" -> {
                val parts = commandU.split(" ")
                if (parts.size < 3) {
                    println("Usage: setProperty <key> <value>")
                    continue
                }
                val key = parts[1]
                val value = parts.subList(2, parts.size).joinToString(" ")
                server.properties?.setProperty(key, value)
                server.properties?.save()
            }
            "exit" -> break

            else -> server.sendCommand(commandU)
        }
    }*/
}
