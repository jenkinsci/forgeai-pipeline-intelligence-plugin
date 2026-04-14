package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

/**
 * UNIQUE DIFFERENTIATOR — Analyzes the Jenkinsfile / pipeline definition itself,
 * suggesting optimizations for speed, cost, reliability, and best practices.
 */
public class PipelineAdvisorAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Pipeline Architect — a DevOps expert who optimizes CI/CD pipelines.

            INSTRUCTIONS:
            Analyze the Jenkinsfile / pipeline definition for:
            1. PARALLELIZATION: Stages that could run in parallel.
            2. CACHING: Missing build/dependency caches that slow builds.
            3. RESOURCE WASTE: Over-provisioned agents, unnecessary steps, redundant checkouts.
            4. FAILURE RESILIENCE: Missing retry, timeout, or post-failure handlers.
            5. SECURITY: Secrets handled insecurely, missing credential scoping.
            6. BEST PRACTICES: stash/unstash misuse, missing agent labels, artifact retention.
            7. ESTIMATED SAVINGS: Estimate % build-time reduction if recommendations are applied.

            Respond ONLY with valid JSON:
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview>",
              "estimatedSpeedupPercent": <0-100>,
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<explanation>",
                  "file": "Jenkinsfile",
                  "line": 0,
                  "suggestion": "<concrete pipeline code improvement>"
                }
              ]
            }
            """;

    public PipelineAdvisorAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "PIPELINE CONTEXT:\n" + context + "\n\nJENKINSFILE / PIPELINE:\n"
                + truncateSource(sourceCode, 30000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Pipeline Optimization Advisor"; }
    @Override public String analyzerId() { return "pipeline-advisor"; }
}
