package io.forgeai.jenkins.analyzers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

/**
 * Parses the JSON response from any analyzer's LLM call into a structured {@link AnalysisResult}.
 * Handles malformed JSON gracefully — if the LLM returns markdown fences or extra text,
 * the parser strips them before attempting deserialization.
 */
public final class ResultParser {

    private static final Gson GSON = new Gson();

    private ResultParser() {}

    public static AnalysisResult parse(String raw, String analyzerId, String analyzerName,
                                       PrintStream logger) {
        AnalysisResult result = new AnalysisResult(analyzerId, analyzerName);

        try {
            // Strip markdown fences if the LLM wrapped its response
            String cleaned = raw.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("```(json)?\\s*", "");
                if (cleaned.endsWith("```")) {
                    cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
                }
            }

            // Find the first '{' and last '}' to extract JSON
            int start = cleaned.indexOf('{');
            int end   = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }

            JsonObject json = GSON.fromJson(cleaned, JsonObject.class);

            result.setScore(getIntOr(json, "score", 5));
            result.setSeverity(getStringOr(json, "severity", "MEDIUM"));
            result.setSummary(getStringOr(json, "summary", "Analysis complete."));
            result.setRawMarkdown(raw);

            if (json.has("findings") && json.get("findings").isJsonArray()) {
                JsonArray findings = json.getAsJsonArray("findings");
                for (JsonElement el : findings) {
                    JsonObject f = el.getAsJsonObject();
                    AnalysisResult.Finding finding = new AnalysisResult.Finding(
                            getStringOr(f, "title", "Untitled"),
                            getStringOr(f, "severity", "MEDIUM"),
                            getStringOr(f, "description", ""));
                    finding.setFile(getStringOr(f, "file", ""));
                    finding.setLine(getIntOr(f, "line", 0));
                    finding.setSuggestion(getStringOr(f, "suggestion", ""));
                    result.addFinding(finding);
                }
            }

        } catch (Exception e) {
            logger.println("[ForgeAI/" + analyzerId + "] WARNING: Could not parse LLM response as JSON. "
                    + "Storing raw response. Error: " + e.getMessage());
            result.setScore(5);
            result.setSeverity("MEDIUM");
            result.setSummary("Analysis complete (raw output — JSON parsing failed).");
            result.setRawMarkdown(raw);
        }

        return result;
    }

    private static String getStringOr(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static int getIntOr(JsonObject obj, String key, int fallback) {
        try {
            return obj.has(key) ? obj.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
