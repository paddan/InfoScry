package infoscry

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

object AppInfo {
    const val name = "InfoScry"
    const val dataFormatVersion = 1
}

class RootCommand : CliktCommand(name = "infoscry") {
    override fun run() {
        echoFormattedHelp()
    }
}

fun main(args: Array<String>) = RootCommand().main(args)
