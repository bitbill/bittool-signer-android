package com.bittool.signer.core.common

import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.InvalidFileException
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun writesReplaceAtomicallyAndLeaveNoStagingFile() {
        val target = File(folder.root, "BitToolSigner/PublicWallets/a.json")
        AtomicFiles.write(target, "one".encodeToByteArray())
        AtomicFiles.write(target, "two".encodeToByteArray())
        assertContentEquals("two".encodeToByteArray(), AtomicFiles.readBounded(target, 10))
        assertEquals(listOf("a.json"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun readsAreBoundedAndRefuseNonRegularFiles() {
        assertNull(AtomicFiles.readBounded(File(folder.root, "missing"), 10))
        val big = folder.newFile("big").apply { writeBytes(ByteArray(11)) }
        assertFailsWith<InvalidFileException> { AtomicFiles.readBounded(big, 10) }
        assertFailsWith<InvalidFileException> { AtomicFiles.readBounded(folder.newFolder("dir"), 10) }
        val link = File(folder.root, "link").toPath()
        Files.createSymbolicLink(link, folder.newFile("real").apply { writeText("x") }.toPath())
        assertFailsWith<InvalidFileException> { AtomicFiles.readBounded(link.toFile(), 10) }
    }

    @Test
    fun stalePendingFilesAreRemoved() {
        val dir = folder.newFolder("Vaults")
        File(dir, ".pending-ABC").writeText("x")
        File(dir, "keep.bsvault").writeText("y")
        AtomicFiles.deleteStalePending(dir)
        assertEquals(listOf("keep.bsvault"), dir.list()!!.toList())
        assertTrue(AtomicFiles.delete(File(dir, "keep.bsvault")))
        assertTrue(AtomicFiles.delete(File(dir, "keep.bsvault")))
    }
}
