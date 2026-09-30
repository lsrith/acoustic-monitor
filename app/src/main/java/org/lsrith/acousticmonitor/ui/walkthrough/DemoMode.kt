// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

/**
 * Which face the app shows.
 *
 * The same engine, the same microphone stream and the same models sit under
 * both. Only the navigation and the framing differ: [PRODUCT] fuses every
 * signal into one verdict, the way a shipped app would; [WALKTHROUGH] gives
 * each signal its own page so it can be provoked and discussed on its own.
 */
enum class DemoMode {
    /** The end-product view: Live, Diagnostics, Alerts, Setup, Models. */
    PRODUCT,


    WALKTHROUGH;

    companion object {
        fun fromName(name: String?): DemoMode =
            entries.firstOrNull { it.name == name } ?: PRODUCT
    }
}
