package com.bittool.signer.core.data.wallet

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.FakeEngine
import com.bittool.signer.core.data.doubles.HmacPublicDataKey
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.parseJsonObject
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Hex
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Test

/** iOS `PublicWalletRepository` (vault-crypto.md §10; HardeningTests.publicWalletFilesAreAuthenticated). */
class FilePublicWalletRepositoryTest {
    private val dir: File = Files.createTempDirectory("public-wallets").toFile()
    private val index = WalletPublicMetadata.parse(FakeEngine.publicIndex("a".repeat(128)))
    private val key = HmacPublicDataKey()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repository(test: kotlinx.coroutines.test.TestScope) = FilePublicWalletRepository(dir, StandardTestDispatcher(test.testScheduler))

    @Test
    fun theMacIsTheSpecifiedHmacOverDomainIdAndBody() {
        // vault-crypto.md §10.3 test vector (computed with Python hmac).
        val id = WalletId.parse("00112233-4455-6677-8899-aabbccddeeff")!!
        val body = """{"displayName":"Public","localWalletId":"00112233-4455-6677-8899-AABBCCDDEEFF"}""".encodeToByteArray()
        val mac = HmacPublicDataKey(ByteArray(32) { it.toByte() }).mac(FilePublicWalletRepository.macInput(id, body))
        assertEquals("5d52224242f35a071ea0fe4e9d091939e104f3cad6a2b557c9dbf18ac376f03b", Hex.encode(mac))
    }

    @Test
    fun publicWalletFilesAreAuthenticated() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        repository.save(index, id, "Public", key)
        val good = repository.snapshot(id, key)
        assertEquals(PublicMetadataStatus.Ready, good.status)
        assertEquals("Public", good.displayName)
        assertEquals(index, good.metadata)
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, HmacPublicDataKey()).status)
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, null).status)
        // Swap the displayed name inside the body: the MAC no longer verifies.
        val file = repository.file(id)
        val envelope = parseJsonObject(file.readBytes())!!
        val body = Base64.getDecoder().decode(envelope.str("body")).decodeToString()
        val tampered = body.replace("\"displayName\":\"Public\"", "\"displayName\":\"Pwned\"")
        file.writeText(JsonObject(envelope + ("body" to JsonPrimitive(Base64.getEncoder().encodeToString(tampered.encodeToByteArray())))).canonicalJson())
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status)
        assertEquals(PublicMetadataStatus.NeedsCompletion, repository.snapshot(WalletId.random(), key).status)
    }

    @Test
    fun theFileIsTheSortedEnvelopeOverTheSortedBody() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        repository.save(index, id, "Main", key)
        val text = repository.file(id).readText()
        val envelope = parseJsonObject(text)!!
        assertEquals(listOf("body", "mac", "schemaVersion"), envelope.keys.toList())
        assertEquals(text, envelope.canonicalJson())
        assertEquals("2", envelope["schemaVersion"].toString())
        assertEquals(64, envelope.str("mac")!!.length)
        val body = parseJsonObject(Base64.getDecoder().decode(envelope.str("body")))!!
        assertEquals(listOf("displayName", "localWalletId", "publicIndex"), body.keys.toList())
        assertEquals(id.toString(), body.str("localWalletId"))
        assertEquals(index.canonicalJson, body.obj("publicIndex")!!.canonicalJson())
        assertFalse(text.contains("xprv"))
    }

    @Test
    fun everyStatusOfTheSnapshotTable() = runTest {
        // vault-crypto.md §10.5.
        val repository = repository(this)
        val id = WalletId.random()
        assertEquals(PublicMetadataStatus.NeedsCompletion, repository.snapshot(id, key).status, "missing file")
        repository.rename(id, "Named only", key)
        val named = repository.snapshot(id, key)
        assertEquals(PublicMetadataStatus.NeedsCompletion, named.status, "no publicIndex")
        assertEquals("Named only", named.displayName)
        repository.file(id).writeText("corrupt public fixture")
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status, "unreadable")
        writeBody(repository, id, JsonObject(mapOf("localWalletId" to JsonPrimitive(id.toString()), "displayName" to JsonPrimitive("   "))))
        val blankName = repository.snapshot(id, key)
        assertEquals(PublicMetadataStatus.Invalid, blankName.status, "invalid displayName")
        assertNull(blankName.displayName)
        writeBody(repository, id, JsonObject(mapOf("localWalletId" to JsonPrimitive(id.toString()), "publicIndex" to JsonObject(mapOf("version" to JsonPrimitive(2))))))
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status, "invalid index")
        val other = WalletId.random()
        writeBody(repository, id, JsonObject(mapOf("localWalletId" to JsonPrimitive(other.toString()))))
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status, "another wallet's body")
        writeBody(repository, id, JsonObject(mapOf("localWalletId" to JsonPrimitive(id.uppercase), "publicIndex" to index.raw)))
        assertEquals(PublicMetadataStatus.Ready, repository.snapshot(id, key).status, "UUIDs compare case-insensitively")
    }

    @Test
    fun aFileMovedToAnotherWalletDoesNotVerify() = runTest {
        val repository = repository(this)
        val first = WalletId.random()
        val second = WalletId.random()
        repository.save(index, first, "First", key)
        repository.file(first).copyTo(repository.file(second))
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(second, key).status)
    }

    @Test
    fun saveKeepsTheNameOfAValidFileAndRenameTheIndex() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        repository.save(index, id, "Kept", key)
        repository.save(index, id, null, key)
        assertEquals("Kept", repository.snapshot(id, key).displayName)
        repository.rename(id, "  Renamed\n", key)
        val renamed = repository.snapshot(id, key)
        assertEquals("Renamed", renamed.displayName)
        assertEquals(index, renamed.metadata, "Renaming keeps a valid index.")
        // An unreadable file loses its index on rename: the wallet needs repair again.
        repository.file(id).writeText("{}")
        repository.rename(id, "After damage", key)
        val damaged = repository.snapshot(id, key)
        assertEquals(PublicMetadataStatus.NeedsCompletion, damaged.status)
        assertEquals("After damage", damaged.displayName)
    }

    @Test
    fun namesAreTrimmedAndLimitedTo40Characters() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        assertFailsWith<VaultError.InvalidName> { repository.rename(id, " \n\t", key) }
        assertFailsWith<VaultError.InvalidName> { repository.rename(id, "x".repeat(41), key) }
        assertFailsWith<VaultError.InvalidName> { repository.save(index, id, "", key) }
        repository.rename(id, "👍🏽".repeat(40), key)
        assertEquals("👍🏽".repeat(40), repository.snapshot(id, key).displayName, "40 grapheme clusters fit.")
        assertFalse(repository.file(WalletId.random()).exists())
    }

    @Test
    fun aRenameToFortyHeavyClustersIsRefusedButAStoredNameStillReads() = runTest {
        // QA audit A04: 40 grapheme clusters of a letter and 60 combining marks pass the iOS rule but could not be a
        // vault name, so a rename refuses them like a 41-character name and leaves the file as it was.
        val heavy = ("e" + "́".repeat(60)).repeat(40)
        val repository = repository(this)
        val id = WalletId.random()
        repository.save(index, id, "Before", key)
        val before = repository.file(id).readBytes()
        assertFailsWith<VaultError.InvalidName> { repository.rename(id, heavy, key) }
        assertTrue(before.contentEquals(repository.file(id).readBytes()))
        assertEquals("Before", repository.snapshot(id, key).displayName)
        // Reading keeps the iOS rule only, so a name stored before this rule existed does not make the wallet invalid.
        repository.save(index, id, heavy, key)
        val stored = repository.snapshot(id, key)
        assertEquals(PublicMetadataStatus.Ready, stored.status)
        assertEquals(heavy, stored.displayName)
    }

    @Test
    fun oversizedAndNonRegularFilesAreInvalid() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        repository.save(index, id, "Linked", key)
        val target = File(dir, "target.json")
        repository.file(id).renameTo(target)
        Files.createSymbolicLink(repository.file(id).toPath(), target.toPath())
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status, "a symlink is not followed")
        repository.file(id).delete()
        repository.file(id).writeBytes(ByteArray(FilePublicWalletRepository.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status)
    }

    @Test
    fun removeDeletesTheSidecarAndToleratesAMissingOne() = runTest {
        val repository = repository(this)
        val id = WalletId.random()
        repository.remove(id)
        repository.save(index, id, "Gone", key)
        repository.remove(id)
        assertFalse(repository.file(id).exists())
        assertEquals(PublicMetadataStatus.NeedsCompletion, repository.snapshot(id, key).status)
    }

    @Test
    fun aBodyIsReadOnlyFromStrictlyPaddedBase64() = runTest {
        // Foundation `Data(base64Encoded:)` (PublicWalletRepository.swift:86) refuses unpadded text, whitespace and other
        // alphabets. The JDK decoder accepts unpadded text, so the repository checks the length itself.
        assertEquals("a", FilePublicWalletRepository.decodeBase64("YQ==")?.decodeToString())
        assertNull(FilePublicWalletRepository.decodeBase64("YQ"))
        assertNull(FilePublicWalletRepository.decodeBase64("Y Q="))
        assertNull(FilePublicWalletRepository.decodeBase64("YQ-_"))
        assertNull(FilePublicWalletRepository.decodeBase64("YQ==\n"))
        val repository = repository(this)
        val id = WalletId.random()
        // One of three consecutive name lengths gives a body whose encoding ends in padding.
        val envelope = listOf("Padded", "Paddedx", "Paddedxx").firstNotNullOf { name ->
            repository.save(index, id, name, key)
            parseJsonObject(repository.file(id).readBytes())!!.takeIf { it.str("body")!!.endsWith("=") }
        }
        assertEquals(PublicMetadataStatus.Ready, repository.snapshot(id, key).status)
        // The same bytes under a valid MAC, but written without the padding.
        val unpadded = envelope.str("body")!!.trimEnd('=')
        repository.file(id).writeText(JsonObject(envelope + ("body" to JsonPrimitive(unpadded))).canonicalJson())
        assertEquals(PublicMetadataStatus.Invalid, repository.snapshot(id, key).status)
    }

    @Test
    fun staleStagingFilesAreTidiedOnFirstUse() = runTest {
        val publicDir = File(dir, "PublicWallets").apply { mkdirs() }
        val stale = File(publicDir, ".pending-ABC")
        stale.writeText("partial")
        val repository = repository(this)
        assertEquals(publicDir, repository.directory)
        repository.snapshot(WalletId.random(), key)
        assertFalse(stale.exists())
        assertTrue(publicDir.isDirectory)
    }

    /** Writes a correctly authenticated file with an arbitrary body. */
    private fun writeBody(repository: FilePublicWalletRepository, id: WalletId, body: JsonObject) {
        val bytes = body.canonicalJson().encodeToByteArray()
        val envelope = JsonObject(
            mapOf(
                "body" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
                "mac" to JsonPrimitive(Hex.encode(key.mac(FilePublicWalletRepository.macInput(id, bytes)))),
                "schemaVersion" to JsonPrimitive(2),
            ),
        )
        repository.file(id).writeText(envelope.canonicalJson())
    }
}
