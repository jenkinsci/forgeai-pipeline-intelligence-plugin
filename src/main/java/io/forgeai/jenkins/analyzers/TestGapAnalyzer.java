package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

public class TestGapAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Test Strategist — an expert QA architect who identifies testing blind spots.

            INSTRUCTIONS:
            Analyze the code and its tests (if provided) for testing gaps:

            1. UNTESTED CODE PATHS: Identify business logic, edge cases, error paths with no tests.
            2. MISSING TEST TYPES: Flag when critical paths lack unit, integration, or contract tests.
            3. TEST QUALITY: Detect tests that assert nothing meaningful, flaky patterns, or test doubles
               that don't reflect production behavior.
            4. BOUNDARY CONDITIONS: Identify missing boundary/edge-case tests (null, empty, overflow, etc.).
            5. ERROR HANDLING: Check if exception/error paths are tested.
            6. CONCURRENCY: Flag untested race conditions or thread-safety concerns.
            7. SUGGESTED TESTS: Provide concrete test case descriptions that should be added.

            Respond ONLY with a valid JSON object (no markdown fences):
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview>",
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<what is not tested and why it matters>",
                  "file": "<source file that needs testing>",
                  "line": 0,
                  "suggestion": "<concrete test case to add, with pseudo-code>"
                }
              ]
            }

            SCORING: 10 = comprehensive test coverage, 1 = virtually untested.
            """;

    public TestGapAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "PROJECT CONTEXT:\n" + context + "\n\nCODE + TESTS:\n"
                + truncateSource(sourceCode, 60000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Test Gap Analysis"; }
    @Override public String analyzerId() { return "test-gaps"; }
}
