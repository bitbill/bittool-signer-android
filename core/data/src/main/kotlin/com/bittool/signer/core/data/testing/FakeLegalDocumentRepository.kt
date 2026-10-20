package com.bittool.signer.core.data.testing

import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser

/**
 * A configurable [LegalDocumentRepository] for tests (Wave 2). **Test double only.** Unconfigured content returns what
 * the real repository returns for missing assets: one paragraph [LegalDocumentParser.MISSING] for a document, the
 * single [LegalDocumentRepository.NOTICES_MISSING] section for the notices.
 */
class FakeLegalDocumentRepository(
    @Volatile var documents: Map<LegalDocument, List<LegalBlock>> = emptyMap(),
    @Volatile var notices: List<NoticeSection> = listOf(NoticeSection("", listOf(LegalDocumentRepository.NOTICES_MISSING))),
) : LegalDocumentRepository {

    override suspend fun blocks(document: LegalDocument): List<LegalBlock> =
        documents[document] ?: listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING))

    override suspend fun thirdPartyNotices(): List<NoticeSection> = notices

    companion object {
        /** Blocks parsed from [text] the way the real repository parses a bundled file. */
        fun parsed(document: LegalDocument, text: String): List<LegalBlock> = LegalDocumentParser.parse(text, document.englishTitle)
    }
}
