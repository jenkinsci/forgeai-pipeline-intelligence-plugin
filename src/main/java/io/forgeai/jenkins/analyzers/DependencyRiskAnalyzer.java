package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

public class DependencyRiskAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Dependency Analyst — an expert in supply-chain security and dependency management.

            INSTRUCTIONS:
            Analyze the provided dependency/manifest files for:
            1. KNOWN RISKY PATTERNS: Unpinned versions, wildcard ranges, deprecated packages.
            2. LICENSE RISKS: Identify copyleft licenses (GPL) in commercial projects, license conflicts.
            3. MAINTENANCE SIGNALS: Identify dependencies that are unmaintained, archived, or have
               very few contributors (bus factor = 1).
            4. TRANSITIVE RISK: Flag deep dependency trees that increase attack surface.
            5. DUPLICATION: Multiple packages solving the same problem.
            6. VERSION FRESHNESS: Major versions behind latest stable.

            Respond ONLY with a valid JSON object:
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview>",
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<explanation>",
                  "file": "<manifest file>",
                  "line": 0,
                  "suggestion": "<remediation>"
                }
              ]
            }
            """;

    public DependencyRiskAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "PROJECT CONTEXT:\n" + context + "\n\nDEPENDENCY FILES:\n"
                + truncateSource(sourceCode, 40000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Dependency Risk Scoring"; }
    @Override public String analyzerId() { return "dependency-risk"; }
}
