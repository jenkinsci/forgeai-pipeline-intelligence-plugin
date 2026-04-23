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

public class AnthropicProvider extends LLMProvider {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/";
    private static final String API_VERSION = "2023-06-01";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    @DataBoundConstructor
    public AnthropicProvider() {
        this.endpoint = DEFAULT_ENDPOINT;
        this.modelId = "claude-opus-4-7";
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
        body.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(ep + "v1/messages")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", Secret.toString(apiKey))
                .addHeader("anthropic-version", API_VERSION)
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String responseBody = rb != null ? rb.string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException("Anthropic API returned HTTP " + response.code() + ": " + responseBody,
                        response.code(), displayName());
            }
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray content = json.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (var el : content) {
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    sb.append(block.get("text").getAsString());
                }
            }
            return sb.toString();
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
    public String displayName() { return "Anthropic Claude (" + modelId + ")"; }

    @Extension
    @Symbol("anthropic")
    public static class DescriptorImpl extends LLMProviderDescriptor {

        @Override
        public String getDisplayName() { return "Anthropic Claude"; }

        @POST
        public hudson.util.FormValidation doTestConnection(
                @QueryParameter String endpoint,
                @QueryParameter String modelId,
                @QueryParameter String apiKeyCredentialId) {
            jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.ADMINISTER);
            try {
                AnthropicProvider p = new AnthropicProvider();
                p.setEndpoint(endpoint);
                p.setModelId(modelId);
                p.setApiKeyCredentialId(apiKeyCredentialId);
                return p.healthCheck()
                        ? hudson.util.FormValidation.ok("Connection successful — Anthropic API reachable.")
                        : hudson.util.FormValidation.error("Health-check failed. Verify endpoint, API key, and model.");
            } catch (Exception e) {
                return hudson.util.FormValidation.error("Connection test failed: " + e.getMessage());
            }
        }
    }
}
