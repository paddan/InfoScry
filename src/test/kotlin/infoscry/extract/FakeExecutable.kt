package infoscry.extract

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * A stand-in for an external tool: a shell script that behaves like the tool would.
 *
 * Every test of the process handling and of the OCR tool uses one of these rather than the installed
 * Tesseract, because the properties under test are this project's — which arguments are passed, in what
 * order, what happens when the tool is slow, absent, or noisy, and where its output is written. A test
 * that needed the real tool would also need the real tool's version, its language packs, and its idea of
 * what a page looks like, and would fail for reasons that have nothing to do with this code.
 *
 * The script is executable only by its owner: it runs as the calling user and nothing else should be able
 * to read or replace it.
 */
internal fun writeFakeExecutable(directory: Path, name: String, script: String): Path {
    Files.createDirectories(directory)
    val target = directory.resolve(name)
    Files.writeString(target, "#!/bin/sh\n$script\n")
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
    return target
}

/** Waits until [condition] holds, for a step that another process performs. */
internal fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    check(condition()) { "the condition did not come true within ${timeoutMillis}ms" }
}
