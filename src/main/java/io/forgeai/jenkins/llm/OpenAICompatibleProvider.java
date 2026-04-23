package io.forgeai.jenkins.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.util.Secret;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import okhttp3.*;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible provider. Works with OpenAI, Azure OpenAI, LM Studio, vLLM, LocalAI, and any
 * server exposing the /v1/chat/completions endpoint.
 */
public class OpenAICompatibleProvider extends LLMProvider {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    @DataBoundConstructor
    public OpenAICompatibleProvider() {
        this.endpoint = DEFAULT_ENDPOINT;
        this.modelId = "gpt-4o";
    }

    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            justification = "OkHttp Response.body() is @Nullable per its contract; null check is necessary")
    public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
        ForgeAIGlobalConfiguration cfg = ForgeAIGlobalConfiguration.get();
        Secret apiKey = Secret.fromString(resolveApiKey());
        String ep = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT
                : (endpoint.endsWith("/") ? endpoint : endpoint + "/");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(cfg.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(cfg.getTimeoutSeconds() * 2L, TimeUnit.SECONDS)
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", cfg.getTemperature());

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        body.add("messages", messages);

        Request.Builder reqBuilder = new Request.Builder()
                .url(ep + "v1/chat/completions")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .addHeader("Content-Type", "application/json");

        String apiKeyPlain = Secret.toString(apiKey);
        if (apiKeyPlain != null && !apiKeyPlain.isBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKeyPlain);
        }

        try (Response response = client.newCall(reqBuilder.build()).execute()) {
            ResponseBody rb = response.body();
            String responseBody = rb != null ? rb.string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException("API returned HTTP " + response.code() + ": " + responseBody,
                        response.code(), displayName());
            }
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (IOException e) {
            throw new LLMException("Network error: " + e.getMessage(), e, displayName());
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            complete("You are a health-check bot.", "Reply OK", 10);
            return true;
        } catch (LLMException e) {
            return false;
        }
    }

    @Override
    public String displayName() { return "OpenAI-Compatible (" + modelId + ")"; }

    @Extension
    @Symbol("openai")
    public static class DescriptorImpl extends LLMProviderDescriptor {

        @Override
        public String getDisplayName() { return "OpenAI / OpenAI-Compatible (LM Studio, vLLM, LocalAI)"; }

        @POST
        public hudson.util.FormValidation doTestConnection(
                @QueryParameter String endpoint,
                @QueryParameter String modelId,
                @QueryParameter String apiKeyCredentialId) {
            jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.ADMINISTER);
            try {
                OpenAICompatibleProvider p = new OpenAICompatibleProvider();
                p.setEndpoint(endpoint);
                p.setModelId(modelId);
                p.setApiKeyCredentialId(apiKeyCredentialId);
                return p.healthCheck()
                        ? hudson.util.FormValidation.ok("Connection successful — endpoint reachable.")
                        : hudson.util.FormValidation.error("Health-check failed. Verify endpoint, API key, and model.");
            } catch (Exception e) {
                return hudson.util.FormValidation.error("Connection test failed: " + e.getMessage());
            }
        }
    }
}
