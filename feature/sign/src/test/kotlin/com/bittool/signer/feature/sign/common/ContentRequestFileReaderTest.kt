package com.bittool.signer.feature.sign.common

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * "Import File" through the system's document providers (scan-sign.md §5, §23.6): the iOS limit and format rules on
 * real `ContentResolver` reads, and no platform wording in the UI whatever a provider throws (ARCHITECTURE §4.2).
 */
@RunWith(RobolectricTestRunner::class)
class ContentRequestFileReaderTest {

    /** A document provider whose behaviour each test sets. */
    class TestDocuments : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
            query(uri)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = open(uri)
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            var query: (Uri) -> Cursor? = { null }
            var open: (Uri) -> ParcelFileDescriptor? = { null }
        }
    }

    private val uri = Uri.parse("content://$AUTHORITY/document/request.psbt")

    private fun reader(): ContentRequestFileReader {
        Robolectric.setupContentProvider(TestDocuments::class.java, AUTHORITY)
        return ContentRequestFileReader(RuntimeEnvironment.getApplication(), UnconfinedTestDispatcher())
    }

    private fun sized(size: Long?) = MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(size)) }

    private fun document(bytes: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("request", ".psbt").apply { writeBytes(bytes); deleteOnExit() }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun aBinaryPsbtDocumentIsImportedAsBase64() = runTest {
        val psbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte(), 0x01, 0x00)
        TestDocuments.query = { sized(psbt.size.toLong()) }
        TestDocuments.open = { document(psbt) }
        assertEquals("cHNidP8BAA==", reader().read(uri))
    }

    @Test
    fun aReportedSizeOverTheLimitIsRefusedBeforeReading() = runTest {
        var opened = false
        TestDocuments.query = { sized(512_001) }
        TestDocuments.open = { opened = true; document(ByteArray(1)) }
        val failure = assertFailsWith<SignerException> { reader().read(uri) }
        assertEquals(UiText.Ios("This file is larger than 512 KB."), failure.text)
        assertEquals(false, opened)
    }

    @Test
    fun whateverAProviderThrowsShowsTheFileCouldNotBeRead() = runTest {
        val failures = listOf<() -> Nothing>(
            { throw IllegalStateException("Couldn't read row 0, col -1 from CursorWindow") },
            { throw UnsupportedOperationException("Not supported by this provider") },
            { throw SecurityException("Permission Denial: reading com.example.Documents") },
        )
        for (fail in failures) {
            TestDocuments.query = { fail() }
            val failure = assertFailsWith<SignerException> { reader().read(uri) }
            assertEquals(UiText.Resource(R.string.sign_file_unreadable), failure.text, "never the provider's own wording")
        }
        TestDocuments.query = { sized(null) }
        TestDocuments.open = { throw IllegalArgumentException("Invalid document id") }
        assertEquals(UiText.Resource(R.string.sign_file_unreadable), assertFailsWith<SignerException> { reader().read(uri) }.text)
    }

    private companion object {
        const val AUTHORITY = "com.bittool.signer.test.documents"
    }
}
