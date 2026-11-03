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
    public static final int DEFAULT_INPUT_TOKENS = 0; // automatic model context window
    public static final int DEFAULT_REQUEST_MIB = 0; // provider policy
    public static final int DEFAULT_OUTPUT_RESERVE = 65_536;
    static final String ROUND_TRIPS_KEY = "maxToolCallingRoundTrips";
    static final String INPUT_TOKENS_KEY = "maxInputTokens";
    static final String REQUEST_MIB_KEY = "maxRequestMiB";
    static final String OUTPUT_RESERVE_KEY = "outputReserveTokens";

    private AssistantExecutionSettings() {}

    public static int inputTokens(PrefService prefs) {
        int value = prefs == null ? DEFAULT_INPUT_TOKENS : prefs.getInt(
            AssistantExecutionSettings.class, INPUT_TOKENS_KEY, DEFAULT_INPUT_TOKENS);
        return value >= 0 ? value : DEFAULT_INPUT_TOKENS;
    }

    public static int requestMiB(PrefService prefs) {
        int value = prefs == null ? DEFAULT_REQUEST_MIB : prefs.getInt(
            AssistantExecutionSettings.class, REQUEST_MIB_KEY, DEFAULT_REQUEST_MIB);
        return value >= 0 && value <= 128 ? value : DEFAULT_REQUEST_MIB;
    }

    public static int outputReserve(PrefService prefs) {
        int value = prefs == null ? DEFAULT_OUTPUT_RESERVE : prefs.getInt(
            AssistantExecutionSettings.class, OUTPUT_RESERVE_KEY, DEFAULT_OUTPUT_RESERVE);
        return value > 0 ? value : DEFAULT_OUTPUT_RESERVE;
    }

    public static void save(PrefService prefs, int rounds, int tokens, int mib) {
        if (rounds < 1 || tokens < 0 || mib < 0 || mib > 128)
            throw new IllegalArgumentException("Use positive rounds, nonnegative context tokens and 0-128 MiB.");
        save(prefs, rounds);
        prefs.put(AssistantExecutionSettings.class, INPUT_TOKENS_KEY, tokens);
        prefs.put(AssistantExecutionSettings.class, REQUEST_MIB_KEY, mib);
    }

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
