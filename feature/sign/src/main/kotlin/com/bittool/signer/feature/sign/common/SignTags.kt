package com.bittool.signer.feature.sign.common

/**
 * Test tags of the Scan tab: the iOS `accessibilityIdentifier`s the e2e suite addresses (scan-sign.md §20, gaps.md
 * G1.8–G1.12). The scanner's own tags (`scanner.*`) live in `:core:scanner`, the review components' (`review.*`) in
 * `:core:ui`, and the password field is tagged "Wallet password" by `PasswordField`.
 */
object SignTags {
    const val SCAN = "signing.scan"
    const val PASTE = "signing.paste"
    const val FILE = "signing.file"
    const val PAYLOAD = "signing.payload"
    const val DECODE = "signing.decode"

    const val REJECT = "signing.reject"
    const val MATCH_ERROR = "signing.matchError"
    const val RETRY_MATCH = "signing.retryMatch"
    const val RISK = "signing.risk"
    const val AUTHORIZE = "signing.authorize"

    const val AUTHORIZE_SIGN = "authorize.sign"

    const val SIGNATURE_TITLE = "signature.title"
    /** `PayloadDisclosure` tag: it adds `signature.payloadToggle` and `signature.payloadCopy`. */
    const val SIGNATURE_PAYLOAD = "signature.payload"
    const val SIGNATURE_EXPORT = "signature.export"
    const val SIGNATURE_DONE = "signature.done"

    /** Android-only: the page roots (screenshot tests and the e2e suite wait for them). */
    const val HOME = "scan.home"
    const val REVIEW = "scan.review"
    const val SIGNATURE = "scan.signature"
    const val PASTE_SHEET = "signing.pasteSheet"
    const val AUTHORIZE_SHEET = "authorize.sheet"
    /** Android-only: the review's scrolling content and its pinned bar (absent while the wallet is matched). */
    const val REVIEW_LIST = "scan.review.list"
    const val SIGN_BAR = "signing.bar"
}
