// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.classify.ScoredClass
import org.lsrith.acousticmonitor.monitor.RoomReading
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue


class DemotionInvariantTest {

    private fun frame(scores: Map<String, Float>) = FrameResult(
        endSampleIndex = 0L,
        top = scores.map { ScoredClass(it.key, it.value) },
        scores = scores,
        inferenceMs = 0.0,
        captureToResultMs = 0.0,
    )

    /** A fully learned room, at the deviation given. */
    private fun room(deviationScore: Float) = RoomReading(
        currentDbfs = -20f,
        baselineDbfs = -50f,
        deviationDb = 30f,
        deviationScore = deviationScore,
        confidence = 1f,
        ageSeconds = 600.0,
    )

    /** Media and laughter both saturated — the most demotion the rules allow. */
    private val maximalDemotion = mapOf(
        "Television" to 1f,
        "Music" to 1f,
        "Laughter" to 1f,
    )

    @Test
    fun `a maximal loudness spike still alerts under maximal demotion`() {
        val engine = DecisionEngine()
        val (verdict, _) = engine.accept(
            frame = frame(maximalDemotion),
            room = room(deviationScore = 1f),
            threatScore = 0f,
            vocalStress = 0f,
            nowEpochMs = 0L,
        )

        assertNotEquals(
            Severity.NONE,
            verdict.severity,
            "A television at full volume made the loudness path unreachable. " +
                "Context adjustment must not make detection unreachable: " +
                "raising the volume to mask an incident is documented behaviour.",
        )
    }

    @Test
    fun `the demoted bar never rises above what the loudness path can reach`() {
        val cfg = DecisionConfig()
        val engine = DecisionEngine(cfg)
        val (verdict, _) = engine.accept(
            frame = frame(maximalDemotion),
            room = room(deviationScore = 1f),
            threatScore = 0f,
            vocalStress = 0f,
            nowEpochMs = 0L,
        )

        // The ceiling is structural, not a tuning choice: ambientPath is
        // weightedDeviation * ambientWeight, and weightedDeviation is capped
        // at 1. No room, however loud, produces more than this.
        val loudnessCeiling = cfg.ambientWeight
        assertTrue(
            verdict.effectiveMediumThreshold < loudnessCeiling,
            "Medium bar ${verdict.effectiveMediumThreshold} is at or above the " +
                "loudness path's structural ceiling $loudnessCeiling, so no " +
                "loudness-only event can ever cross it.",
        )
    }

    @Test
    fun `demotion still raises the bar`() {
        val engine = DecisionEngine()
        val quiet = engine.accept(
            frame = frame(emptyMap()),
            room = room(deviationScore = 0.5f),
            threatScore = 0f,
            vocalStress = 0f,
            nowEpochMs = 0L,
        ).first

        val televised = DecisionEngine().accept(
            frame = frame(maximalDemotion),
            room = room(deviationScore = 0.5f),
            threatScore = 0f,
            vocalStress = 0f,
            nowEpochMs = 0L,
        ).first

        // The fix must not have quietly turned demotion off — that would trade
        // one failure for the commonest false positive in a home.
        assertTrue(
            televised.effectiveMediumThreshold > quiet.effectiveMediumThreshold,
            "Media no longer demotes at all.",
        )
    }

    @Test
    fun `language faces an undemoted bar`() {
        val engine = DecisionEngine()
        val cfg = DecisionConfig()
        // Just over the medium bar on the language path alone.
        val threat = (cfg.mediumThreshold / cfg.threatWeight) + 0.01f

        val (verdict, _) = engine.accept(
            frame = frame(maximalDemotion),
            room = room(deviationScore = 0f),
            threatScore = threat.coerceAtMost(1f),
            vocalStress = 0f,
            nowEpochMs = 0L,
        )

        assertNotEquals(
            Severity.NONE,
            verdict.severity,
            "A threat spoken over a television is not less of a threat, and " +
                "demoting language would reward masking.",
        )
    }
}
