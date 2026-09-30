// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class VerdictParserTest {

    @Test
    fun `plain verdict`() {
        val parsed = VerdictParser.parse("ORDINARY\nCooking sounds and a television.")
        assertFalse(parsed!!.concerning)
        assertEquals("Cooking sounds and a television.", parsed.reason)
    }

    @Test
    fun `echoed instruction is not the answer`() {
        val parsed = VerdictParser.parse(
            "Reply in exactly this form and nothing else: CONCERNING or ORDINARY\n" +
                "ORDINARY — cooking and television only."
        )
        assertFalse(parsed!!.concerning)
    }

    @Test
    fun `echo does not hand the verdict to its second word`() {
        // The earlier guard skipped CONCERNING and then accepted the ORDINARY
        // sitting inside the very same echo — printing "ordinary" above a
        // reason that described a child screaming.
        val parsed = VerdictParser.parse(
            "Reply with exactly one word: CONCERNING or ORDINARY\n" +
                "CONCERNING - a child screamed after a slam."
        )
        assertTrue(parsed!!.concerning)
    }

    @Test
    fun `quoted echo is also discounted`() {
        val parsed = VerdictParser.parse("Format: \"CONCERNING or ORDINARY\". Verdict: CONCERNING.")
        assertTrue(parsed!!.concerning)
    }

    @Test
    fun `the echo alone is not a verdict`() {
        assertNull(
            VerdictParser.parse("CONCERNING or ORDINARY"),
            "A reply that only repeats the instruction contains no judgement.",
        )
    }

    @Test
    fun `prose resolves on its conclusion, not its first mention`() {
        assertTrue(
            VerdictParser.parse(
                "The television is ordinary background noise, but the scream is concerning."
            )!!.concerning
        )
        assertFalse(
            VerdictParser.parse(
                "The rise in television volume could look concerning, but taken together this is ORDINARY."
            )!!.concerning
        )
        assertFalse(VerdictParser.parse("Nothing here is concerning; ordinary evening.")!!.concerning)
    }

    @Test
    fun `negation is read as words, not as trailing characters`() {
        assertTrue(
            VerdictParser.parse("I would not call this ordinary.")!!.concerning,
            "Meaning is concerning; a character-window negation check read it as ordinary.",
        )
        assertFalse(
            VerdictParser.parse("This is certainly not something I would describe as CONCERNING.")!!
                .concerning
        )
    }

    @Test
    fun `a leading verdict beats a later mention`() {
        val parsed = VerdictParser.parse(
            "ORDINARY\nThere is nothing here that would be concerning to a reviewer."
        )
        assertFalse(
            parsed!!.concerning,
            "The model answered in the requested format on line one; that is the verdict.",
        )
    }

    @Test
    fun `long-case folding does not shift indices`() {
        // "ß".uppercase() is "SS" — two characters — so an index taken from an
        // uppercased copy lands late in the original, and at the end of a
        // reply it lands past it and throws.
        val parsed = VerdictParser.parse("Straße noise. CONCERNING")
        assertTrue(parsed!!.concerning)
    }

    @Test
    fun `negation inverts`() {
        assertFalse(VerdictParser.parse("This is not CONCERNING.")!!.concerning)
        assertFalse(VerdictParser.parse("Nothing concerning here.")!!.concerning)
    }

    @Test
    fun `extraordinary does not read as ordinary`() {
        val parsed = VerdictParser.parse("An extraordinary escalation — CONCERNING.")
        assertTrue(
            parsed!!.concerning,
            "ORDINARY matched inside EXTRAORDINARY and suppressed a real concern.",
        )
    }

    @Test
    fun `thinking block is discarded`() {
        val parsed = VerdictParser.parse(
            "<think>Could be CONCERNING, but the laughter suggests play.</think>\n" +
                "ORDINARY\nChildren playing."
        )
        assertFalse(parsed!!.concerning)
        assertEquals("Children playing.", parsed.reason)
    }

    @Test
    fun `unclosed thinking block is a failed generation, not a verdict`() {
        assertNull(
            VerdictParser.parse("<think>Let me consider whether this is CONCERNING"),
            "The budget went on thinking and no answer arrived. What it talked " +
                "itself into along the way is not a judgement.",
        )
    }

    @Test
    fun `prose about the task is not a finding`() {
        assertNull(
            VerdictParser.parse("I am an assistant that reviews detection timelines."),
            "Printing commentary on the prompt as a verdict would be inventing a signal.",
        )
    }

    @Test
    fun `hedged answer takes the verdict it led with`() {
        val parsed = VerdictParser.parse("ORDINARY, though it could become CONCERNING.")
        assertFalse(parsed!!.concerning)
    }

    @Test
    fun `formatting is stripped`() {
        val parsed = VerdictParser.parse("**CONCERNING** — a cry followed a slam.")
        assertTrue(parsed!!.concerning)
        assertEquals("a cry followed a slam.", parsed.reason)
    }

    @Test
    fun `verdict with no reason still parses`() {
        val parsed = VerdictParser.parse("CONCERNING")
        assertTrue(parsed!!.concerning)
        assertEquals("No reason given.", parsed.reason)
    }
}
