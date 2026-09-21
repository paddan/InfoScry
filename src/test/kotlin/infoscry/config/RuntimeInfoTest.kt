package infoscry.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val info = RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new())

        info.writeTo(file)

        assertEquals(info, RuntimeInfo.read(file))
    }

    @Test
    fun `a running process is live and discoverable`() {
        val file = dataDir.resolve("runtime.json")
        val info = RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new())

        info.writeTo(file)

        assertTrue(info.isLive, "the current process is alive")
        assertEquals(info, RuntimeInfo.discover(file))
    }

    @Test
    fun `a stale runtime file is rejected by pid validation rather than trusted`() {
        val deadPid = pidOfExitedProcess()
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = deadPid, port = 8765, bearerToken = BearerToken.new()).writeTo(file)

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
        RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new()).writeTo(file)

        RuntimeInfo.delete(file)
        RuntimeInfo.delete(file)

        assertFalse(Files.exists(file))
    }

    @Test
    fun `the runtime file is private to the user`() {
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new()).writeTo(file)

        assertEquals(PosixFilePermissions.fromString("rw-------"), permissionsOf(file))
    }

    @Test
    fun `a write leaves the runtime file private and no temporary file behind`() {
        val file = dataDir.resolve("runtime.json")
        RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new()).writeTo(file)

        assertFalse(Files.exists(temporaryOf(file)), "the token must not survive in a sidecar file")
        assertEquals(PosixFilePermissions.fromString("rw-------"), permissionsOf(file))
    }

    @Test
    fun `the token's temporary file is private before a token can be written into it`() {
        val temporary = temporaryOf(dataDir.resolve("runtime.json"))

        RuntimeInfo.openTemporaryForWrite(temporary).use { /* open only: the point is the mode */ }

        assertTrue(Files.exists(temporary), "the call under test creates the file")
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            permissionsOf(temporary),
            "the token's temporary file must be private from the moment it exists",
        )
    }

    @Test
    fun `a readable temporary file left by a killed process is hardened before it is reused`() {
        val temporary = temporaryOf(dataDir.resolve("runtime.json"))
        Files.writeString(temporary, "left behind by a killed process")
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))

        RuntimeInfo.openTemporaryForWrite(temporary).use { /* open only: the point is the mode */ }

        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            permissionsOf(temporary),
            "permissions set at creation do not apply to a file that already existed",
        )
    }

    @Test
    fun `a failed write leaves neither the runtime file nor its temporary file`() {
        val file = dataDir.resolve("runtime.json")
        val temporary = temporaryOf(file)
        // An occupied destination makes the final atomic move fail after the token was already
        // written to the temporary file, which is the moment that used to leave a readable sidecar.
        Files.createDirectories(file)
        Files.writeString(file.resolve("occupied"), "not empty")

        assertFailsWith<IOException> {
            RuntimeInfo(pid = ProcessHandle.current().pid(), port = 8765, bearerToken = BearerToken.new()).writeTo(file)
        }

        assertFalse(Files.exists(temporary), "a failed write must delete the token-bearing temporary file")
        assertTrue(Files.exists(file.resolve("occupied")), "cleanup must not touch the destination itself")
    }

    @Test
    fun `tokens are 256 bits of randomness and never repeat`() {
        val first = BearerToken.new()
        val second = BearerToken.new()

        assertNotEquals(first, second)
        val decoded = Base64.getUrlDecoder().decode(first.value)
        assertEquals(BearerToken.BYTES, decoded.size, "a 256-bit token is ${BearerToken.BYTES} bytes")
    }

    @Test
    fun `a token cannot be printed, so it cannot leak through stringification`() {
        val token = BearerToken.new()
        val info = RuntimeInfo(pid = 4242, port = 8765, bearerToken = token)

        assertFalse("$token".contains(token.value), "the token must not print itself")
        assertEquals(BearerToken.REDACTED, "$token")
        assertFalse("$info".contains(token.value), "a record carrying the token must not print it")
        assertTrue("$info".contains("bearerToken"), "the field name still tells an operator what was dropped")
    }

    @Test
    fun `a blank token cannot exist`() {
        assertFailsWith<IllegalArgumentException> { BearerToken("  ") }
    }

    /** The sidecar [RuntimeInfo.writeTo] writes the token into before moving it into place. */
    private fun temporaryOf(file: Path): Path = file.resolveSibling("${file.fileName}.tmp")

    /**
     * The mode of [path]. The data directory lives on a POSIX filesystem on both supported platforms
     * (macOS and Linux), so a missing POSIX view is a broken premise here and is reported as such
     * rather than quietly skipped.
     */
    private fun permissionsOf(path: Path): Set<PosixFilePermission> {
        assertTrue(
            Files.getFileStore(path).supportsFileAttributeView("posix"),
            "InfoScry's private-file invariant is asserted on POSIX: $path",
        )
        return Files.getPosixFilePermissions(path)
    }

    /** Spawns a process, waits for it to exit, and returns the pid it no longer occupies. */
    private fun pidOfExitedProcess(): Long {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        process.waitFor()
        return process.pid()
    }
}
