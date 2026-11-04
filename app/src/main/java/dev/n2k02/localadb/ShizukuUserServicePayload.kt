package dev.n2k02.localadb

import java.io.File
import java.security.MessageDigest

/** Verify executable code before it enters the helper's isolated classloader. */
object ShizukuUserServicePayload {
    @JvmStatic
    fun verify(file: File) = verifyDigest(file, NativeShizukuManager.SERVER_SHA256)

    internal fun verifyDigest(file: File, expected: String) {
        check(file.isFile) { "Shizuku user-service payload is missing: $file" }
        check(!file.canWrite()) { "Shizuku user-service payload must be read-only: $file" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (size > 0) digest.update(buffer, 0, size)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "Shizuku user-service payload checksum mismatch" }
    }
}
