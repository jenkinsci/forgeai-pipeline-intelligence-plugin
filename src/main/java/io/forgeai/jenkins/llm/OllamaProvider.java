package io.forgeai.jenkins.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import okhttp3.*;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Ollama provider for air-gapped / local LLM inference.
 * Recommended models: codellama, deepseek-coder, mistral, llama3, phi3.
 */
public class OllamaProvider extends LLMProvider {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    @DataBoundConstructor
    public OllamaProvider() {
        this.endpoint = DEFAULT_ENDPOINT;
        this.modelId = "codellama:13b";
    }

    @Override
    @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
            justification = "OkHttp Response.body() is @Nullable per its contract; null check is necessary")
    public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
        ForgeAIGlobalConfiguration cfg = ForgeAIGlobalConfiguration.get();
        int timeout = cfg.getTimeoutSeconds();
        String ep = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT
                : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout * 3L, TimeUnit.SECONDS) // local models can be slow
                .build();

        JsonObject options = new JsonObject();
        options.addProperty("temperature", cfg.getTemperature());
        options.addProperty("num_predict", maxTokens);

        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("system", systemPrompt);
        body.addProperty("prompt", userPrompt);
        body.addProperty("stream", false);
        body.add("options", options);

        Request request = new Request.Builder()
                .url(ep + "/api/generate")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody rb = response.body();
            String responseBody = rb != null ? rb.string() : "";
            if (!response.isSuccessful()) {
                throw new LLMException("Ollama returned HTTP " + response.code() + ": " + responseBody,
                        response.code(), displayName());
            }
            return GSON.fromJson(responseBody, JsonObject.class).get("response").getAsString();
        } catch (IOException e) {
            throw new LLMException("Cannot reach Ollama at " + ep + ": " + e.getMessage(), e, displayName());
        }
    }

    @Override
    public boolean healthCheck() {
        String ep = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT
                : (endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response r = client.newCall(new Request.Builder().url(ep + "/api/tags").build()).execute()) {
            return r.isSuccessful();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String displayName() { return "Ollama Local (" + modelId + ")"; }

    @Extension
    @Symbol("ollama")
    public static class DescriptorImpl extends LLMProviderDescriptor {

        @Override
        public String getDisplayName() { return "Ollama (Local)"; }

        @POST
        public hudson.util.FormValidation doTestConnection(@QueryParameter String endpoint) {
            jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.ADMINISTER);
            try {
                OllamaProvider p = new OllamaProvider();
                p.setEndpoint(endpoint);
                return p.healthCheck()
                        ? hudson.util.FormValidation.ok("Ollama is reachable.")
                        : hudson.util.FormValidation.error("Cannot reach Ollama. Is it running?");
            } catch (Exception e) {
                return hudson.util.FormValidation.error("Connection test failed: " + e.getMessage());
            }
        }
    }
}
