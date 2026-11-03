/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

/** Last-request telemetry only: never retains bodies, headers or credentials. */
final class ContextMonitor {
    private static String label = "No request yet", archive = "";
    private static long tokens, inputLimit, bytes, byteLimit;
    private static Integer actualInput;
    private static java.util.function.Supplier<String> activity = () -> "No monitored stream yet";
    static synchronized void watch(java.util.function.Supplier<String> state) { activity = state; }
    static synchronized void preflight(String model, long estimate, long limit, long maximumBytes, String directory) {
        label = model; tokens = estimate; inputLimit = limit; byteLimit = maximumBytes; archive = directory;
    }
    static synchronized void body(long count, long maximum) { bytes = count; byteLimit = maximum; }
    static synchronized void usage(Integer input) { actualInput = input; }
    static synchronized String status() {
        return "Most recently active assistant: " + label + "\n\n" + activity.get() + "\n\n" +
            "Estimated input tokens: " + tokens + " / " + inputLimit + "\n" +
            "Automatic token compaction trigger: " + (inputLimit * 4 / 5) + "\n" +
            "Last serialized request body: " + bytes + " / " + byteLimit + " bytes\n" +
            "Last completed API response input tokens: " + (actualInput == null ? "not reported" : actualInput) + "\n\n" +
            "Token estimates are approximate. Body bytes are measured from the actual UTF-8 JSON.\n" +
            "API usage describes the last completed request, not a future request.\n\n" +
            "Full local record: " + archive;
    }
}
