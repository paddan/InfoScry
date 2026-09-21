package infoscry

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import infoscry.cli.LogsCommand

object AppInfo {
    const val name = "InfoScry"
    const val dataFormatVersion = 1
}

class RootCommand : CliktCommand(name = "infoscry") {

    init {
        subcommands(LogsCommand())
    }

    override fun run() {
        // Clikt calls the parent's run() before a subcommand's, so help is printed only when no
        // subcommand was given; otherwise every command would be preceded by the program's usage text.
        if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }
}

fun main(args: Array<String>) = RootCommand().main(args)
