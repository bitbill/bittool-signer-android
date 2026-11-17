package com.bittool.signer.core.model

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The identifier rule of iOS Revision 13 (`AddressFormatter.groups`): only a compact identifier is cut into groups or
 * shortened. The engine's placeholder for a Solana account supplied by an address lookup table is prose
 * (`engine/src/solana.js` `lookupLabel`), and must reach the screen whole.
 */
class AddressFormatterTest {
    private val placeholder = "[lookup table 4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofM · entry 3]"

    @Test
    fun aLookupTablePlaceholderIsNotGroupedOrShortened() {
        assertFalse(AddressFormatter.isIdentifier(placeholder))
        assertEquals(listOf(placeholder), AddressFormatter.groups(placeholder))
        assertEquals(listOf(placeholder), AddressFormatter.groups(placeholder, 8))
        assertEquals(placeholder, AddressFormatter.short(placeholder, 6, 6))
        assertFalse(AddressFormatter.looksLikeAddress(placeholder), "never offered as a copyable address")
    }

    @Test
    fun anyWhitespaceMakesAValueProse() {
        for (value in listOf("a b", "line\nbreak", "tab\there", "no-break space", "ideographic　space")) {
            assertFalse(AddressFormatter.isIdentifier(value), value)
            assertEquals(listOf(value), AddressFormatter.groups(value))
        }
    }

    @Test
    fun identifiersAreStillGroupedAndShortened() {
        val evm = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        assertTrue(AddressFormatter.isIdentifier(evm))
        assertEquals("0x9858", AddressFormatter.groups(evm).first())
        assertEquals(10, AddressFormatter.groups(evm).size)
        assertEquals("0x9858Ef…aEda94", AddressFormatter.short(evm, 6, 6))
        val solana = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        assertEquals(listOf("HAgk", "14Jp", "MQLg", "t6rV", "gv7c", "BQFJ", "WFto", "5Dqx", "i472", "uT3D", "Kpqk"), AddressFormatter.groups(solana))
    }
}
