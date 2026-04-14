package io.forgeai.jenkins.analyzers;

import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.reports.AnalysisResult;

import java.io.PrintStream;

/**
 * Base class for all ForgeAI analyzers.
 * Each analyzer focuses on one dimension of code/pipeline intelligence.
 */
public abstract class BaseAnalyzer {

    protected final LLMProvider llm;
    protected final PrintStream logger;
    protected final int maxTokens;

    protected BaseAnalyzer(LLMProvider llm, PrintStream logger, int maxTokens) {
        this.llm = llm;
        this.logger = logger;
        this.maxTokens = maxTokens;
    }

    /**
     * Execute the analysis on the provided source material.
     *
     * @param sourceCode   the code or diff to analyze
     * @param context      additional context (file paths, language, project structure)
     * @return structured analysis result
     */
    public abstract AnalysisResult analyze(String sourceCode, String context) throws LLMException;

    /** Human-readable name of this analyzer for reports/logs. */
    public abstract String analyzerName();

    /** Short identifier for JSON keys and filenames. */
    public abstract String analyzerId();

    protected String safeComplete(String systemPrompt, String userPrompt) throws LLMException {
        logger.println("[ForgeAI/" + analyzerId() + "] Sending analysis request to LLM...");
        long start = System.currentTimeMillis();
        String result = llm.complete(systemPrompt, userPrompt, maxTokens);
        long elapsed = System.currentTimeMillis() - start;
        logger.printf("[ForgeAI/%s] LLM responded in %.1f seconds%n", analyzerId(), elapsed / 1000.0);
        return result;
    }

    /**
     * Truncate source code to fit within model context limits.
     * Keeps the first and last portions so the model sees both the start and end of files.
     */
    protected String truncateSource(String source, int maxChars) {
        if (source.length() <= maxChars) return source;
        int half = maxChars / 2;
        return source.substring(0, half)
                + "\n\n... [TRUNCATED " + (source.length() - maxChars) + " characters] ...\n\n"
                + source.substring(source.length() - half);
    }
}
