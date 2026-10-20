package com.bittool.signer.core.common.storage

import android.content.Context
import com.bittool.signer.core.common.harness.UiTestEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Optional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The root of every private signer file: `context.noBackupFilesDir/BitToolSigner` (vault-crypto.md §17.8). Excluded
 * from cloud backup and device transfer by the manifest rules; credential-encrypted storage. Holds `Vaults/`,
 * `PublicWallets/` and `receipts.bin`. The debug UI-test harness replaces it (gaps.md G3, `UiTestRun.root`).
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SignerDataDir

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    @SignerDataDir
    fun signerDataDir(@ApplicationContext context: Context, uiTests: Optional<UiTestEnvironment>): File =
        uiTests.getOrNull()?.run?.root ?: File(context.noBackupFilesDir, "BitToolSigner")
}

/** A file that exists but is not a regular file (e.g. a symlink) or exceeds its size limit. */
class InvalidFileException(message: String) : IOException(message)

/**
 * Atomic, bounded file access shared by the vault, the public repository and the receipt journal (iOS
 * `writeProtected` and the read guards, vault-crypto.md §2.2–2.3, §17.8). Blocking: call on `Dispatchers.IO`.
 */
object AtomicFiles {
    const val PENDING_PREFIX = ".pending-"

    /**
     * Writes [bytes] to [target] atomically: creates the directory, stages `.pending-<UUID>` in the same directory,
     * writes and `fsync`s it, then renames it over [target] (atomic replace on the same file system). The staged file is
     * removed on failure, so the previous content survives any error. Throws [IOException].
     */
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory")
        val staged = File(dir, PENDING_PREFIX + UUID.randomUUID().toString().uppercase())
        try {
            FileOutputStream(staged).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /**
     * The bytes of [target], or null when it does not exist. Throws [InvalidFileException] when it is not a regular file
     * (symlinks are not followed) or is larger than [maxBytes] — checked **before** reading — and [IOException] on read
     * errors.
     */
    fun readBounded(target: File, maxBytes: Int): ByteArray? {
        val path = target.toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: java.nio.file.NoSuchFileException) {
            return null
        }
        if (!attributes.isRegularFile) throw InvalidFileException("Not a regular file")
        if (attributes.size() > maxBytes) throw InvalidFileException("File too large")
        val bytes = Files.readAllBytes(path)
        if (bytes.size > maxBytes) throw InvalidFileException("File too large")
        return bytes
    }

    /** Deletes [target] when it exists; true when it is gone afterwards. */
    fun delete(target: File): Boolean = !target.exists() || target.delete()

    /** Removes leftover `.pending-*` staging files in [dir] (harmless after a crash; tidy on startup). */
    fun deleteStalePending(dir: File) {
        dir.listFiles { file -> file.name.startsWith(PENDING_PREFIX) }?.forEach { it.delete() }
    }
}
