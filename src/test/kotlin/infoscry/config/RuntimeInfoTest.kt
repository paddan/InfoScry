package infoscry.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeInfoTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-runtime")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `runtime information round trips through its file`() {
        val file = dataDir.resolve("runtime.json")
        val info = RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = RuntimeInfo.newToken())

        info.writeTo(file)

        assertEquals(info, RuntimeInfo.read(file))
    }

    @Test
    fun `a running process is live and discoverable`() {
        val file = dataDir.resolve("runtime.json")
        val info = RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = RuntimeInfo.newToken())

        info.writeTo(file)

        assertTrue(info.isLive, "the current process is alive")
        assertEquals(info, RuntimeInfo.discover(file))
    }

    @Test
    fun `a stale runtime file is rejected by pid validation rather than trusted`() {
        val deadPid = pidOfExitedProcess()
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = deadPid, port = 8765, bearerToken = RuntimeInfo.newToken()).writeTo(file)

        val stale = RuntimeInfo.read(file)

        assertEquals(deadPid, stale?.pid, "the file still parses; only liveness may reject it")
        assertFalse(stale!!.isLive, "a pid that no longer exists is not a running server")
        assertNull(RuntimeInfo.discover(file), "discovery must not hand out a dead server's address")
    }

    @Test
    fun `a missing or unreadable runtime file is not an error, just no server`() {
        val file = dataDir.resolve("runtime.json")

        assertNull(RuntimeInfo.read(file))
        assertNull(RuntimeInfo.discover(file))

        Files.writeString(file, "{ this is not json")

        assertNull(RuntimeInfo.read(file))
        assertNull(RuntimeInfo.discover(file))
    }

    @Test
    fun `deleting runtime information removes the file and tolerates an absent one`() {
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = RuntimeInfo.newToken()).writeTo(file)

        RuntimeInfo.delete(file)
        RuntimeInfo.delete(file)

        assertFalse(Files.exists(file))
    }

    @Test
    fun `the runtime file is private to the user`() {
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = RuntimeInfo.newToken()).writeTo(file)

        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        }
    }

    @Test
    fun `tokens are 256 bits of randomness and never repeat`() {
        val first = RuntimeInfo.newToken()
        val second = RuntimeInfo.newToken()

        assertNotEquals(first, second)
        val decoded = Base64.getUrlDecoder().decode(first)
        assertEquals(RuntimeInfo.TOKEN_BYTES, decoded.size, "a 256-bit token is ${RuntimeInfo.TOKEN_BYTES} bytes")
    }

    /** Spawns a process, waits for it to exit, and returns the pid it no longer occupies. */
    private fun pidOfExitedProcess(): Long {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        process.waitFor()
        return process.pid()
    }
}
