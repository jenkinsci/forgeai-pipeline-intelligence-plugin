package io.forgeai.jenkins;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.forgeai.jenkins.reports.AnalysisResult;
import io.forgeai.jenkins.reports.ForgeAISarifGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForgeAISarifGeneratorTest {

    private static AnalysisResult.Finding finding(String title, String severity, String file, int line) {
        AnalysisResult.Finding f = new AnalysisResult.Finding(title, severity, "Description of " + title);
        f.setFile(file);
        f.setLine(line);
        f.setSuggestion("Fix " + title);
        return f;
    }

    private static JsonObject run(List<AnalysisResult> results) {
        JsonObject sarif = new Gson().fromJson(ForgeAISarifGenerator.generate(results), JsonObject.class);
        assertEquals("2.1.0", sarif.get("version").getAsString());
        assertEquals(ForgeAISarifGenerator.SCHEMA, sarif.get("$schema").getAsString());
        return sarif.getAsJsonArray("runs").get(0).getAsJsonObject();
    }

    @Test
    void writesOneRulePerAnalyzerAndOneResultPerLocatedFinding() {
        AnalysisResult vuln = new AnalysisResult("vulnerability", "Security Vulnerability Analysis");
        vuln.addFinding(finding("SQL injection", "CRITICAL", "src/payments/app.py", 21));
        vuln.addFinding(finding("Weak hash", "MEDIUM", "src/payments/auth.py", 0));
        AnalysisResult review = new AnalysisResult("code-review", "AI Code Review");
        review.addFinding(finding("Long method", "LOW", "src/payments/app.py", 40));

        JsonObject run = run(List.of(vuln, review));

        JsonObject driver = run.getAsJsonObject("tool").getAsJsonObject("driver");
        assertEquals("ForgeAI", driver.get("name").getAsString());
        JsonArray rules = driver.getAsJsonArray("rules");
        assertEquals(2, rules.size());
        assertEquals("forgeai/vulnerability", rules.get(0).getAsJsonObject().get("id").getAsString());

        JsonArray results = run.getAsJsonArray("results");
        assertEquals(3, results.size());

        JsonObject first = results.get(0).getAsJsonObject();
        assertEquals("forgeai/vulnerability", first.get("ruleId").getAsString());
        assertEquals("error", first.get("level").getAsString());
        String message = first.getAsJsonObject("message").get("text").getAsString();
        assertTrue(message.startsWith("SQL injection: Description of SQL injection"));
        assertTrue(message.contains("Suggested fix: Fix SQL injection"));
        JsonObject physical = first.getAsJsonArray("locations").get(0).getAsJsonObject().getAsJsonObject("physicalLocation");
        assertEquals("src/payments/app.py", physical.getAsJsonObject("artifactLocation").get("uri").getAsString());
        assertEquals(21, physical.getAsJsonObject("region").get("startLine").getAsInt());
        assertEquals("CRITICAL", first.getAsJsonObject("properties").get("severity").getAsString());

        JsonObject second = results.get(1).getAsJsonObject();
        assertEquals("warning", second.get("level").getAsString());
        assertFalse(second.getAsJsonArray("locations").get(0).getAsJsonObject()
                .getAsJsonObject("physicalLocation").has("region"), "no region when the line is unknown");

        assertEquals("note", results.get(2).getAsJsonObject().get("level").getAsString());
    }

    @Test
    void leavesOutFindingsWithoutAUsableFile() {
        AnalysisResult readiness = new AnalysisResult("release-readiness", "Release Readiness Score");
        readiness.addFinding(finding("Overall risk", "HIGH", "", 0));
        AnalysisResult review = new AnalysisResult("code-review", "AI Code Review");
        review.addFinding(finding("Absolute path", "LOW", "/etc/passwd", 1));
        review.addFinding(finding("Traversal", "LOW", "../outside/Secret.java", 1));
        review.addFinding(finding("Drive letter", "LOW", "C:\\work\\App.java", 1));
        review.addFinding(finding("Good", "LOW", ".\\src\\My App.java", 7));

        JsonObject run = run(List.of(readiness, review));

        JsonArray results = run.getAsJsonArray("results");
        assertEquals(1, results.size(), "only the finding with a workspace-relative file is written");
        assertEquals("src/My%20App.java", results.get(0).getAsJsonObject().getAsJsonArray("locations").get(0)
                .getAsJsonObject().getAsJsonObject("physicalLocation").getAsJsonObject("artifactLocation")
                .get("uri").getAsString());
        assertEquals(2, run.getAsJsonObject("tool").getAsJsonObject("driver").getAsJsonArray("rules").size(),
                "rules are listed even when an analyzer has no located findings");
        assertEquals(1, ForgeAISarifGenerator.locatedFindings(List.of(readiness, review)));
    }

    @Test
    void pipelineAdvisorFindingsPointAtTheJenkinsfile() {
        AnalysisResult advisor = new AnalysisResult("pipeline-advisor", "Pipeline Optimization Advisor");
        advisor.addFinding(finding("No timeout", "MEDIUM", "", 0));

        JsonObject result = run(List.of(advisor)).getAsJsonArray("results").get(0).getAsJsonObject();

        assertEquals("Jenkinsfile", result.getAsJsonArray("locations").get(0).getAsJsonObject()
                .getAsJsonObject("physicalLocation").getAsJsonObject("artifactLocation").get("uri").getAsString());
    }
}
