/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Shortens the long lists of a very large tool result before it is sent to the model.
 *
 * A tool that lists every ROI or every table row of a few thousand objects returns
 * hundreds of thousands of characters, and the chat memory sends them again with every
 * later request. Only JSON lists are cut, to their first rows, and each cut list says how
 * many rows it had. Text is never cut: a script or a log must arrive whole. Results below
 * the limit are not looked at. The full result stays in the local archive.
 */
final class LongLists {
    /** A result this long or shorter is sent as it is. */
    static final int LIMIT = 30_000;
    /** Rows kept at the start of a list that is cut. */
    static final int KEEP = 20;

    private LongLists() {}

    /**
     * The result with its longest lists cut until it fits the limit, or null when it is
     * sent unchanged: short enough, not JSON, or without a list that can be cut.
     */
    static String shorten(String text, String fullResult) {
        if (text == null || text.length() <= LIMIT) return null;
        JsonElement root;
        try { root = JsonParser.parseString(text); }
        catch (RuntimeException notJson) { return null; }
        if (!root.isJsonObject() && !root.isJsonArray()) return null;
        boolean cut = false;
        for (int round = 0; round < 64 && root.toString().length() > LIMIT; round++) {
            JsonArray longest = longest(root);
            if (longest == null) break;
            int total = longest.size();
            var kept = new JsonArray();
            for (int i = 0; i < KEEP; i++) kept.add(longest.get(i));
            while (longest.size() > 0) longest.remove(longest.size() - 1);
            longest.addAll(kept);
            var mark = new JsonObject();
            mark.addProperty("rows_omitted_here", total - KEEP);
            mark.addProperty("rows_in_total", total);
            longest.add(mark);
            cut = true;
        }
        if (!cut) return null;
        String note = "Lists in this result were cut to their first " + KEEP + " rows because the whole result has " +
            text.length() + " characters; each cut list ends with rows_omitted_here and rows_in_total. Every other " +
            "field is complete. The rows shown are not all rows: do not total them or conclude from them. " +
            "To use the omitted rows, compute what you need with a script or read a narrower range." +
            (fullResult == null || fullResult.isBlank() ? "" : " Full result: " + fullResult);
        if (root.isJsonObject()) root.getAsJsonObject().addProperty("result_shortened", note);
        else {
            var mark = new JsonObject();
            mark.addProperty("result_shortened", note);
            root.getAsJsonArray().add(mark);
        }
        return root.toString();
    }

    /** The list with more rows than a cut list keeps that takes the most characters. */
    private static JsonArray longest(JsonElement root) {
        var best = new JsonArray[1];
        var bestLength = new int[1];
        find(root, best, bestLength);
        return best[0];
    }

    private static void find(JsonElement element, JsonArray[] best, int[] bestLength) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.size() > KEEP + 1) {
                int length = array.toString().length();   // once per list
                if (length > bestLength[0]) { best[0] = array; bestLength[0] = length; }
            }
            for (JsonElement child : array) find(child, best, bestLength);
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) find(entry.getValue(), best, bestLength);
        }
    }
}
