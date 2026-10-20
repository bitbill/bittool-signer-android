package com.bittool.signer.core.data.doubles

import com.bittool.signer.core.model.SecretText

/** Runs [block] with a copy of [this] as a secret and wipes it afterwards (JVM and instrumented store tests). */
inline fun <R> String.asSecret(block: (SecretText) -> R): R = SecretText.of(this).use(block)
