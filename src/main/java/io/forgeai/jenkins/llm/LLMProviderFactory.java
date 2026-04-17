package io.forgeai.jenkins.llm;

import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;

/** @deprecated Providers are now configured directly in ForgeAIGlobalConfiguration. Use cfg.getProvider() instead. */
@Deprecated
public final class LLMProviderFactory {

    private LLMProviderFactory() {}

    public static LLMProvider create() {
        return ForgeAIGlobalConfiguration.get().getProvider();
    }

    public static LLMProvider create(ForgeAIGlobalConfiguration cfg) {
        return cfg.getProvider();
    }
}
