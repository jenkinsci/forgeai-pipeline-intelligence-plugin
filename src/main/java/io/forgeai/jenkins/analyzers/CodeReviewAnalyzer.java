package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

public class CodeReviewAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Code Reviewer — an elite senior software engineer performing a thorough code review.

            INSTRUCTIONS:
            1. Analyze the provided code/diff for: correctness, performance, readability, maintainability,
               SOLID principles, DRY violations, error handling, naming conventions, and anti-patterns.
            2. Respond ONLY with a valid JSON object (no markdown fences) in this exact schema:

            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview>",
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<detailed explanation>",
                  "file": "<filename if identifiable>",
                  "line": <line number or 0>,
                  "suggestion": "<concrete fix or improvement>"
                }
              ]
            }

            SCORING GUIDE:
            - 9-10: Production-ready, excellent quality
            - 7-8: Good with minor improvements needed
            - 5-6: Acceptable but notable issues exist
            - 3-4: Significant problems, needs rework
            - 1-2: Critical issues, should not be merged
            """;

    public CodeReviewAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "PROJECT CONTEXT:\n" + context + "\n\nCODE TO REVIEW:\n"
                + truncateSource(sourceCode, 60000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "AI Code Review"; }
    @Override public String analyzerId() { return "code-review"; }
}
