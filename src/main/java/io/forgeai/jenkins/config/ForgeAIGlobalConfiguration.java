package io.forgeai.jenkins.config;

import hudson.Extension;
import hudson.util.FormValidation;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.llm.LLMProvider.LLMProviderDescriptor;
import io.forgeai.jenkins.llm.OpenAICompatibleProvider;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

/**
 * Global configuration page for ForgeAI Pipeline Intelligence.
 * Accessible from: Manage Jenkins → Configure System → ForgeAI Pipeline Intelligence.
 */
@Extension
@Symbol("forgeAI")
public class ForgeAIGlobalConfiguration extends GlobalConfiguration {

    // ── LLM Provider (selected via hetero-radio) ───────────────────
    private LLMProvider provider = new OpenAICompatibleProvider();

    // ── Common LLM call settings ───────────────────────────────────
    private double temperature    = 0.2;
    private int    timeoutSeconds = 120;
    private int    maxTokens      = 4096;

    // ── Feature Toggles ────────────────────────────────────────────
    private boolean enableCodeReview          = true;
    private boolean enableVulnerabilityAnalysis = true;
    private boolean enableArchitectureDrift   = true;
    private boolean enableTestGapAnalysis     = true;
    private boolean enableReleaseReadiness    = true;
    private boolean enableCommitIntelligence  = true;
    private boolean enableDependencyRisk      = true;
    private boolean enablePipelineAdvisor     = true;

    // ── Reporting ──────────────────────────────────────────────────
    private boolean publishHtmlReport  = true;
    private boolean failOnCritical     = false;
    private int     criticalThreshold  = 3;
    private String  customSystemPrompt = "";

    public ForgeAIGlobalConfiguration() {
        load();
    }

    public static ForgeAIGlobalConfiguration get() {
        return GlobalConfiguration.all().get(ForgeAIGlobalConfiguration.class);
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    /** Returns all registered LLMProvider descriptors for the hetero-radio widget. */
    public java.util.List<LLMProviderDescriptor> getProviderDescriptors() {
        return LLMProvider.all();
    }

    @POST
    public FormValidation doTestConnection() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (provider == null) return FormValidation.error("No provider configured.");
        try {
            return provider.healthCheck()
                    ? FormValidation.ok("Connection successful — %s is reachable.", provider.displayName())
                    : FormValidation.error("Health-check failed. Verify endpoint, API key, and model ID.");
        } catch (Exception e) {
            return FormValidation.error("Connection test failed: " + e.getMessage());
        }
    }

    // ── Getters & Setters ──────────────────────────────────────────

    public LLMProvider getProvider() { return provider; }
    @DataBoundSetter public void setProvider(LLMProvider v) { this.provider = v; }

    public double getTemperature() { return temperature; }
    @DataBoundSetter public void setTemperature(double v) { this.temperature = v; }

    public int getTimeoutSeconds() { return timeoutSeconds > 0 ? timeoutSeconds : 120; }
    @DataBoundSetter public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }

    public int getMaxTokens() { return maxTokens > 0 ? maxTokens : 4096; }
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
