// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

/**
 * One phrase match against the transcript. Recorded even when [negated] is
 * true, so a reviewer can see why a phrase that looks alarming in isolation
 * ("I will not hurt you") did not move the score.
 */
data class ThreatHit(val category: ThreatCategory, val phrase: String, val negated: Boolean)


data class ThreatAssessment(
    val score: Float,
    val hits: List<ThreatHit>,
    val topCategory: ThreatCategory?,
    val lexiconVersion: String,
) {
    val isEmpty: Boolean get() = hits.isEmpty()

    companion object {
        fun empty(lexiconVersion: String) = ThreatAssessment(0f, emptyList(), null, lexiconVersion)
    }
}


class ThreatScorer(private val lexicon: ThreatLexicon = ThreatLexicon) {

    private data class PhraseMatcher(val category: ThreatCategory, val phrase: String, val regex: Regex)

    // Built once here, not inside score(): compiling ~a hundred regexes per
    // transcript would dominate the cost of what is otherwise a linear scan.
    private val matchers: List<PhraseMatcher> = lexicon.terms.flatMap { (category, phrases) ->
        phrases.map { phrase -> PhraseMatcher(category, phrase, wordBoundaryRegex(phrase)) }
    }

    private val negationRegexes: List<Regex> = lexicon.negations.map(::wordBoundaryRegex)

    fun score(text: String): ThreatAssessment {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ThreatAssessment.empty(lexicon.VERSION)

        val hits = mutableListOf<ThreatHit>()
        for (matcher in matchers) {
            for (match in matcher.regex.findAll(normalized)) {
                hits += ThreatHit(matcher.category, matcher.phrase, isNegated(normalized, match.range.first))
            }
        }
        if (hits.isEmpty()) return ThreatAssessment.empty(lexicon.VERSION)

        val active = hits.filterNot { it.negated }
        if (active.isEmpty()) return ThreatAssessment(0f, hits, null, lexicon.VERSION)

        val topHit = active.maxBy { it.category.weight }
        val distinctCategories = active.mapTo(HashSet()) { it.category }
        // The strongest single hit sets the score; each further distinct
        // category only nudges it up. One unambiguous threat should outrank
        // an ordinary argument that happens to brush several categories, and
        // summing weights would let volume of speech substitute for severity.
        val score = (topHit.category.weight + 0.1f * (distinctCategories.size - 1)).coerceIn(0f, 1f)

        return ThreatAssessment(score, hits, topHit.category, lexicon.VERSION)
    }

    // A negation only counts if it sits in the 3 tokens right before the
    // match: "he said don't you dare hit me" should still score, but
    // "I will not hit you" should not.
    private fun isNegated(text: String, matchStart: Int): Boolean {
        val precedingWindow = text.substring(0, matchStart)
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
            .takeLast(3)
            .joinToString(" ")
        if (precedingWindow.isEmpty()) return false
        return negationRegexes.any { it.containsMatchIn(precedingWindow) }
    }

    private fun normalize(text: String): String =
        WHITESPACE.replace(text.trim().lowercase(), " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")

        fun wordBoundaryRegex(phrase: String) = Regex("\\b${Regex.escape(phrase)}\\b")
    }
}
