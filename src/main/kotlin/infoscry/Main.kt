package infoscry

import com.github.ajalt.clikt.core.main
import infoscry.cli.RootCommand

object AppInfo {
    const val name = "InfoScry"
    const val dataFormatVersion = 1
}

fun main(args: Array<String>) = RootCommand().main(args)
