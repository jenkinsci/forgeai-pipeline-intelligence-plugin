package io.forgeai.jenkins.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Ollama provider for air-gapped / local LLM inference.
 * Talks to the Ollama REST API at /api/generate.
 * Recommended models: codellama, deepseek-coder, mistral, llama3, phi3.
 */
public class OllamaProvider implements LLMProvider {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private final String endpoint;
    private final String model;
    private final double temperature;
    private final int timeoutSeconds;

    public OllamaProvider(String endpoint, String model, double temperature, int timeoutSeconds) {
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT
                : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.model = model;
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds * 3L, TimeUnit.SECONDS) // local models can be slow
                .build();

        JsonObject options = new JsonObject();
        options.addProperty("temperature", temperature);
        options.addProperty("num_predict", maxTokens);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("system", systemPrompt);
        body.addProperty("prompt", userPrompt);
        body.addProperty("stream", false);
        body.add("options", options);

        String url = endpoint + "/api/generate";

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException(
                        "Ollama returned HTTP " + response.code() + ": " + responseBody,
                        response.code(), displayName());
            }
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            return json.get("response").getAsString();
        } catch (IOException e) {
            throw new LLMException("Cannot reach Ollama at " + endpoint + ": " + e.getMessage(),
                    e, displayName());
        }
    }

    @Override
    public boolean healthCheck() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response r = client.newCall(new Request.Builder().url(endpoint + "/api/tags").build()).execute()) {
            return r.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String displayName() {
        return "Ollama Local (" + model + ")";
    }
}
