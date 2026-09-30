// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

/**
 * A category of threat-language cue, with the weight it contributes to
 * [ThreatAssessment.score].
 *
 * Weights are a relative ranking, not a calibrated probability: a direct
 * threat of violence should dominate the score even when other categories
 * also fire, which is why [ThreatScorer] takes a maximum rather than a sum.
 */
enum class ThreatCategory(val displayName: String, val weight: Float) {
    PHYSICAL_THREAT("Physical threat", 1.0f),
    INTIMIDATION("Intimidation", 0.8f),
    DEGRADATION("Degradation", 0.6f),
    COERCION("Coercion and control", 0.7f),
    HELP_SEEKING("Calls for help", 0.9f),
}


object ThreatLexicon {
    /** Version this whenever [terms] or [negations] change; a score is only
     *  comparable to another score produced by the same version. */
    const val VERSION = "2026-08-07.1"

    val terms: Map<ThreatCategory, List<String>> = mapOf(
        ThreatCategory.PHYSICAL_THREAT to listOf(
            "i will hit you",
            "i will beat you",
            "i will slap you",
            "i will punch you",
            "i will hurt you",
            "i will kill you",
            "i'll hit you",
            "i'll beat you",
            "i'll slap you",
            "i'll hurt you",
            "i'll kill you",
            "i'm going to hit you",
            "i'm going to beat you",
            "i'm going to hurt you",
            "hit you again",
            "beat you again",
            "you want to get hit",
            "you want a beating",
            "come here and take it",
        ),
        ThreatCategory.INTIMIDATION to listOf(
            "if you tell anyone",
            "if you say anything",
            "if you tell your teacher",
            "you'll regret it",
            "you will regret it",
            "wait until i get you",
            "just wait until",
            "you'll see what happens",
            "you don't want to know what happens",
            "keep pushing me",
            "don't test me",
            "don't make me",
            "you know what happens when",
            "last warning",
            "i'm warning you",
            "watch yourself",
            "you better watch it",
        ),
        ThreatCategory.DEGRADATION to listOf(
            "you're worthless",
            "you are worthless",
            "you're useless",
            "you are useless",
            "you're stupid",
            "you're an idiot",
            "you're a disgrace",
            "nobody wants you",
            "no one wants you",
            "you're a mistake",
            "you ruin everything",
            "you're pathetic",
            "you disgust me",
            "i wish you were never born",
            "wish you were never born",
            "you're a burden",
            "you're nothing",
        ),
        ThreatCategory.COERCION to listOf(
            "don't tell anyone",
            "don't you dare tell",
            "this stays between us",
            "our little secret",
            "you can't leave",
            "you're not allowed to leave",
            "you're not allowed out",
            "give me your phone",
            "who were you talking to",
            "who gave you permission",
            "you do as i say",
            "you'll do what i tell you",
            "no one will believe you",
            "nobody will believe you",
            "stay in your room",
            "you're not going anywhere",
        ),
        ThreatCategory.HELP_SEEKING to listOf(
            "help me",
            "please stop",
            "stop it please",
            "please don't",
            "i'm scared",
            "i am scared",
            "i'm afraid",
            "i am afraid",
            "someone help",
            "call the police",
            "i want to go home",
            "please don't hurt me",
            "get off me",
            "leave me alone",
            "it hurts",
            "you're hurting me",
            "please no more",
        ),
    )

    /** Tokens that commonly invert the meaning of whatever follows them. */
    val negations: List<String> = listOf(
        "don't",
        "dont",
        "not",
        "never",
        "wouldn't",
        "wouldnt",
        "no one",
        "nobody",
    )
}
