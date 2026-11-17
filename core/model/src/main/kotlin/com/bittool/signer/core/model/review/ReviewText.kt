package com.bittool.signer.core.model.review


/**
 * The translation of one engine phrase in the current interface language, or null when the catalogue has no entry
 * (iOS detects a missing entry with a sentinel default). `:core:l10n` provides the Android implementation backed by
 * `IosStrings.lookup` + `Resources.getString`.
 */
fun interface EngineTextLookup {
    fun translation(english: String): String?

    companion object {
        /** No translations: everything stays the engine's English (JVM tests, English UI). */
        val None: EngineTextLookup = EngineTextLookup { null }
    }
}

/**
 * Draw-time translation of engine English (models-state.md §4, iOS `Models/ReviewText.swift`). The engine's labels,
 * actions, headlines, warnings and refusals are covered by the request fingerprint, so they are never translated inside
 * the engine; the UI translates when drawing and falls back to the English whenever a part has no entry, so a new text
 * added to the engine shows up in English rather than disappearing.
 *
 * Every kind of engine text goes through [render] (iOS 2026-11-17, audit M-01: about half the warnings, HIGH RISK ones
 * among them, stayed English because a batch number or a value hid them from the lookup). Each kind is tried as an exact
 * key, then as a [TemplateTable] template, then split the way that kind is built (see [Kind]).
 *
 * The lookup returns null for every key in the English interface ([EngineTextLookup] in `:core:l10n`), as iOS has no
 * English table: English is always drawn exactly as the engine wrote it, never rebuilt from parts.
 */
class ReviewText(private val lookup: EngineTextLookup) {
    /**
     * What a piece of engine text looks like once drawn. [complete] is false when any part of it fell back to the
     * engine's English; the coverage test reads it, the interface only draws [text].
     */
    data class Rendered(val text: String, val complete: Boolean) {
        companion object {
            fun english(text: String): Rendered = Rendered(text, complete = false)
        }
    }

    /** The four shapes engine text comes in, each with its own way of being split into parts the catalogue translates. */
    enum class Kind {
        /** A whole name: a display action, a review title, a counterparty label, a unit word. */
        Phrase,

        /** The line under the action, which may carry a value the engine filled in. */
        Headline,

        /** A Details label, possibly numbered ("Instruction 2 · Amount (SOL)"). */
        Label,

        /** A warning or a refusal: a sentence, possibly numbered ("HIGH RISK: Order 2: …"). */
        Sentence,
    }

    /** A whole phrase: a display action, a review title, a counterparty label. `""` stays `""`. */
    fun phrase(english: String): String = render(english, Kind.Phrase).text

    /** A headline that may carry a value ("Method 0x3593564c", "Safe: Send 1 ETH"); the value is reproduced exactly. */
    fun headline(english: String): String = render(english, Kind.Headline).text

    /**
     * A Details label: possibly built by appending a unit the engine decided ("Amount (USDC)") or by numbering the part
     * of the request it belongs to ("Instruction 2 · Amount (SOL)", "Call 3: Recipient"). The unit and number stay.
     */
    fun label(english: String): String = render(english, Kind.Label).text

    /**
     * A warning or a refusal the engine wrote. Callers decide severity from the **engine's** English, never from the
     * result: "HIGH RISK" is a marker in the source text, not in the translation.
     */
    fun sentence(english: String): String = render(english, Kind.Sentence).text

    /**
     * A word drawn beside an amount: "Unlimited", or a unit such as "raw units" or "native coin" (iOS `ReviewText.unit`,
     * same rule). A number is never looked up; anything else is, through [phrase]. A token symbol therefore changes only
     * if the catalogue has it as a key, which among the registry's symbols is only "NFT" (and "OK"); every other symbol
     * comes back as the engine wrote it.
     */
    fun unit(english: String): String {
        val lowercaseWords = LOWERCASE_WORDS.matches(english)
        val notANumber = !NUMBER.matches(english)
        return if (lowercaseWords || notANumber) phrase(english) else english
    }

    /** An amount as the card draws it: the number as the engine wrote it and the word beside it, both through [unit]. */
    fun amountLine(amount: String, symbol: String?): String = listOfNotNull(unit(amount), symbol?.let(::unit)).joinToString(" ")

    /** Draws engine text of one kind through this language's table. */
    fun render(english: String, kind: Kind): Rendered {
        if (english.isEmpty()) return Rendered(english, complete = true)
        direct(english)?.let { return Rendered(it, complete = true) }
        return when (kind) {
            Kind.Phrase -> phraseParts(english)
            Kind.Headline -> headlineParts(english)
            Kind.Label -> labelParts(english)
            Kind.Sentence -> sentenceParts(english)
        }
    }

    /**
     * An exact catalogue entry, or a registered template with its values put back. Android also treats the straight and
     * the typographic apostrophe as one character: the engine writes `transaction's` where catalogue keys say
     * `transaction’s`.
     */
    private fun direct(english: String): String? {
        translationOf(english)?.let { return it }
        if (english.contains('\'')) translationOf(english.replace('\'', '\u2019'))?.let { return ownWordsIfSame(it, english) }
        for (template in TEMPLATES) {
            val values = template.match(english)?.toMutableList() ?: continue
            val localized = translationOf(template.english) ?: return null
            // A value that is engine text itself is translated in turn; data, or text the catalogue lacks, is drawn
            // exactly as the engine wrote it.
            TemplateTable.slots[template.english]?.forEach { (index, kind) ->
                if (index in values.indices) {
                    val inner = render(values[index], kind)
                    if (inner.complete) values[index] = inner.text
                }
            }
            return substitute(localized, values)?.let { ownWordsIfSame(it, english) }
        }
        return null
    }

    /**
     * The engine's own [english] when [translated] says exactly the same apart from the apostrophe: the engine's text is
     * never retyped for nothing.
     */
    private fun ownWordsIfSame(translated: String, english: String): String =
        if (translated.replace('\u2019', '\'') == english.replace('\u2019', '\'')) english else translated

    private fun translationOf(english: String): String? = if (english.isEmpty()) null else lookup.translation(english)

    /** One numbered key — "Call %@", "Orders %@ (+%@ more)" — filled with its numbers, drawn as the engine wrote them. */
    private fun numbered(key: String, values: List<String>): Rendered? {
        val localized = translationOf(key) ?: return null
        return substitute(localized, values)?.let { Rendered(it, complete = true) }
    }

    // "Safe: Send", "TRON Transfer tokens": a fixed prefix on a phrase that translates on its own.
    private fun phraseParts(english: String): Rendered {
        groups("Safe: (.+)", english)?.let { g ->
            translationOf("Safe:")?.let { safe -> return join(" ", Rendered(safe, true), render(g[0], Kind.Phrase)) }
        }
        groups("TRON (.+)", english)?.let { g -> return join(" ", Rendered("TRON", true), render(g[0], Kind.Phrase)) }
        return Rendered.english(english)
    }

    private fun headlineParts(english: String): Rendered {
        for (prefix in PREFIXES) {
            if (!english.startsWith(prefix)) continue
            val value = english.substring(prefix.length)
            if (value.isEmpty()) break
            val key = prefix.dropLast(1)
            val head = translationOf(key)?.let { Rendered(it, true) } ?: Rendered(key, false)
            // "Safe: …" and "Batch: …" wrap another headline; "Method 0x…" and "New …" wrap a value.
            val rest = if (prefix == "Safe: " || prefix == "Batch: ") render(value, Kind.Headline) else Rendered(value, true)
            return join(" ", head, rest)
        }
        return Rendered.english(english)
    }

    private fun labelParts(english: String): Rendered {
        // "Order 3 · Also paid · Item 2": each part on its own.
        val dot = english.indexOf(" \u00b7 ")
        if (dot >= 0) {
            return join(" \u00b7 ", render(english.substring(0, dot), Kind.Label), render(english.substring(dot + 3), Kind.Label))
        }
        // "Call 3: Recipient", "Call 2: Call 1: Method"
        groups("($NUMBERED_PARTS) ([0-9]+): (.+)", english)?.let { g ->
            numbered("${g[0]} %@", listOf(g[1]))?.let { head -> return join(": ", head, render(g[2], Kind.Label)) }
        }
        // "Output 2", and "Output 2 ownership", "Token 1 allowance (raw units)"
        groups("($NUMBERED_PARTS) ([0-9]+)", english)?.let { g -> numbered("${g[0]} %@", listOf(g[1]))?.let { return it } }
        groups("($NUMBERED_PARTS) ([0-9]+) (.+)", english)?.let { g -> numbered("${g[0]} %@ ${g[2]}", listOf(g[1]))?.let { return it } }
        groups("Safe: (.+)", english)?.let { g ->
            translationOf("Safe:")?.let { safe -> return join(" ", Rendered(safe, true), render(g[0], Kind.Label)) }
        }
        // "Amount (USDC)": the stem translates, the symbol is the engine's value. A bracket that says something —
        // "(stored, not enforced)", "(raw units)" — is part of the label, which then has to be in the catalogue whole.
        groups("(.+?) \\(([A-Za-z0-9.$-]+)\\)", english)?.let { g ->
            val stem = render(g[0], Kind.Label)
            if (!stem.complete) return Rendered.english(english)
            return Rendered(stem.text + " (" + g[1] + ")", complete = true)
        }
        return Rendered.english(english)
    }

    private fun warningLabel(label: String): Rendered {
        groups("(Call|Order) ([0-9]+)", label)?.let { g -> numbered("${g[0]} %@", listOf(g[1]))?.let { return it } }
        groups("All ([0-9]+) orders", label)?.let { g -> numbered("All %@ orders", g)?.let { return it } }
        groups("Orders (.+) \\(\\+([0-9]+) more\\)", label)?.let { g -> numbered("Orders %@ (+%@ more)", g)?.let { return it } }
        groups("Orders (.+)", label)?.let { g -> numbered("Orders %@", g)?.let { return it } }
        return Rendered.english(label)
    }

    // A numbered sentence is put back together only when every part of it translates. Otherwise it is drawn exactly as
    // the engine wrote it: in English the parts would come back in a different order ("Order 2: HIGH RISK: …").
    private fun sentenceParts(english: String): Rendered {
        val composed = numberedSentence(english)
        return if (composed?.complete == true) composed else Rendered.english(english)
    }

    private fun numberedSentence(english: String): Rendered? {
        // "HIGH RISK: Order 2: You receive nothing…" is "HIGH RISK: You receive nothing…" said of order 2. The sentence
        // keeps its marker; the label leads.
        groups("(HIGH RISK|HIGH FEE): ($WARNING_LABEL): (.+)", english)?.let { g ->
            return join(": ", warningLabel(g[1]), render("${g[0]}: ${g[2]}", Kind.Sentence))
        }
        // "UNLIMITED spending approval (Call 3)."
        groups("(.+) \\(($WARNING_LABEL)\\)\\.", english)?.let { g ->
            var inner = render(g[0] + ".", Kind.Sentence)
            if (!inner.complete) inner = render(g[0], Kind.Sentence)
            val label = warningLabel(g[1])
            return Rendered(inner.text + " (" + label.text + ")", complete = inner.complete && label.complete)
        }
        groups("($WARNING_LABEL): (.+)", english)?.let { g -> return join(": ", warningLabel(g[0]), render(g[1], Kind.Sentence)) }
        return null
    }

    /**
     * One engine text that carries values (iOS `ReviewText.Template`). [english] is the catalogue key with `%@` where a
     * value goes: both the translation key and the pattern the engine's output is matched against, so the two cannot
     * drift apart. The fixed text is matched literally (regular-expression characters in it or in a value change
     * nothing), apostrophes either way.
     */
    class Template(val english: String) {
        private val pattern: Regex = Regex(
            "^" + english.split("%@").joinToString("(.+?)") { piece -> literal(piece) } + "$",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** The values this text filled in, in order, or null when it is a different text. */
        fun match(text: String): List<String>? = pattern.matchEntire(text)?.groupValues?.drop(1)

        private companion object {
            fun literal(piece: String): String =
                piece.split('\'', '\u2019').joinToString("['\u2019]") { if (it.isEmpty()) "" else Regex.escape(it) }
        }
    }

    companion object {
        private val PREFIXES = listOf("Method ", "Safe: ", "Batch: ", "New ")

        /**
         * Whether a headline is the requester's own text rather than the engine's: the words of a message, or the type
         * name of typed data the engine does not recognise. Those are drawn exactly as they arrive: a message that
         * happens to equal a catalogue entry ("Approve delegate") must not be shown translated, because the text on
         * screen would then differ from the text being signed (iOS I-01).
         */
        fun headlineIsTheirs(action: String?): Boolean = action == "Sign Message" || action == "Sign Typed Data"

        /** The parts of a request the engine numbers, as they prefix labels. */
        private const val NUMBERED_PARTS = "Call|Order|Instruction|Input|Output|Authorization|Item|Token"

        /** The labels the engine puts on a warning about one part of a batch: "Call 3", "All 40 orders", "Orders 1, 3 (+5 more)". */
        private const val WARNING_LABEL = "Call [0-9]+|Order [0-9]+|All [0-9]+ orders|Orders [0-9]+(?:, [0-9]+)*(?: \\(\\+[0-9]+ more\\))?"

        private val LOWERCASE_WORDS = Regex("^[a-z][a-z ]*$")
        private val NUMBER = Regex("^[0-9.,\\s]+$")

        /**
         * The engine texts that carry a value, in the iOS order ([TemplateTable]): a more specific template must come
         * before one that could also match it.
         */
        val TEMPLATES: List<Template> = TemplateTable.all.map(::Template)

        private fun join(separator: String, vararg parts: Rendered): Rendered =
            Rendered(parts.joinToString(separator) { it.text }, parts.all { it.complete })

        /** The groups a regular expression captured, or null when it does not match the whole text. */
        private fun groups(pattern: String, text: String): List<String>? =
            Regex("^$pattern$", RegexOption.DOT_MATCHES_ALL).matchEntire(text)?.groupValues?.drop(1)

        private val SPECIFIER = Regex("%(?:([0-9]+)\\$)?([sd%])")

        /**
         * [values] substituted into the translated [template] (iOS `ReviewText.substitute`, Android specifiers). Numbered
         * specifiers take the value of their number, so a translation that reorders the values ("this instruction’s
         * %2$s accounts, %1$s of which") gets them right; unnumbered ones take them in order. Null — the caller then
         * shows the English — when the specifiers do not account for exactly the values given.
         */
        fun substitute(template: String, values: List<String>): String? {
            val specifiers = SPECIFIER.findAll(template).filter { it.groupValues[2] != "%" }.toList()
            if (specifiers.size != values.size) return null
            val positions = specifiers.map { it.groupValues[1].toIntOrNull() }
            val numbered = positions.all { it != null }
            if (!numbered && positions.any { it != null }) return null
            if (numbered && positions.map { it!! }.sorted() != (1..values.size).toList()) return null
            var next = 0
            return SPECIFIER.replace(template) { m ->
                when {
                    m.groupValues[2] == "%" -> "%"
                    numbered -> values[m.groupValues[1].toInt() - 1]
                    else -> values[next++]
                }
            }
        }

        /**
         * Shortens a decimal amount to at most [places] fraction digits, rounding **away from zero** so a fee ceiling is
         * never shown below what the request allows ("0.999999999 ETH" → "1.00000000 ETH"). Works on digit strings, so a
         * whole part beyond `Long` never overflows; anything it cannot round is returned unchanged
         * (models-state.md §4, ReviewTextTests).
         */
        fun shortDecimal(text: String, places: Int = 8): String {
            val space = text.indexOf(' ')
            val number = if (space >= 0) text.substring(0, space) else text
            val unit = if (space >= 0) " " + text.substring(space + 1) else ""
            val dot = number.indexOf('.')
            if (dot < 0 || !number.drop(1).all { it in '0'..'9' || it == '.' }) return text
            val whole = number.substring(0, dot)
            val fraction = number.substring(dot + 1)
            if (fraction.length <= places || !fraction.all { it in '0'..'9' }) return text
            val kept = fraction.substring(0, places)
            val dropped = fraction.substring(places)
            if (dropped.all { it == '0' }) return "$whole.$kept$unit"
            val carriedFraction = increment(kept) ?: return text
            if (!carriedFraction.second) return "$whole.${carriedFraction.first}$unit"
            val carriedWhole = increment(whole) ?: return text
            val head = if (carriedWhole.second) "1" else ""
            return head + carriedWhole.first + "." + carriedFraction.first + unit
        }

        /** Adds one to a run of decimal digits: the new digits and whether it carried past the front; null on a non-digit. */
        private fun increment(digits: String): Pair<String, Boolean>? {
            val chars = digits.toCharArray()
            var carry = true
            var index = chars.size - 1
            while (carry && index >= 0) {
                val c = chars[index]
                if (c !in '0'..'9') return null
                if (c == '9') chars[index] = '0' else { chars[index] = c + 1; carry = false }
                index--
            }
            return String(chars) to carry
        }
    }
}
