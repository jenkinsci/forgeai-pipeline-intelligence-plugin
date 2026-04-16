package io.forgeai.jenkins.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hudson.util.Secret;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Native Anthropic Claude API provider.
 * Uses the Messages API (v1/messages) directly.
 */
public class AnthropicProvider implements LLMProvider {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/";
    private static final String API_VERSION = "2023-06-01";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private final String endpoint;
    private final Secret apiKey;
    private final String model;
    private final double temperature;
    private final int timeoutSeconds;

    public AnthropicProvider(String endpoint, String apiKey, String model,
                             double temperature, int timeoutSeconds) {
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT
                : (endpoint.endsWith("/") ? endpoint : endpoint + "/");
        this.apiKey = Secret.fromString(apiKey);
        this.model = model;
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds * 2L, TimeUnit.SECONDS)
                .build();

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("system", systemPrompt);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        body.add("messages", messages);

        String url = endpoint + "v1/messages";

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", Secret.toString(apiKey))
                .addHeader("anthropic-version", API_VERSION)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException(
                        "Anthropic API returned HTTP " + response.code() + ": " + responseBody,
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
            String result = complete("You are a health-check bot.", "Reply OK", 10);
            return result != null && !result.isBlank();
        } catch (LLMException e) {
            return false;
        }
    }

    @Override
    public String displayName() {
        return "Anthropic Claude (" + model + ")";
    }
}
