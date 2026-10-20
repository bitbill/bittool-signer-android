package com.bittool.signer.feature.activity

import android.content.Context
import android.net.Uri
import com.bittool.signer.core.common.coroutines.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Writes a text document the person chose with the system file picker (iOS `fileExporter` +
 * `SignedResponseDocument`, `SignatureResultView.swift:100-111`): UTF-8, replacing the document's content. The app
 * opens no other app; if the person picks a cloud provider, that provider uploads the file.
 */
fun interface TextDocumentWriter {
    suspend fun write(uri: Uri, text: String)
}

/** [TextDocumentWriter] through the `ContentResolver`, on the I/O dispatcher. */
class ContentResolverTextDocumentWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TextDocumentWriter {
    override suspend fun write(uri: Uri, text: String) = withContext(io) {
        // No message: the screen then shows its own "could not be saved" text rather than a content URI.
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException()
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityModule {
    @Binds
    abstract fun textDocumentWriter(writer: ContentResolverTextDocumentWriter): TextDocumentWriter
}
