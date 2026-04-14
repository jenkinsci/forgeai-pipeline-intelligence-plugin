package io.forgeai.jenkins.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import io.forgeai.jenkins.llm.*;

import java.util.Collections;

/**
 * Global configuration page for ForgeAI Pipeline Intelligence.
 * Accessible from: Manage Jenkins → Configure System → ForgeAI Pipeline Intelligence.
 */
@Extension
@Symbol("forgeAI")
public class ForgeAIGlobalConfiguration extends GlobalConfiguration {

    // ── LLM Provider Settings ──────────────────────────────────────────
    private String providerType = "openai";          // openai | anthropic | ollama
    private String llmEndpoint = "https://api.openai.com/";
    private String modelId = "gpt-4o";
    private String apiKeyCredentialId = "";
    private double temperature = 0.2;
    private int    timeoutSeconds = 120;
    private int    maxTokens = 4096;

    // ── Feature Toggles ────────────────────────────────────────────────
    private boolean enableCodeReview = true;
    private boolean enableVulnerabilityAnalysis = true;
    private boolean enableArchitectureDrift = true;
    private boolean enableTestGapAnalysis = true;
    private boolean enableReleaseReadiness = true;
    private boolean enableCommitIntelligence = true;
    private boolean enableDependencyRisk = true;
    private boolean enablePipelineAdvisor = true;

    // ── Reporting ──────────────────────────────────────────────────────
    private boolean publishHtmlReport = true;
    private boolean failOnCritical = false;
    private int     criticalThreshold = 3;       // fail if score < 3/10
    private String  customSystemPrompt = "";

    public ForgeAIGlobalConfiguration() {
        load();
    }

    public static ForgeAIGlobalConfiguration get() {
        return GlobalConfiguration.all().get(ForgeAIGlobalConfiguration.class);
    }

    // ── Persist ────────────────────────────────────────────────────────
    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    // ── Resolve the API key from Jenkins Credentials store ────────────
    public String resolveApiKey() {
        if (apiKeyCredentialId == null || apiKeyCredentialId.isBlank()) return "";
        StringCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(apiKeyCredentialId));
        return cred != null ? cred.getSecret().getPlainText() : "";
    }

    // ── Validation helpers shown in real-time in the UI ────────────────
    public FormValidation doCheckLlmEndpoint(@QueryParameter String value) {
        if (value == null || value.isBlank()) return FormValidation.error("Endpoint is required");
        if (!value.startsWith("http")) return FormValidation.error("Must start with http:// or https://");
        return FormValidation.ok();
    }

    public FormValidation doCheckModelId(@QueryParameter String value) {
        if (value == null || value.isBlank()) return FormValidation.error("Model ID is required");
        return FormValidation.ok();
    }

    public FormValidation doTestConnection(@QueryParameter String providerType,
                                           @QueryParameter String llmEndpoint,
                                           @QueryParameter String modelId,
                                           @QueryParameter String apiKeyCredentialId) {
        try {
            // Temporarily build a provider for the test
            ForgeAIGlobalConfiguration temp = new ForgeAIGlobalConfiguration();
            temp.setProviderType(providerType);
            temp.setLlmEndpoint(llmEndpoint);
            temp.setModelId(modelId);
            temp.setApiKeyCredentialId(apiKeyCredentialId);

            LLMProvider provider = LLMProviderFactory.create(temp);
            if (provider.healthCheck()) {
                return FormValidation.ok("Connection successful — %s is reachable.", provider.displayName());
            } else {
                return FormValidation.error("Health-check failed. Verify endpoint, API key, and model ID.");
            }
        } catch (Exception e) {
            return FormValidation.error("Connection test failed: " + e.getMessage());
        }
    }

    /** Populate the API-key credential dropdown. */
    public ListBoxModel doFillApiKeyCredentialIdItems(@QueryParameter String apiKeyCredentialId) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(apiKeyCredentialId);
        }
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        Jenkins.get(),
                        StringCredentials.class,
                        Collections.<DomainRequirement>emptyList(),
                        CredentialsMatchers.always())
                .includeCurrentValue(apiKeyCredentialId);
    }

    /** Provider type dropdown. */
    public ListBoxModel doFillProviderTypeItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("OpenAI / OpenAI-Compatible (LM Studio, vLLM, LocalAI)", "openai");
        items.add("Anthropic Claude", "anthropic");
        items.add("Ollama (Local)", "ollama");
        return items;
    }

    // ── Getters & Setters (DataBoundSetter for Jenkins persistence) ───

    public String getProviderType() { return providerType; }
    @DataBoundSetter public void setProviderType(String v) { this.providerType = v; }

    public String getLlmEndpoint() { return llmEndpoint; }
    @DataBoundSetter public void setLlmEndpoint(String v) { this.llmEndpoint = v; }

    public String getModelId() { return modelId; }
    @DataBoundSetter public void setModelId(String v) { this.modelId = v; }

    public String getApiKeyCredentialId() { return apiKeyCredentialId; }
    @DataBoundSetter public void setApiKeyCredentialId(String v) { this.apiKeyCredentialId = v; }

    public double getTemperature() { return temperature; }
    @DataBoundSetter public void setTemperature(double v) { this.temperature = v; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    @DataBoundSetter public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }

    public int getMaxTokens() { return maxTokens; }
    @DataBoundSetter public void setMaxTokens(int v) { this.maxTokens = v; }

    public boolean isEnableCodeReview() { return enableCodeReview; }
    @DataBoundSetter public void setEnableCodeReview(boolean v) { this.enableCodeReview = v; }

    public boolean isEnableVulnerabilityAnalysis() { return enableVulnerabilityAnalysis; }
    @DataBoundSetter public void setEnableVulnerabilityAnalysis(boolean v) { this.enableVulnerabilityAnalysis = v; }

    public boolean isEnableArchitectureDrift() { return enableArchitectureDrift; }
    @DataBoundSetter public void setEnableArchitectureDrift(boolean v) { this.enableArchitectureDrift = v; }

    public boolean isEnableTestGapAnalysis() { return enableTestGapAnalysis; }
    @DataBoundSetter public void setEnableTestGapAnalysis(boolean v) { this.enableTestGapAnalysis = v; }

    public boolean isEnableReleaseReadiness() { return enableReleaseReadiness; }
    @DataBoundSetter public void setEnableReleaseReadiness(boolean v) { this.enableReleaseReadiness = v; }

    public boolean isEnableCommitIntelligence() { return enableCommitIntelligence; }
    @DataBoundSetter public void setEnableCommitIntelligence(boolean v) { this.enableCommitIntelligence = v; }

    public boolean isEnableDependencyRisk() { return enableDependencyRisk; }
    @DataBoundSetter public void setEnableDependencyRisk(boolean v) { this.enableDependencyRisk = v; }

    public boolean isEnablePipelineAdvisor() { return enablePipelineAdvisor; }
    @DataBoundSetter public void setEnablePipelineAdvisor(boolean v) { this.enablePipelineAdvisor = v; }

    public boolean isPublishHtmlReport() { return publishHtmlReport; }
    @DataBoundSetter public void setPublishHtmlReport(boolean v) { this.publishHtmlReport = v; }

    public boolean isFailOnCritical() { return failOnCritical; }
    @DataBoundSetter public void setFailOnCritical(boolean v) { this.failOnCritical = v; }

    public int getCriticalThreshold() { return criticalThreshold; }
    @DataBoundSetter public void setCriticalThreshold(int v) { this.criticalThreshold = v; }

    public String getCustomSystemPrompt() { return customSystemPrompt; }
    @DataBoundSetter public void setCustomSystemPrompt(String v) { this.customSystemPrompt = v; }
}
