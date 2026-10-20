package com.bittool.signer.feature.sign.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The text a chosen request file is imported as (iOS `ScanHomeView.importFile`, `Features/Sign/ScanTab.swift:225-239`;
 * scan-sign.md §5, §23.6). Pure, so the rules are unit-tested on the JVM.
 */
object RequestFile {
    /** iOS limit: decimal 512,000 bytes, not 524,288. */
    const val MAX_BYTES = 512_000

    private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())

    /** "This file is larger than 512 KB." */
    fun tooLarge(): AppIssue = AppIssue("This file is larger than 512 KB.")

    /**
     * A binary PSBT (`psbt\xff…`) becomes standard padded Base64 without line breaks; any other content must be
     * strict UTF-8 and is used unchanged; anything else fails with "Choose a PSBT file or a text file containing a UR
     * request.". [bytes] must already respect [MAX_BYTES].
     */
    fun text(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw tooLarge()
        if (bytes.size >= PSBT_MAGIC.size && PSBT_MAGIC.indices.all { bytes[it] == PSBT_MAGIC[it] }) {
            return Base64.getEncoder().encodeToString(bytes)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw AppIssue("Choose a PSBT file or a text file containing a UR request.")
        }
    }

    /**
     * Reads at most [MAX_BYTES] from [input]; one byte more means the file is too large (a provider that reports no
     * size is never read without a bound, unlike iOS, which treats an unknown size as 0).
     */
    fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        if (total > MAX_BYTES) throw tooLarge()
        return buffer.copyOf(total)
    }
}

/** Reads a request document chosen in the system picker (Storage Access Framework). */
fun interface RequestFileReader {
    /**
     * The import text of [uri] ([RequestFile.text]). Failures are `SignerException`s with the text to show: the iOS
     * limit and format messages, or "The file could not be read." for a document the system cannot open or read.
     */
    suspend fun read(uri: Uri): String
}

/** [RequestFileReader] over the app's `ContentResolver`, on the I/O dispatcher. */
class ContentRequestFileReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RequestFileReader {
    override suspend fun read(uri: Uri): String = withContext(io) {
        val resolver = context.contentResolver
        try {
            val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null
            }
            if (size != null && size > RequestFile.MAX_BYTES) throw RequestFile.tooLarge()
            val bytes = resolver.openInputStream(uri)?.use(RequestFile::readBounded) ?: throw IOException("No input stream")
            RequestFile.text(bytes)
        } catch (e: SignerException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SignerException(UiText.Resource(R.string.sign_file_unreadable), e)
        } catch (e: RuntimeException) {
            // Whatever the document provider throws (SecurityException, IllegalArgumentException, IllegalStateException
            // from a cursor, UnsupportedOperationException…): its platform wording never reaches the UI (ARCHITECTURE §4.2).
            throw SignerException(UiText.Resource(R.string.sign_file_unreadable), e)
        }
    }
}

/** Writes the signature response to a document created in the system picker. */
fun interface SignatureFileWriter {
    /** Writes [text] as UTF-8 to [uri], truncating what was there. Throws the platform failure. */
    suspend fun write(uri: Uri, text: String)
}

/** [SignatureFileWriter] over the app's `ContentResolver`, on the I/O dispatcher. */
class ContentSignatureFileWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SignatureFileWriter {
    override suspend fun write(uri: Uri, text: String) = withContext(io) {
        // "wt": "w" does not truncate on some providers (scan-sign.md §23.6).
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SignFilesModule {
    @Binds abstract fun requestFileReader(reader: ContentRequestFileReader): RequestFileReader

    @Binds abstract fun signatureFileWriter(writer: ContentSignatureFileWriter): SignatureFileWriter
}
