package io.forgeai.jenkins.llm;

public class LLMException extends Exception {
    private final int statusCode;
    private final String providerName;

    public LLMException(String message, String providerName) {
        super(message);
        this.statusCode = -1;
        this.providerName = providerName;
    }

    public LLMException(String message, int statusCode, String providerName) {
        super(message);
        this.statusCode = statusCode;
        this.providerName = providerName;
    }

    public LLMException(String message, Throwable cause, String providerName) {
        super(message, cause);
        this.statusCode = -1;
        this.providerName = providerName;
    }

    public int getStatusCode() { return statusCode; }
    public String getProviderName() { return providerName; }

    @Override
    public String toString() {
        return String.format("[ForgeAI/%s] %s (HTTP %d)", providerName, getMessage(), statusCode);
    }
}
