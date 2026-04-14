package io.forgeai.jenkins.llm;

import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;

/**
 * Factory that reads the global Jenkins configuration and returns the
 * correct {@link LLMProvider} instance.
 */
public final class LLMProviderFactory {

    private LLMProviderFactory() {}

    public static LLMProvider create() {
        return create(ForgeAIGlobalConfiguration.get());
    }

    public static LLMProvider create(ForgeAIGlobalConfiguration cfg) {
        int timeout = cfg.getTimeoutSeconds() > 0 ? cfg.getTimeoutSeconds() : 120;
        double temp  = cfg.getTemperature();

        switch (cfg.getProviderType()) {
            case "anthropic":
                return new AnthropicProvider(
                        cfg.getLlmEndpoint(),
                        cfg.resolveApiKey(),
                        cfg.getModelId(),
                        temp, timeout);
            case "ollama":
                return new OllamaProvider(
                        cfg.getLlmEndpoint(),
                        cfg.getModelId(),
                        temp, timeout);
            case "openai":
            default:
                return new OpenAICompatibleProvider(
                        cfg.getLlmEndpoint(),
                        cfg.resolveApiKey(),
                        cfg.getModelId(),
                        temp, timeout);
        }
    }
}
