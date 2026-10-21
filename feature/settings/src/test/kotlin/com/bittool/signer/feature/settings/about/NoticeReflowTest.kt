package com.bittool.signer.feature.settings.about

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Third-Party Notices reflowed to a phone (QA sign-16): line breaks change, the words never do. */
class NoticeReflowTest {
    private val mit = """
        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:
    """.trimIndent()

    private fun texts(paragraph: String): List<String> =
        NoticeReflow.blocks(paragraph).flatMap { (it as? NoticeBlock.Text)?.lines.orEmpty() }.map { it.text }

    @Test
    fun aHardWrappedSentenceBecomesOneLine() {
        assertEquals(listOf(mit.replace('\n', ' ')), texts(mit))
    }

    @Test
    fun aSeparatorIsARuleAndTheHeadlineKeepsItsLines() {
        val header = "========================================================================\n" +
            "@adraffy/ens-normalize 1.11.1 — MIT\nhttps://github.com/adraffy/ens-normalize.js"
        val blocks = NoticeReflow.blocks(header)
        assertEquals(NoticeBlock.Rule, blocks.first())
        assertEquals(
            listOf("@adraffy/ens-normalize 1.11.1 — MIT", "https://github.com/adraffy/ens-normalize.js"),
            (blocks[1] as NoticeBlock.Text).lines.map { it.text },
        )
    }

    @Test
    fun aLongHeadlineDoesNotSwallowItsUrl() {
        val header = "Android Jetpack (AndroidX) libraries — Apache-2.0 (full text below)\nhttps://developer.android.com/jetpack/androidx"
        assertEquals(header.lines(), texts(header))
    }

    @Test
    fun copyrightHoldersAndListItemsKeepTheirOwnLines() {
        val holders = "Copyright (c) 2015-2018 The first holder of this library and friends\n" +
            "Copyright (c) 2019-2025 The second holder of this library and others"
        assertEquals(holders.lines(), texts(holders))
        val list = "Redistribution and use in source and binary forms, with or without\n" +
            "modification, are permitted provided that the following conditions are met:\n" +
            "1. Redistributions of source code must retain the above copyright notice."
        assertEquals(
            listOf(list.lines()[0] + " " + list.lines()[1], list.lines()[2]),
            texts(list),
        )
    }

    @Test
    fun apacheSectionsKeepTheirHangingIndentAndCentredTitlesStayCentred() {
        val section = "   2. Grant of Copyright License. Subject to the terms and conditions of\n" +
            "      this License, each Contributor hereby grants to You a perpetual,\n" +
            "      worldwide, non-exclusive, no-charge, royalty-free, irrevocable\n" +
            "      copyright license to reproduce, prepare Derivative Works of,"
        val line = (NoticeReflow.blocks(section).single() as NoticeBlock.Text).lines.single()
        assertEquals(3, line.indent)
        assertEquals(6, line.hangingIndent)
        assertTrue(line.text.startsWith("2. Grant of Copyright License. Subject"), line.text)
        assertTrue(line.text.endsWith("royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of,"), line.text)

        val title = "                                 Apache License\n" +
            "                           Version 2.0, January 2004\n" +
            "                        https://www.apache.org/licenses/"
        val lines = (NoticeReflow.blocks(title).single() as NoticeBlock.Text).lines
        assertEquals(listOf("Apache License", "Version 2.0, January 2004", "https://www.apache.org/licenses/"), lines.map { it.text })
        assertTrue(lines.all { it.centered && it.indent == 0 })
        // The engine's notices carry the title at the margin and only its version and URL indented.
        val trimmed = (NoticeReflow.blocks(title.trimStart()).single() as NoticeBlock.Text).lines
        assertTrue(trimmed.all { it.centered }, trimmed.toString())
    }

    /** Both bundled notices files: every word survives, in order; separators are the only thing drawn differently. */
    @Test
    fun theBundledNoticesKeepEveryWord() {
        val legal = File("../../core/data/src/main/assets/legal")
        val files = listOf("THIRD_PARTY_LICENSES.txt", "THIRD_PARTY_LICENSES_ANDROID.txt").map { File(legal, it) }
        assertTrue(files.all(File::isFile), "notices files at ${legal.absolutePath}")
        var joinedLines = 0
        for (file in files) {
            for (paragraph in file.readText().replace("\r\n", "\n").split("\n\n").map { it.trim('\n') }.filter { it.isNotEmpty() }) {
                val blocks = NoticeReflow.blocks(paragraph)
                val shown = blocks.flatMap { (it as? NoticeBlock.Text)?.lines.orEmpty() }
                val original = paragraph.lines().filterNot { Regex("""^\s*([=\-_*~#])\1{9,}\s*$""").matches(it) }
                joinedLines += original.count { it.isNotBlank() } - shown.size
                val words = { text: String -> text.replace(Regex("""(?<=\p{L})-\s+(?=\p{L})"""), "-").split(Regex("""\s+""")).filter(String::isNotEmpty) }
                assertEquals(words(original.joinToString("\n")), words(shown.joinToString("\n") { it.text }), "in ${file.name}:\n$paragraph")
            }
        }
        assertTrue(joinedLines > 1_000, "the hard-wrapped licence texts are reflowed ($joinedLines line breaks joined)")
    }
}
