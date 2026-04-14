package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

/**
 * UNIQUE DIFFERENTIATOR — Architecture Drift Detection.
 *
 * Unlike simple linters or code-quality tools, this analyzer understands high-level
 * architectural patterns (layered, hexagonal, microservices, event-driven, CQRS, etc.)
 * and detects when code changes violate the intended architecture.
 *
 * It examines: package structure, dependency direction, layer violations,
 * circular dependencies, God classes, anemic domain models, and coupling metrics.
 */
public class ArchitectureDriftAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT = """
            You are ForgeAI Architecture Guardian — a principal architect who enforces architectural integrity.

            INSTRUCTIONS:
            Analyze the code for architecture drift and structural violations:

            1. PATTERN DETECTION: Identify the intended architecture from the package/module structure
               (layered, hexagonal/ports-and-adapters, clean architecture, microservice, MVC, CQRS, event-driven).
            2. DEPENDENCY DIRECTION: Check that dependencies flow inward (domain ← application ← infrastructure).
               Flag any layer that imports from a layer it should not depend on.
            3. CIRCULAR DEPENDENCIES: Detect circular references between packages/modules.
            4. COUPLING ANALYSIS: Identify God classes, excessive fan-out, tight coupling between modules.
            5. COHESION CHECK: Flag packages with mixed responsibilities (e.g., business logic in controllers).
            6. API BOUNDARY VIOLATIONS: Internal types exposed across module boundaries.
            7. DOMAIN MODEL HEALTH: Detect anemic domain models, business logic leaking into services.

            Respond ONLY with a valid JSON object (no markdown fences):
            {
              "score": <1-10>,
              "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
              "summary": "<2-3 sentence overview of architectural health>",
              "detectedArchitecture": "<name of detected pattern>",
              "findings": [
                {
                  "title": "<short title>",
                  "severity": "<CRITICAL|HIGH|MEDIUM|LOW|INFO>",
                  "description": "<explanation of the architectural violation>",
                  "file": "<filename or package>",
                  "line": 0,
                  "suggestion": "<how to fix while preserving the architecture>"
                }
              ]
            }

            SCORING: 10 = pristine architecture, 1 = no discernible structure.
            """;

    public ArchitectureDriftAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        super(llm, logger, maxTokens);
    }

    @Override
    public AnalysisResult analyze(String sourceCode, String context) throws LLMException {
        String userPrompt = "PROJECT STRUCTURE & CONTEXT:\n" + context
                + "\n\nSOURCE CODE / DIFF:\n" + truncateSource(sourceCode, 60000);
        String response = safeComplete(SYSTEM_PROMPT, userPrompt);
        return ResultParser.parse(response, analyzerId(), analyzerName(), logger);
    }

    @Override public String analyzerName() { return "Architecture Drift Detection"; }
    @Override public String analyzerId() { return "architecture-drift"; }
}
