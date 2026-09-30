// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect


enum class CueGroup(val displayName: String, val labels: List<String>) {
    SCREAM("Scream", listOf("Screaming")),
    SHOUT("Shout", listOf("Shout", "Yell", "Bellow", "Children shouting", "Whoop")),
    CRY("Crying", listOf("Crying, sobbing", "Baby cry, infant cry", "Whimper", "Wail, moan")),
    GLASS("Glass", listOf("Glass", "Shatter", "Chink, clink")),
    /**
     * "Explosion" is deliberately absent.
     *
     * YAMNet applies it to almost any loud broadband transient, and in a home
     * essentially every one of them is a door, a dropped pan or a bang. Moving
     * it out of the gunshot group to here, at weight 0.70, reduced how often it
     * alerted but did not stop it: a raised voice alongside a slam multiplies
     * the cue by the strain boost and it clears the bar again.
     *
     * It is dropped rather than reweighted because it carries no information
     * the rest of this group does not. A genuine impact loud enough to matter
     * is also a thump, a slam, a bang or a crash, and those are here. What it
     * contributed was a label on an alert that said "Explosion" about a closing
     * door — which is not a description of anything that happened.
     *
     * If real impacts stop being detected, Diagnostics > Where cues die will
     * show Impact scoring and falling short, which is the evidence needed to
     * reconsider this.
     */
    IMPACT("Impact", listOf("Thump, thud", "Slam", "Bang", "Knock", "Smash, crash")),


    GUNSHOT("Gunshot", listOf("Gunshot, gunfire"));

    companion object {

        val SPEECH_LABELS = listOf(
            "Speech",
            "Male speech, man speaking",
            "Female speech, woman speaking",
            "Child speech, kid speaking",
            "Conversation",
            "Narration, monologue",
        )


        val MEDIA_LABELS = listOf(
            "Television",
            "Radio",
            "Music",
            "Musical instrument",
            "Singing",
            "Theme music",
            "Soundtrack music",
        )


        val LAUGHTER_LABELS = listOf(
            "Laughter",
            "Giggle",
            "Chuckle, chortle",
            "Snicker",
            "Belly laugh",
            "Baby laughter",
        )

        /** Every label the decision layer reads, for the class-map cross-check. */
        val ALL_REFERENCED: List<String> =
            entries.flatMap { it.labels } + SPEECH_LABELS + MEDIA_LABELS + LAUGHTER_LABELS
    }
}
