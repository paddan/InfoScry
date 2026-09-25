package infoscry

import com.github.ajalt.clikt.core.main
import infoscry.cli.RootCommand

object AppInfo {
    const val name = "InfoScry"
    const val dataFormatVersion = 1
}

fun main(args: Array<String>) {
    val commandArgs = if (args.firstOrNull() == "--serve") {
        arrayOf("serve", *args.copyOfRange(1, args.size))
    } else {
        args
    }
    RootCommand().main(commandArgs)
}
