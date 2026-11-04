package dev.n2k02.localadb

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShizukuUserServicePayloadTest {
    @get:Rule val folder = TemporaryFolder()

    private fun readOnly(file: File): File = object : File(file.path) {
        override fun canWrite() = false
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rejected(file: File, expected: String, reason: String) {
        try {
            ShizukuUserServicePayload.verifyDigest(file, expected)
            fail("Untrusted payload was accepted")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(reason))
        }
    }

    @Test fun verifiedReadOnlyPayloadIsAccepted() {
        val file = folder.newFile().apply { writeText("verified payload") }
        ShizukuUserServicePayload.verifyDigest(readOnly(file), hash(file.readBytes()))
    }

    @Test fun missingPayloadFailsBeforeClassloading() {
        rejected(File(folder.root, "missing.apk"), "unused", "missing")
    }

    @Test fun directoryCannotBeLoadedAsPayload() {
        rejected(folder.root, "unused", "missing")
    }

    @Test fun writablePayloadIsRejectedEvenWhenItsChecksumMatches() {
        val file = folder.newFile().apply { writeText("verified payload") }
        rejected(file, hash(file.readBytes()), "read-only")
    }

    @Test fun tamperedReadOnlyPayloadIsRejected() {
        val file = folder.newFile().apply { writeText("modified payload") }
        rejected(readOnly(file), hash("verified payload".toByteArray()), "checksum mismatch")
    }

    @Test fun largePayloadIsHashedAcrossTheEntireStream() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val file = folder.newFile().apply { writeBytes(bytes) }
        ShizukuUserServicePayload.verifyDigest(readOnly(file), hash(bytes))
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
        file.writeBytes(bytes)
        rejected(readOnly(file), hash(ByteArray(200_000) { (it % 251).toByte() }), "checksum mismatch")
    }
}
