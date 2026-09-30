// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.llm

/** A parsed model reply. */
data class Judgement(val concerning: Boolean, val reason: String)

/**
 * Pulls a verdict out of whatever the model actually returned.
 *
 * Deliberately separate from [LlmEngine] and free of Android types, because
 * this is the part that gets a judgement wrong quietly — no exception, no log
 * line, just the opposite answer on screen.
 *
 * Four rules, each of which exists because the simpler version failed:
 *
 * 1. **The echoed instruction is deleted, not skipped.** The prompt ends with
 *    the literal line `CONCERNING or ORDINARY`, and a small model parrots it.
 *    Skipping the first word of that pair merely handed the verdict to the
 *    second word of the same echo.
 * 2. **A line that begins with a verdict word wins.** That is the format the
 *    model was asked for, and honouring it beats guessing from prose.
 * 3. **Otherwise the last verdict word wins, not the first.** Prose puts its
 *    conclusion at the end: "the television is ordinary background noise, but
 *    the scream is concerning" resolves the way a reader would resolve it.
 * 4. **No verdict is a failure, not a finding.** A reply containing only the
 *    echoed instruction yields nothing, because inventing a signal is the one
 *    thing this system must never do.
 */
object VerdictParser {

    private const val CONCERNING = "CONCERNING"
    private const val ORDINARY = "ORDINARY"

    /**
     * Words that flip the verdict that follows them.
     *
     * Matched as whole words within a short lookback rather than as a suffix
     * of the preceding characters, which read "nothing here is concerning" as
     * concerning — the lookback ended in "IS ".
     */
    private val NEGATIONS = setOf(
        "NOT", "NO", "NOR", "NEVER", "NOTHING", "NONE", "HARDLY", "BARELY",
        "ISN'T", "WASN'T", "AREN'T", "DOESN'T", "DON'T", "CAN'T", "WOULDN'T",
    )

    /** How many preceding words a negation may sit in. */
    private const val NEGATION_LOOKBACK_WORDS = 6

    fun parse(raw: String): Judgement? {
        val stripped = stripThinking(raw) ?: return null

        // Delete the echoed instruction outright. Anything inside it is the
        // prompt coming back, not an answer, and leaving any of it in place
        // hands the verdict to whichever half survived the filter.
        val text = ECHO.replace(stripped) { match -> " ".repeat(match.value.length) }

        val upper = foldCase(text)
        val hits = (occurrences(upper, CONCERNING).map { it to true } +
            occurrences(upper, ORDINARY).map { it to false })
            .sortedBy { it.first }
        if (hits.isEmpty()) return null

        // The requested format first: a verdict alone at the start of a line.
        val leading = hits.firstOrNull { (at, _) -> startsLine(upper, at) }
        val (at, stated) = leading ?: hits.last()

        val concerning = if (isNegated(upper, at)) !stated else stated
        val word = if (stated) CONCERNING else ORDINARY

        val reason = text.substring(at + word.length)
            .trimStart(' ', '\t', '\n', '\r', '.', ',', ':', ';', '-', '—', '*', '"', ')')
            .lineSequence()
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            // A leading verdict leaves the whole reply as its reason; a
            // trailing one leaves nothing after it, so fall back to the text
            // before it rather than printing "no reason" over a real sentence.
            .ifBlank {
                text.substring(0, at).lineSequence().filter { it.isNotBlank() }
                    .joinToString(" ").trim()
            }

        return Judgement(concerning, reason.ifBlank { "No reason given." })
    }

    private val ECHO = Regex(
        """\b(CONCERNING\s+OR\s+ORDINARY|ORDINARY\s+OR\s+CONCERNING)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Drops a reasoning model's thinking block.
     *
     * An unclosed block means the whole token budget went on thinking and the
     * answer never arrived — there is nothing to salvage, and whatever the
     * model talked itself into along the way is not a verdict.
     */
    private fun stripThinking(raw: String): String? {
        val closed = raw.indexOf("</think>")
        return when {
            closed >= 0 -> raw.substring(closed + "</think>".length).trim()
            raw.contains("<think>") -> null
            else -> raw.trim()
        }
    }

    /**
     * Upper-cases character by character, which preserves length.
     *
     * `String.uppercase` does not: "ß" becomes "SS", and every index taken
     * from the folded copy then lands one character late in the original —
     * far enough, at the end of a reply, to throw out of bounds.
     */
    private fun foldCase(text: String): String =
        buildString(text.length) { text.forEach { append(it.uppercaseChar()) } }

    /** Whole-word occurrences: ORDINARY must not match inside EXTRAORDINARY. */
    private fun occurrences(haystack: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            val end = at + needle.length
            val beforeOk = at == 0 || !haystack[at - 1].isLetter()
            val afterOk = end >= haystack.length || !haystack[end].isLetter()
            if (beforeOk && afterOk) found += at
            from = end
        }
    }

    /** True when only bullets, quotes or whitespace precede it on its line. */
    private fun startsLine(upper: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0) {
            val c = upper[i]
            if (c == '\n') return true
            if (c !in " \t*_\"'#>-") return false
            i--
        }
        return true
    }

    /**
     * Whether a negation governs the verdict at [at].
     *
     * Scoped to the clause it sits in. Left to run over the whole reply, the
     * "nothing" in the prompt's own "nothing else" negated a verdict on the
     * following line, and the "Nothing" in "Nothing here is concerning;
     * ordinary evening" reached across the semicolon to invert the word after
     * it — turning two ordinary rooms into alerts.
     */
    private fun isNegated(upper: String, at: Int): Boolean {
        val clauseStart = upper.lastIndexOfAny(CLAUSE_BREAKS, at - 1).let { if (it < 0) 0 else it + 1 }
        return upper.substring(clauseStart, at)
            .split(' ', '\t', ',', '(')
            .filter { it.isNotBlank() }
            .takeLast(NEGATION_LOOKBACK_WORDS)
            .any { it.trim('.', '"', '\'', '*') in NEGATIONS }
    }

    private val CLAUSE_BREAKS = charArrayOf('\n', '\r', '.', ';', ':', '!', '?')
}
