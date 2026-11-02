package com.aimediasorter.app

import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.domain.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Exercises production transfer code with isolated real temporary files. */
class AcceptanceScenariosTest {
    private lateinit var root: File
    private lateinit var sourceFile: File
    private lateinit var destinationRoot: File
    private val payload = ByteArray(25000) { (it % 251).toByte() }
    private val historical = 1684074130123L
    private val engine = VerifiedTransferEngine()
    @Before fun prepare() {
        root = Files.createTempDirectory("aimediasorter-safety-").toFile()
        sourceFile = File(root, "source/İstanbul 'Kahvaltı' \$100.jpg")
        check(sourceFile.parentFile!!.mkdirs())
        sourceFile.writeBytes(payload)
        check(sourceFile.setLastModified(historical))
        destinationRoot = File(root, "destination")
    }
    @After fun clean() { root.deleteRecursively() }
    private fun checksum(): String = MessageDigest.getInstance("SHA-256")
        .digest(payload).joinToString("") { "%02x".format(it) }
    private suspend fun transfer(
        method: TransferMethod = TransferMethod.MOVE,
        source: TransferDocument = LocalTransferDocument(sourceFile),
        directory: TransferDirectory = LocalTransferDirectory(File(destinationRoot, "Food"), destinationRoot),
        policy: CollisionPolicy = CollisionPolicy.RENAME_WITH_SUFFIX,
        checkpoint: suspend (TransferCheckpoint) -> Unit = {}
    ): VerifiedTransferReceipt = engine.transfer(
        source, directory, sourceFile.name, "image/jpeg", method, policy,
        TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL, checksum(), payload.size.toLong(), historical, checkpoint
    )
    @Test fun copyPreservesSourceBytesAndFilesystemTime() = runBlocking {
        val result = transfer(method = TransferMethod.COPY)
        assertTrue(sourceFile.exists())
        val destination = File(result.destination!!.id)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(historical, destination.lastModified())
        assertTrue(result.timestamp!!.preserved)
    }
    @Test fun moveDeletesSourceOnlyAfterVerifiedReadback() = runBlocking {
        val result = transfer()
        assertFalse(sourceFile.exists())
        assertEquals(TransferMethod.MOVE, result.actualMethod)
        assertFalse(result.sourceRetained)
        assertArrayEquals(payload, File(result.destination!!.id).readBytes())
    }
    @Test fun concurrentCollisionPreservesExistingDestination() = runBlocking {
        lateinit var existing: File
        val result = transfer(checkpoint = { stage ->
            if (stage.phase == "STAGING") {
                existing = File(destinationRoot, "Food/" + sourceFile.name)
                existing.writeText("concurrent unrelated media")
            }
        })
        assertEquals("concurrent unrelated media", existing.readText())
        assertNotEquals(existing.canonicalPath, result.destination!!.id)
        assertArrayEquals(payload, File(result.destination!!.id).readBytes())
    }
    @Test fun skipDoesNotClaimTimestampVerification() = runBlocking {
        val dir = LocalTransferDirectory(File(destinationRoot, "Food"), destinationRoot)
        File(destinationRoot, "Food/" + sourceFile.name).writeText("different")
        val result = transfer(directory = dir, policy = CollisionPolicy.SKIP_EXISTING)
        assertTrue(result.skipped)
        assertNull(result.timestamp)
        assertTrue(sourceFile.exists())
    }
    @Test fun changedSourceBeforeDeletionIsPreserved() = runBlocking {
        try {
            transfer(checkpoint = {
                if (it.phase == "SOURCE_DELETE_PENDING") sourceFile.writeText("newer source")
            })
            fail("Changed source must not be deleted")
        } catch (_: IllegalArgumentException) {
            assertEquals("newer source", sourceFile.readText())
        }
    }
    @Test fun destinationChangedDuringDeletionCheckpointNeverRemovesOriginal() = runBlocking {
        try {
            transfer(checkpoint = {
                if (it.phase == "SOURCE_DELETE_PENDING") File(it.destinationId!!).writeText("new destination content")
            })
            fail("Destination changes after checkpoint must block deletion")
        } catch (_: IllegalArgumentException) {
            assertArrayEquals(payload, sourceFile.readBytes())
        }
    }
    @Test fun sameSizeDestinationCorruptionIsDetected() = runBlocking {
        val delegate = LocalTransferDirectory(File(destinationRoot, "Food"), destinationRoot)
        val corrupting = object : TransferDirectory by delegate {
            override fun createExclusive(name: String, mimeType: String): TransferDocument? {
                val document = delegate.createExclusive(name, mimeType) ?: return null
                if (name.startsWith(".aimediasorter-")) return document
                return object : TransferDocument by document {
                    override fun sync(output: OutputStream) {
                        document.sync(output)
                        File(document.id).writeBytes(ByteArray(payload.size) { 42 })
                    }
                }
            }
        }
        try {
            transfer(directory = corrupting)
            fail("Same-size corruption must not pass verification")
        } catch (_: IllegalArgumentException) {
            assertArrayEquals(payload, sourceFile.readBytes())
        }
    }
    @Test fun timestampFailureKeepsOriginal() = runBlocking {
        val delegate = LocalTransferDirectory(File(destinationRoot, "Food"), destinationRoot)
        val restricted = object : TransferDirectory by delegate {
            override fun createExclusive(name: String, mimeType: String): TransferDocument? {
                val document = delegate.createExclusive(name, mimeType) ?: return null
                return object : TransferDocument by document {
                    override fun setTimestamp(originalMs: Long) =
                        TimestampVerificationResult(false, originalMs, 0, "Provider denied modification time.")
                }
            }
        }
        val result = transfer(directory = restricted)
        assertTrue(sourceFile.exists())
        assertTrue(result.sourceRetained)
        assertEquals(TransferMethod.COPY, result.actualMethod)
    }
    @Test fun deletionFailureIsReportedAsCopy() = runBlocking {
        val delegate = LocalTransferDocument(sourceFile)
        val restricted = object : TransferDocument by delegate {
            override fun delete(): Boolean = false
        }
        val result = transfer(source = restricted)
        assertTrue(sourceFile.exists())
        assertTrue(result.sourceRetained)
        assertEquals(TransferMethod.COPY, result.actualMethod)
        assertTrue(result.details.contains("MOVE was not completed"))
    }
    @Test fun cancellationPreservesOriginalAndVerifiedCopy() = runBlocking {
        try {
            transfer(checkpoint = {
                if (it.phase == "DESTINATION_VERIFIED") throw CancellationException("cancel")
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertArrayEquals(payload, sourceFile.readBytes())
            assertArrayEquals(payload, File(destinationRoot, "Food/" + sourceFile.name).readBytes())
            assertTrue(File(destinationRoot, "Food").listFiles()!!.none { it.name.endsWith(".part") })
        }
    }
    @Test fun changedAnalysisChecksumPreventsTransfer() = runBlocking {
        sourceFile.writeText("changed before transfer")
        try {
            transfer()
            fail("Changed input must require re-analysis")
        } catch (_: IllegalArgumentException) {
            assertEquals("changed before transfer", sourceFile.readText())
        }
    }
    @Test fun directoryTraversalIsRejected() {
        try { SafeRelativePath.segments("../source"); fail("Traversal must be rejected") }
        catch (_: IllegalArgumentException) {}
        try { LocalTransferDirectory(File(destinationRoot, "../../escape"), destinationRoot); fail("Escape must be rejected") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun providerReturningSourceAsStagingNeverDeletesSource() = runBlocking {
        val source = LocalTransferDocument(sourceFile)
        val unsafe = object : TransferDirectory {
            override fun find(name: String): TransferDocument? = null
            override fun createExclusive(name: String, mimeType: String): TransferDocument = source
        }
        try {
            transfer(source = source, directory = unsafe)
            fail("Source alias must be rejected")
        } catch (_: IllegalArgumentException) {
            assertArrayEquals(payload, sourceFile.readBytes())
        }
    }
    @Test fun unknownStableIdentityKeepsOriginal() = runBlocking {
        val delegate = LocalTransferDocument(sourceFile)
        val opaque = object : TransferDocument by delegate {
            override fun identityKey(): String? = null
        }
        val result = transfer(source = opaque)
        assertTrue(sourceFile.exists())
        assertEquals(TransferMethod.COPY, result.actualMethod)
        assertTrue(result.sourceRetained)
    }
}
