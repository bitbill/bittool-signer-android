package com.bittool.signer.core.data.reference

import android.content.Context
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The bundled legal texts (activity-settings.md §9–§10; iOS `LegalDocumentView.blocks(of:)`,
 * `LegalDocumentView.swift:64-70`, and `LicensesView`, `SettingsTab.swift:333-363`). Asset paths are those of
 * [LegalDocument.assetPath]; `app/src/{apk,play}/assets/legal/` override `:core:data`'s copies at merge time
 * (app assets win over library assets). Read on [io] each time (a page opens rarely; nothing is cached).
 */
@Singleton
class AssetLegalDocumentRepository @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : LegalDocumentRepository {

    private val assets = context.assets

    override suspend fun blocks(document: LegalDocument): List<LegalBlock> = withContext(io) {
        val text = readText(document.assetPath)
            ?: return@withContext listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING))
        LegalDocumentParser.parse(text, document.englishTitle)
    }

    override suspend fun thirdPartyNotices(): List<NoticeSection> = withContext(io) {
        NOTICE_FILES.mapNotNull { path -> readText(path)?.let(::section) }
            .ifEmpty { listOf(NoticeSection(title = "", paragraphs = listOf(LegalDocumentRepository.NOTICES_MISSING))) }
    }

    private fun readText(path: String): String? = try {
        assets.open(path).use { it.readBytes() }.decodeToString(throwOnInvalidSequence = true)
    } catch (e: IOException) {
        null
    } catch (e: CharacterCodingException) {
        null
    }

    companion object {
        /** The engine's notices (iOS byte-identical), then the Android app's own dependencies (gaps.md G8). */
        val NOTICE_FILES = listOf("legal/THIRD_PARTY_LICENSES.txt", "legal/THIRD_PARTY_LICENSES_ANDROID.txt")

        /**
         * One notices file: its first line is the title; the rest is split on `"\n\n"` like iOS (`SettingsTab.swift:359`)
         * and kept verbatim, except that the line breaks at the edges of a piece (left by runs of blank lines) are dropped,
         * and so are pieces with nothing else in them.
         */
        internal fun section(text: String): NoticeSection {
            val normalized = text.replace("\r\n", "\n").trimEnd('\n')
            val title = normalized.substringBefore('\n')
            val rest = if ('\n' in normalized) normalized.substringAfter('\n').trimStart('\n') else ""
            return NoticeSection(title, rest.split("\n\n").map { it.trim('\n') }.filter { it.isNotEmpty() })
        }
    }
}
