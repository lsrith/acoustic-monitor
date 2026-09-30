// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.nlp

import java.io.File
import java.text.Normalizer

/**
 * The result of tokenizing one piece of text for a BERT-family model.
 *
 * All three arrays share [length] — this tokenizer does not pad past the
 * sequence it actually produced, since inference here always runs a single
 * sequence (batch size 1) rather than a batch that would need common-length
 * padding.
 */
data class TokenizedText(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
    val length: Int,
)

/**
 * BERT WordPiece tokenization for `bert-base-uncased`-style vocabularies.
 *
 * Reimplements the reference `BasicTokenizer` + `WordpieceTokenizer` pipeline
 * (lowercase, strip accents, split on whitespace/punctuation, then greedy
 * longest-match-first WordPiece) without pulling in a full tokenizers
 * library for what is, for this vocabulary, a few hundred lines of logic.
 *
 * Not thread-safe to construct concurrently with itself, but [tokenize] holds
 * no mutable state and is safe to call repeatedly from one thread.
 */
class WordPieceTokenizer(vocabFile: File, private val maxLength: Int = 256) {

    private val vocab: Map<String, Int> = buildMap {
        vocabFile.readLines().forEachIndexed { index, line -> put(line, index) }
    }

    private val clsId = vocabId(CLS)
    private val sepId = vocabId(SEP)
    private val unkId = vocabId(UNK)

    private fun vocabId(token: String): Int =
        vocab[token] ?: error("$token missing from vocabulary")

    /** Tokenizes [text] into `[CLS] ... [SEP]`, truncated to [maxLength] tokens total. */
    fun tokenize(text: String): TokenizedText {
        val wordpieceTokens = basicTokenize(text).flatMap { wordpiece(it) }
        val budget = (maxLength - 2).coerceAtLeast(0)
        val truncated = if (wordpieceTokens.size > budget) wordpieceTokens.subList(0, budget) else wordpieceTokens

        val ids = LongArray(truncated.size + 2)
        ids[0] = clsId.toLong()
        for (i in truncated.indices) {
            ids[i + 1] = (vocab[truncated[i]] ?: unkId).toLong()
        }
        ids[truncated.size + 1] = sepId.toLong()

        val length = ids.size
        return TokenizedText(
            inputIds = ids,
            attentionMask = LongArray(length) { 1L },
            tokenTypeIds = LongArray(length) { 0L },
        length = length,
        )
    }

    // ---- basic tokenization: clean, lowercase, strip accents, split ----

    private fun basicTokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        val output = mutableListOf<String>()
        for (rawToken in whitespaceTokenize(cleaned)) {
            val lowered = stripAccents(rawToken.lowercase())
            output += splitOnPunctuation(lowered)
        }
        return output
    }

    /** Drops control characters (keeping the input a plain word/space stream) and normalizes whitespace. */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            if (cp == 0 || cp == 0xFFFD || isControl(ch)) continue
            sb.append(if (isWhitespace(ch)) ' ' else ch)
        }
        return sb.toString()
    }

    private fun whitespaceTokenize(text: String): List<String> =
        text.trim().split(WHITESPACE_RUN).filter { it.isNotEmpty() }

    private fun stripAccents(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(normalized.length)
        for (ch in normalized) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString()
    }

    /** Splits punctuation off into single-character tokens of their own. */
    private fun splitOnPunctuation(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val output = mutableListOf<StringBuilder>()
        var startNewWord = true
        for (ch in token) {
            if (isPunctuation(ch)) {
                output += StringBuilder().append(ch)
                startNewWord = true
            } else {
                if (startNewWord) output += StringBuilder()
                output.last().append(ch)
                startNewWord = false
            }
        }
        return output.map { it.toString() }
    }

    // ---- WordPiece: greedy longest-match-first ----

    private fun wordpiece(token: String): List<String> {
        if (token.length > MAX_INPUT_CHARS_PER_WORD) return listOf(UNK)

        val subTokens = mutableListOf<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var curSubstr: String? = null
            while (start < end) {
                val piece = token.substring(start, end)
                val candidate = if (start > 0) "##$piece" else piece
                if (vocab.containsKey(candidate)) {
                    curSubstr = candidate
                    break
                }
                end -= 1
            }
            if (curSubstr == null) return listOf(UNK)
            subTokens += curSubstr
            start = end
        }
        return subTokens
    }

    private companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"

        // Matches the reference WordpieceTokenizer default: words longer than
        // this are treated as unrecognized rather than exploded into a huge
        // number of subword pieces.
        const val MAX_INPUT_CHARS_PER_WORD = 100

        val WHITESPACE_RUN = Regex("\\s+")

        fun isWhitespace(ch: Char): Boolean {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return true
            return Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()
        }

        fun isControl(ch: Char): Boolean {
            if (ch == '\t' || ch == '\n' || ch == '\r') return false
            return when (Character.getType(ch)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.SURROGATE.toInt(),
                Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt() -> true
                else -> false
            }
        }

        fun isPunctuation(ch: Char): Boolean {
            val cp = ch.code
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) return true
            return when (Character.getType(ch)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }
    }
}
