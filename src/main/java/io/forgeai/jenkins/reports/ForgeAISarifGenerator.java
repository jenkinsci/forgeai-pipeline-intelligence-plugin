package io.forgeai.jenkins.reports;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Writes findings as SARIF 2.1.0, the standard static-analysis interchange format, so they can be shown
 * by tools such as the Warnings NG plugin or GitHub code scanning.
 *
 * <p>Only findings with a file location are included: SARIF consumers anchor results to source, and
 * GitHub code scanning rejects a whole upload when a result has no location. Findings without a file
 * remain in the HTML report.
 */
public final class ForgeAISarifGenerator {

    public static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String INFO_URI = "https://plugins.jenkins.io/forgeai-pipeline-intelligence/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ForgeAISarifGenerator() {}

    public static String generate(List<AnalysisResult> results) {
        JsonArray rules = new JsonArray();
        JsonArray sarifResults = new JsonArray();

        for (AnalysisResult r : results) {
            String ruleId = "forgeai/" + r.getAnalyzerId();
            JsonObject rule = new JsonObject();
            rule.addProperty("id", ruleId);
            rule.addProperty("name", r.getAnalyzerName());
            rule.add("shortDescription", text(r.getAnalyzerName()));
            rule.addProperty("helpUri", INFO_URI);
            rules.add(rule);

            for (AnalysisResult.Finding f : r.getFindings()) {
                String uri = locationUri(r.getAnalyzerId(), f.getFile());
                if (uri == null) continue;

                JsonObject artifact = new JsonObject();
                artifact.addProperty("uri", uri);
                artifact.addProperty("uriBaseId", "%SRCROOT%");
                JsonObject physical = new JsonObject();
                physical.add("artifactLocation", artifact);
                if (f.getLine() > 0) {
                    JsonObject region = new JsonObject();
                    region.addProperty("startLine", f.getLine());
                    physical.add("region", region);
                }
                JsonObject location = new JsonObject();
                location.add("physicalLocation", physical);
                JsonArray locations = new JsonArray();
                locations.add(location);

                JsonObject properties = new JsonObject();
                properties.addProperty("severity", f.getSeverity());
                properties.addProperty("analyzer", r.getAnalyzerId());

                JsonObject result = new JsonObject();
                result.addProperty("ruleId", ruleId);
                result.addProperty("level", level(f.getSeverity()));
                result.add("message", text(message(f)));
                result.add("locations", locations);
                result.add("properties", properties);
                sarifResults.add(result);
            }
        }

        JsonObject driver = new JsonObject();
        driver.addProperty("name", "ForgeAI");
        driver.addProperty("fullName", "ForgeAI Pipeline Intelligence");
        driver.addProperty("informationUri", INFO_URI);
        driver.add("rules", rules);
        JsonObject tool = new JsonObject();
        tool.add("driver", driver);
        JsonObject run = new JsonObject();
        run.add("tool", tool);
        run.add("results", sarifResults);
        JsonArray runs = new JsonArray();
        runs.add(run);

        JsonObject sarif = new JsonObject();
        sarif.addProperty("$schema", SCHEMA);
        sarif.addProperty("version", "2.1.0");
        sarif.add("runs", runs);
        return GSON.toJson(sarif);
    }

    /** Number of findings {@link #generate} will include, i.e. those with a usable file location. */
    public static long locatedFindings(List<AnalysisResult> results) {
        return results.stream()
                .mapToLong(r -> r.getFindings().stream()
                        .filter(f -> locationUri(r.getAnalyzerId(), f.getFile()) != null).count())
                .sum();
    }

    static String level(String severity) {
        if (severity == null) return "warning";
        return switch (severity) {
            case "CRITICAL", "HIGH" -> "error";
            case "LOW", "INFO" -> "note";
            default -> "warning";
        };
    }

    /**
     * Turns the file the model reported into a relative URI, or {@code null} when there is none or it
     * points outside the workspace. Pipeline advisor findings are about the Jenkinsfile.
     */
    static String locationUri(String analyzerId, String file) {
        String path = file == null ? "" : file.strip().replace('\\', '/');
        if (path.isEmpty() && "pipeline-advisor".equals(analyzerId)) {
            path = "Jenkinsfile";
        }
        while (path.startsWith("./")) path = path.substring(2);
        if (path.isEmpty() || path.startsWith("/") || path.contains(":") || List.of(path.split("/")).contains("..")) {
            return null; // absolute paths, drive letters, URLs and parent traversal are not workspace files
        }
        try {
            return new URI(null, null, path, null).getRawPath();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String message(AnalysisResult.Finding f) {
        StringBuilder sb = new StringBuilder(f.getTitle() == null ? "Finding" : f.getTitle());
        if (f.getDescription() != null && !f.getDescription().isBlank()) {
            sb.append(": ").append(f.getDescription());
        }
        if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
            sb.append("\nSuggested fix: ").append(f.getSuggestion());
        }
        return sb.toString();
    }

    private static JsonObject text(String value) {
        JsonObject o = new JsonObject();
        o.addProperty("text", value);
        return o;
    }
}
