package io.forgeai.jenkins;

import com.sun.net.httpserver.HttpServer;
import hudson.ProxyConfiguration;
import io.forgeai.jenkins.llm.AnthropicProvider;
import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.llm.OllamaProvider;
import io.forgeai.jenkins.llm.OpenAICompatibleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies every provider sends its requests through the Jenkins HTTP proxy. The endpoints use
 * {@code .invalid} hostnames, which never resolve, so a request only succeeds if it went via the proxy.
 */
@WithJenkins
class ProviderProxyTest {

    private HttpServer proxy;
    private final List<String> proxiedHosts = new CopyOnWriteArrayList<>();
    private JenkinsRule j;

    @BeforeEach
    void startProxy(JenkinsRule rule) throws IOException {
        j = rule;
        proxy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        proxy.createContext("/", exchange -> {
            proxiedHosts.add(exchange.getRequestHeaders().getFirst("Host"));
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/v1/chat/completions") ? "{\"choices\":[{\"message\":{\"content\":\"via proxy\"}}]}"
                    : path.endsWith("/v1/messages") ? "{\"content\":[{\"type\":\"text\",\"text\":\"via proxy\"}]}"
                    : path.endsWith("/api/generate") ? "{\"response\":\"via proxy\"}"
                    : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        proxy.start();
        j.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", proxy.getAddress().getPort()));
    }

    @AfterEach
    void stopProxy() {
        proxy.stop(0);
    }

    @Test
    void openAiProviderUsesJenkinsProxy() throws Exception {
        OpenAICompatibleProvider p = new OpenAICompatibleProvider();
        p.setEndpoint("http://llm.forgeai.invalid/");
        assertProxied(p, "llm.forgeai.invalid");
    }

    @Test
    void anthropicProviderUsesJenkinsProxy() throws Exception {
        AnthropicProvider p = new AnthropicProvider();
        p.setEndpoint("http://claude.forgeai.invalid/");
        assertProxied(p, "claude.forgeai.invalid");
    }

    @Test
    void ollamaProviderUsesJenkinsProxy() throws Exception {
        OllamaProvider p = new OllamaProvider();
        p.setEndpoint("http://ollama.forgeai.invalid:11434");
        assertProxied(p, "ollama.forgeai.invalid:11434");
    }

    @Test
    void noProxyHostsBypassTheProxy() {
        j.jenkins.setProxy(new ProxyConfiguration("127.0.0.1", proxy.getAddress().getPort(),
                null, null, "*.forgeai.invalid"));
        OpenAICompatibleProvider p = new OpenAICompatibleProvider();
        p.setEndpoint("http://llm.forgeai.invalid/");

        assertThrows(LLMException.class, () -> p.complete("system", "user", 10));
        assertTrue(proxiedHosts.isEmpty(), "a no-proxy host must not be sent to the proxy");
    }

    private void assertProxied(LLMProvider provider, String expectedHost) throws LLMException {
        assertEquals("via proxy", provider.complete("system", "user", 10));
        assertEquals(List.of(expectedHost), proxiedHosts);
    }
}
