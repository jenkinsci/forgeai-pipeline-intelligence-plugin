package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

/**
 * UNIQUE DIFFERENTIATOR — Holistic Release Readiness Scoring.
 *
 * This analyzer takes the output of ALL other analyzers and produces a composite
 * release readiness assessment — essentially an AI-powered quality gate.
 */
public class ReleaseReadinessAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Release Oracle — a seasoned release manager who decides if software is ship-ready.

            You are given the summarized results of multiple analysis passes (code review, security,
            architecture, test gaps, dependency risk). Synthesize them into a holistic release verdict.

            INSTRUCTIONS:
            1. COMPOSITE SCORE: Weighted average — Security issues weigh 3x, Architecture drift 2x, others 1x.
            2. BLOCKING ISSUES: List any finding that should BLOCK the release.
            3. RISK ASSESSMENT: Classify overall release risk as SHIP_IT | CAUTION | HOLD | BLOCK.
            4. RELEASE NOTES DRAFT: Suggest key points for release notes based on the changes.
            5. TECHNICAL DEBT: Estimate technical debt introduced (hours to remediate).

            Respond ONLY with a valid JSON object (no markdown fences):
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence release verdict>",
              "releaseVerdict": "<SHIP_IT|CAUTION|HOLD|BLOCK>",
              "technicalDebtHours": <estimated hours>,
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<synthesized cross-analyzer finding>",
                  "file": "",
                  "line": 0,
                  "suggestion": "<actionable remediation>"
                }
              ]
            }
            """;

    public ReleaseReadinessAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        // `sourceCode` here is actually the aggregated JSON of prior analyzer results
        String userPrompt = "PRIOR ANALYSIS RESULTS:\n" + context
                + "\n\nCHANGE SUMMARY:\n" + truncateSource(sourceCode, 30000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Release Readiness Score"; }
    @Override public String analyzerId() { return "release-readiness"; }
}
