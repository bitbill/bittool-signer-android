package com.bittool.signer.core.model.json

import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertNull
import org.junit.Test

/** Strict readers (models-state.md §0.2) and the canonical/pretty printers (§0.5–0.6). */
class JsonTest {
    private fun obj(text: String) = parseJsonObject(text)!!

    @Test
    fun readersNeverCoerceTypes() {
        val raw = obj("""{"s":"1","n":1,"f":1.5,"e":1e3,"b":true,"big":3543024904,"a":["x","y"],"mixed":["x",1],"o":{}}""")
        assertEquals("1", raw.str("s"))
        assertNull(raw.str("n"))
        assertEquals(1L, raw.long("n"))
        assertNull(raw.long("s"))
        assertNull(raw.long("f"))
        assertNull(raw.long("e"))
        assertNull(raw.long("b"))
        assertEquals(3543024904L, raw.long("big"))
        assertNull(raw.int("big"), "fingerprints beyond Int.MAX never become an Int")
        assertEquals(1.5, raw.double("f"))
        assertEquals(1.0, raw.double("n"))
        assertNull(raw.double("s"))
        assertNull(raw.double("b"))
        assertEquals(true, raw.bool("b"))
        assertNull(raw.bool("n"))
        assertEquals(listOf("x", "y"), raw.strings("a"))
        assertNull(raw.strings("mixed"), "a list with one wrong element fails as a whole")
        assertNull(raw.objects("a"))
        assertEquals(JsonObject(emptyMap()), raw.obj("o"))
        assertNull(raw.obj("missing"))
    }

    @Test
    fun canonicalJsonSortsKeysRecursivelyAndKeepsNumbers() {
        val raw = obj("""{"b":1,"a":{"d":[3,{"z":true,"y":null}],"c":"/x"},"num":1942346250}""")
        assertEquals("""{"a":{"c":"/x","d":[3,{"y":null,"z":true}]},"b":1,"num":1942346250}""", raw.canonicalJson())
        assertEquals("""{"q":"a\"b\\c\n"}""", obj("""{"q":"a\"b\\c\n"}""").canonicalJson())
        assertEquals("""{"t":"👋 世界 é"}""", obj("""{"t":"👋 世界 é"}""").canonicalJson())
    }

    @Test
    fun prettyJsonUsesAppleStyle() {
        val raw = obj("""{"signature":"ab","request-id":{"uuid":"01"},"list":[1,"/"],"empty":{}}""")
        assertEquals(
            "{\n  \"empty\" : {},\n  \"list\" : [\n    1,\n    \"/\"\n  ],\n  \"request-id\" : {\n    \"uuid\" : \"01\"\n  },\n  \"signature\" : \"ab\"\n}",
            raw.prettyJson(),
        )
    }

    @Test
    fun invalidTextIsNotAnObject() {
        assertNull(parseJsonObject("not json"))
        assertNull(parseJsonObject("[1]"))
        assertNull(parseJsonObject(byteArrayOf(0xC3.toByte())))
    }
}
