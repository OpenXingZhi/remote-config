package com.xingzhi.remoteconfig

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JVM/Android file adapter that stores the complete snapshot in one atomically replaced file. */
class FileConfigStore(
    directory: Path,
    snapshotFileName: String = "remote-config.snapshot",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigStore {
    private val snapshotPath = directory.resolve(snapshotFileName)

    override suspend fun load(): ConfigSnapshot? = withContext(dispatcher) {
        if (!snapshotPath.exists()) return@withContext null
        DataInputStream(Files.newInputStream(snapshotPath)).use { input ->
            require(input.readInt() == FORMAT_MAGIC) { "Invalid remote config snapshot." }
            require(input.readInt() == FORMAT_VERSION) { "Unsupported remote config snapshot." }
            val content = input.readSizedBytes()
            val signature = input.readSizedBytes().takeUnless { it.isEmpty() }
            val revision = input.readUTF().ifEmpty { null }
            ConfigSnapshot(content, signature, revision)
        }
    }

    override suspend fun save(snapshot: ConfigSnapshot): Unit = withContext(dispatcher) {
        Files.createDirectories(snapshotPath.parent)
        val temporary = snapshotPath.resolveSibling(".${snapshotPath.fileName}.tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary)).use { output ->
                output.writeInt(FORMAT_MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeSizedBytes(snapshot.content)
                output.writeSizedBytes(snapshot.signature ?: byteArrayOf())
                output.writeUTF(snapshot.revision.orEmpty())
            }
            try {
                Files.move(
                    temporary,
                    snapshotPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: UnsupportedOperationException) {
                Files.move(temporary, snapshotPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun DataInputStream.readSizedBytes(): ByteArray {
        val size = readInt()
        require(size in 0..MAX_FIELD_SIZE) { "Invalid remote config field size: $size" }
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeSizedBytes(bytes: ByteArray) {
        require(bytes.size <= MAX_FIELD_SIZE) { "Remote config field is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        private const val FORMAT_MAGIC = 0x585A5243 // XZRC
        private const val FORMAT_VERSION = 1
        private const val MAX_FIELD_SIZE = 64 * 1024 * 1024
    }
}
