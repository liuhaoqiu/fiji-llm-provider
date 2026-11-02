/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.prefs.PrefService;

/** Stable, version-independent preference namespace for this extension. */
public final class AssistantExecutionSettings {
    public static final int DEFAULT_ROUND_TRIPS = 1000;
    static final String ROUND_TRIPS_KEY = "maxToolCallingRoundTrips";

    private AssistantExecutionSettings() {}

    public static int roundTrips(PrefService prefs) {
        int saved = prefs.getInt(AssistantExecutionSettings.class,
            ROUND_TRIPS_KEY, DEFAULT_ROUND_TRIPS);
        // A damaged or old preference must never disable the finite limit.
        return saved > 0 ? saved : DEFAULT_ROUND_TRIPS;
    }

    public static void save(PrefService prefs, int roundTrips) {
        if (roundTrips < 1) throw new IllegalArgumentException("Tool rounds must be at least 1.");
        prefs.put(AssistantExecutionSettings.class, ROUND_TRIPS_KEY, roundTrips);
    }
}
