package com.bittool.signer.e2e.support

/**
 * Public test data of the iOS suite, byte for byte (gaps.md G1.0 "Constants"; `BitToolSignerUITests.swift:9-16`).
 * Nothing here is a real wallet: the phrase is the public BIP39 vector (master fingerprint 73C5DA0A = 1942346250).
 * Never log a generated phrase; these public values may appear in failure messages.
 */
object Fixtures {
    const val PUBLIC_RECOVERY_PHRASE =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    const val FIXTURE_PASSWORD = "Public-Test-Password1!"

    const val PUBLIC_SIGNING_REQUEST =
        "UR:ETH-SIGN-REQUEST/OSADTPDAGDMYCNHYNLVAVSFZFYONCAGYWPFMSOFWPFAOHDECFWINJYGUINIOJTIHJPCXJOKPIDJZINIACXGOGACXIYINKSJYKPJPIHDMCXGLJLCXIYKPJTIEJKCXJLJPCXJOIHJPJNINJKJKINJLJTJKDMAXAXAAADAHTAADDYOEADLECSDWYKCSFNYKAEYKAEWKAEWKAOCYJKSKTNBKAMGHMKHDWSZCCNDNFZEOVEKIMHAEFSFPWPEEWPPLTNMWATJKGDKPIDJZINIACXJYIHJKJYCXIYINKSJYKPJPIHFWHGMKCH"

    /** Fixture 1 approving unlimited USDT to Permit2 (in the bundled known-contract library on every EVM network). */
    const val APPROVE_KNOWN_SPENDER_REQUEST =
        "UR:ETH-SIGN-REQUEST/OLADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDJOAOYAJNADATLRFRNYSGAELPAAPDCHSPAELFWDHNMWTNSELBMDLGDMVWCNOECXIDAMNLFEMSSEFSLSCKSTLAROFYASHYOSQDAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAECPTYJKAXBSBYJNUEWLYNQZFTSTLUOTZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMZMRTAXAAAAADAHTAADDYOEADLECSDWYKCSFNYKAEYKAEWKAEWKAOCYJKSKTNBKAMGHMKHDWSZCCNDNFZEOVEKIMHAEFSFPWPEEWPPLTNMWDIFDNSZE"

    /** The same approval to an address the library does not list. */
    const val APPROVE_UNLISTED_SPENDER_REQUEST =
        "UR:ETH-SIGN-REQUEST/OLADTPDAGDNDCAWMGTFRKIGRPMNDUTDNBTKGFSSBJNAOHDJOAOYAJNADATLRFRNYSGAELPAAPDCHSPAELFWDHNMWTNSELBMDLGDMVWCNOECXIDAMNLFEMSSEFSLSCKSTLAROFYASHYOSQDAEAEAEAEAEAEAEAEAEAEAEAEBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYBYAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEAEBSFWFZRTAXAAAAADAHTAADDYOEADLECSDWYKCSFNYKAEYKAEWKAEWKAOCYJKSKTNBKAMGHMKHDWSZCCNDNFZEOVEKIMHAEFSFPWPEEWPPLTNMWMEWZMEJO"

    const val MISMATCH_PASSWORD = "Mismatch-Password1!"
    const val BACKUP_PASSWORD = "UiTest-Backup8!"
    const val WRONG_PASSWORD = "Wrong-Password1!"
    const val UPDATED_PASSWORD = "Updated-Test-Password2!"
    const val RECOVERED_PASSWORD = "Recovered-Test-Password3!"

    /** A Spanish phrase typed without accents ("dragon" for "dragón", UIT:273). */
    const val SPANISH_PHRASE = "obra diadema gorila farmacia colgar gorra pausa talar cocina duda dragon optar"

    /** Valid Italian, and valid Spanish once accents are added: two wallets, so the app asks (UIT:287). */
    const val AMBIGUOUS_PHRASE = "nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo"

    /** The fixture wallets' master fingerprint (public test phrase). */
    const val FINGERPRINT = "73C5DA0A"

    /** The debug harness' failing biometric (gaps.md G3; iOS "Face ID was cancelled"). */
    const val BIOMETRIC_FAILURE = "Biometric authentication was cancelled"
}
