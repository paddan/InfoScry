package infoscry.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessLockTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun createTemporaryDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-lock")
    }

    @AfterTest
    fun removeTemporaryDataDirectory() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `acquiring a free lock succeeds and reports its path`() {
        val paths = AppPaths.from(dataDir)

        ProcessLock.acquire(paths.lockFile).use { lock ->
            assertEquals(paths.lockFile, lock.path)
            assertTrue(Files.exists(paths.lockFile), "the lock has to exist on disk to be a lock")
        }
    }

    @Test
    fun `a second acquisition fails with an actionable message instead of blocking`() {
        val paths = AppPaths.from(dataDir)

        ProcessLock.acquire(paths.lockFile).use {
            val failure = assertFailsWith<ProcessLockUnavailable> { ProcessLock.acquire(paths.lockFile) }

            assertContains(failure.message.orEmpty(), paths.lockFile.toString())
            assertContains(failure.message.orEmpty(), "already running")
        }
    }

    @Test
    fun `releasing the lock lets the next process acquire it`() {
        val paths = AppPaths.from(dataDir)

        ProcessLock.acquire(paths.lockFile).close()
        ProcessLock.acquire(paths.lockFile).use { lock ->
            assertEquals(paths.lockFile, lock.path)
        }
    }

    @Test
    fun `the lock file is private to the user`() {
        val paths = AppPaths.from(dataDir)

        ProcessLock.acquire(paths.lockFile).use {
            if (Files.getFileStore(paths.lockFile).supportsFileAttributeView("posix")) {
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(paths.lockFile),
                )
            }
        }
    }

}
