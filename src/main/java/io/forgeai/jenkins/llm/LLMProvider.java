package io.forgeai.jenkins.llm;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.DescriptorExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;
import java.util.Collections;

/**
 * Extension point for LLM backends. Implement this interface (plus a Descriptor) to add a new
 * provider — even from a separate plugin — without modifying ForgeAI itself.
 */
public abstract class LLMProvider implements ExtensionPoint, Describable<LLMProvider>, Serializable {

    private static final long serialVersionUID = 1L;

    protected String endpoint;
    protected String modelId;
    private String apiKeyCredentialId = "";

    public abstract String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException;

    public abstract boolean healthCheck();

    public abstract String displayName();

    public String getEndpoint() { return endpoint; }
    @DataBoundSetter public void setEndpoint(String v) { this.endpoint = v; }

    public String getModelId() { return modelId; }
    @DataBoundSetter public void setModelId(String v) { this.modelId = v; }

    public String getApiKeyCredentialId() { return apiKeyCredentialId; }
    @DataBoundSetter public void setApiKeyCredentialId(String v) { this.apiKeyCredentialId = v; }

    protected String resolveApiKey() {
        if (apiKeyCredentialId == null || apiKeyCredentialId.isBlank()) return "";
        StringCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(apiKeyCredentialId));
        return cred != null ? cred.getSecret().getPlainText() : "";
    }

    @Override
    public LLMProviderDescriptor getDescriptor() {
        return (LLMProviderDescriptor) Jenkins.get().getDescriptorOrDie(getClass());
    }

    public static DescriptorExtensionList<LLMProvider, LLMProviderDescriptor> all() {
        return Jenkins.get().getDescriptorList(LLMProvider.class);
    }

    public abstract static class LLMProviderDescriptor extends Descriptor<LLMProvider> {

        @POST
        public ListBoxModel doFillApiKeyCredentialIdItems(@QueryParameter String apiKeyCredentialId) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(apiKeyCredentialId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, Jenkins.get(), StringCredentials.class,
                            Collections.<DomainRequirement>emptyList(), CredentialsMatchers.always())
                    .includeCurrentValue(apiKeyCredentialId);
        }

        @POST
        public hudson.util.FormValidation doCheckEndpoint(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) return hudson.util.FormValidation.error("Endpoint is required");
            if (!value.startsWith("http")) return hudson.util.FormValidation.error("Must start with http:// or https://");
            return hudson.util.FormValidation.ok();
        }

        @POST
        public hudson.util.FormValidation doCheckModelId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.isBlank()) return hudson.util.FormValidation.error("Model ID is required");
            return hudson.util.FormValidation.ok();
        }
    }
}
