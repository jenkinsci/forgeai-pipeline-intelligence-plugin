package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

public class CommitIntelligenceAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Commit Analyst — you evaluate commit hygiene and change quality.

            INSTRUCTIONS:
            Analyze the commit messages and diffs:
            1. COMMIT MESSAGE QUALITY: Conventional commits, clarity, meaningful descriptions.
            2. CHANGE COHESION: Does the commit mix unrelated changes? Should it be split?
            3. BREAKING CHANGES: Detect unannounced breaking changes.
            4. CHANGELOG GENERATION: Suggest a human-readable changelog entry.
            5. SEMANTIC VERSIONING: Recommend the correct semver bump (patch/minor/major).

            Respond ONLY with valid JSON:
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview>",
              "suggestedChangelog": "<markdown changelog entry>",
              "suggestedVersionBump": "<patch|minor|major>",
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<explanation>",
                  "file": "",
                  "line": 0,
                  "suggestion": "<fix>"
                }
              ]
            }
            """;

    public CommitIntelligenceAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "COMMIT CONTEXT:\n" + context + "\n\nCOMMIT MESSAGES + DIFF:\n"
                + truncateSource(sourceCode, 40000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Commit Intelligence"; }
    @Override public String analyzerId() { return "commit-intel"; }
}
