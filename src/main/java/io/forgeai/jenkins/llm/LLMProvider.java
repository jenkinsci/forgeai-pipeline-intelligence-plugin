package io.forgeai.jenkins.llm;

import java.io.Serializable;

/**
 * Abstraction over any LLM backend.
 * Implementations: OpenAI, Anthropic Claude, Ollama (local), LM Studio, Custom HTTP endpoint.
 */
public interface LLMProvider extends Serializable {

    /**
     * Send a prompt to the LLM and receive a text completion.
     *
     * @param systemPrompt the system-level instruction
     * @param userPrompt   the user-level prompt with context
     * @param maxTokens    maximum tokens in the response
     * @return the LLM's text response
     * @throws LLMException if the call fails
     */
    String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException;

    /**
     * Health check — verifies the backend is reachable and the credentials are valid.
     *
     * @return true when a trivial completion succeeds
     */
    boolean healthCheck();

    /**
     * Human-readable label shown in the Jenkins UI.
     */
    String displayName();
}
