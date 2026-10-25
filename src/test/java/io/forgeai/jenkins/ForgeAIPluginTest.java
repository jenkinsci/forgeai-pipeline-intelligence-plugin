package io.forgeai.jenkins;

import io.forgeai.jenkins.analyzers.ResultParser;
import io.forgeai.jenkins.reports.AnalysisResult;
import io.forgeai.jenkins.reports.ForgeAIReportGenerator;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForgeAIPluginTest {

    private final PrintStream log = System.out;

    @Test
    void testResultParser_validJson() {
        String json = """
                {
                  "score": 7,
                  "severity": "MEDIUM",
                  "summary": "Code is generally well-written with minor issues.",
                  "findings": [
                    {
                      "title": "Unused import",
                      "severity": "LOW",
                      "description": "java.util.List is imported but never used.",
                      "file": "Main.java",
                      "line": 3,
                      "suggestion": "Remove the unused import."
                    }
                  ]
                }
                """;
        AnalysisResult result = ResultParser.parse(json, "code-review", "AI Code Review", log);
        assertEquals(7, result.getScore());
        assertEquals("MEDIUM", result.getSeverity());
        assertEquals(1, result.getFindings().size());
        assertEquals("Unused import", result.getFindings().get(0).getTitle());
        assertEquals(3, result.getFindings().get(0).getLine());
    }

    @Test
    void testResultParser_markdownWrapped() {
        String json = """
                ```json
                {"score":9,"severity":"LOW","summary":"All good.","findings":[]}
                ```
                """;
        AnalysisResult result = ResultParser.parse(json, "test", "Test", log);
        assertEquals(9, result.getScore());
        assertEquals(0, result.getFindings().size());
    }

    @Test
    void testResultParser_malformedJson() {
        String garbage = "This is not JSON at all.";
        AnalysisResult result = ResultParser.parse(garbage, "test", "Test", log);
        assertEquals(5, result.getScore());
        assertNotNull(result.getRawMarkdown());
    }

    @Test
    void testResultParser_extraTextBeforeJson() {
        String response = "Sure! Here is the analysis:\n{\"score\":4,\"severity\":\"HIGH\"," +
                "\"summary\":\"Issues found.\",\"findings\":[]}";
        AnalysisResult result = ResultParser.parse(response, "vuln", "Vuln", log);
        assertEquals(4, result.getScore());
        assertEquals("HIGH", result.getSeverity());
    }

    @Test
    void testAnalysisResult_countBySeverity() {
        AnalysisResult r = new AnalysisResult("test", "Test");
        r.addFinding(new AnalysisResult.Finding("A", "CRITICAL", "desc"));
        r.addFinding(new AnalysisResult.Finding("B", "CRITICAL", "desc"));
        r.addFinding(new AnalysisResult.Finding("C", "HIGH", "desc"));
        r.addFinding(new AnalysisResult.Finding("D", "LOW", "desc"));

        assertEquals(2, r.countBySeverity("CRITICAL"));
        assertEquals(1, r.countBySeverity("HIGH"));
        assertEquals(0, r.countBySeverity("MEDIUM"));
    }

    @Test
    void testResultParser_flagsParseFailure() {
        assertTrue(ResultParser.parse("not json", "t", "T", log).isParseFailed());
        assertFalse(ResultParser.parse("{\"score\":8,\"findings\":[]}", "t", "T", log).isParseFailed());
    }

    @Test
    void testResultParser_normalizesSeverity() {
        String json = "{\"score\":3,\"severity\":\"critical\",\"findings\":["
                + "{\"title\":\"x\",\"severity\":\"\\\"><script>alert(1)</script>\"}]}";
        AnalysisResult result = ResultParser.parse(json, "t", "T", log);
        assertEquals("CRITICAL", result.getSeverity());
        assertEquals("MEDIUM", result.getFindings().get(0).getSeverity());
    }

    @Test
    void testReportGenerator_escapesLlmControlledFields() {
        AnalysisResult r = new AnalysisResult("t", "T");
        r.setScore(5);
        r.setSummary("<b>summary</b>");
        r.addFinding(new AnalysisResult.Finding("<img src=x onerror=alert(1)>",
                "\"><script>alert(1)</script>", "desc"));
        String html = ForgeAIReportGenerator.generateHtml(List.of(r), "folder/job", "1");
        assertFalse(html.contains("<script>"), "severity must be escaped");
        assertFalse(html.contains("<img"), "title must be escaped");
        assertFalse(html.contains("<b>summary"), "summary must be escaped");
    }

    @Test
    void testAnalysisResult_scoreClamp() {
        AnalysisResult r = new AnalysisResult("test", "Test");
        r.setScore(15);
        assertEquals(10, r.getScore());
        r.setScore(-5);
        assertEquals(1, r.getScore());
    }
}
