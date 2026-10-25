package io.forgeai.jenkins;

import hudson.FilePath;
import hudson.model.Result;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@WithJenkins
class ForgeAIStepTest {

    private static final String LOW_SCORE = "{\"score\":2,\"severity\":\"HIGH\",\"summary\":\"Poor.\",\"findings\":[]}";
    private static final String HIGH_SCORE = "{\"score\":9,\"severity\":\"LOW\",\"summary\":\"Good.\",\"findings\":[]}";

    private JenkinsRule j;
    private FakeProvider llm;
    private ForgeAIGlobalConfiguration cfg;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        llm = new FakeProvider();
        cfg = ForgeAIGlobalConfiguration.get();
        cfg.setProvider(llm);
    }

    @Test
    void globalGateAppliesWhenStepDoesNotOverride() throws Exception {
        cfg.setFailOnCritical(true);
        cfg.setCriticalThreshold(3);
        llm.response = LOW_SCORE;

        WorkflowRun b = run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java'", Result.FAILURE);
        j.assertLogContains("is below threshold 3", b);
    }

    @Test
    void stepCanDisableGlobalGate() throws Exception {
        cfg.setFailOnCritical(true);
        llm.response = LOW_SCORE;

        run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', failOnCritical: false", Result.SUCCESS);
    }

    @Test
    void stepThresholdOverridesGlobalThreshold() throws Exception {
        cfg.setFailOnCritical(true);
        cfg.setCriticalThreshold(3);
        llm.response = HIGH_SCORE;

        WorkflowRun b = run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', criticalThreshold: 10",
                Result.FAILURE);
        j.assertLogContains("is below threshold 10", b);
    }

    @Test
    void gateFailsWhenAnalyzerErrors() throws Exception {
        llm.fail = true;

        WorkflowRun b = run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', failOnCritical: true",
                Result.FAILURE);
        j.assertLogContains("quality gate could not be evaluated", b);
    }

    @Test
    void gateFailsWhenResponseCannotBeParsed() throws Exception {
        llm.response = "I'm sorry, I can't help with that.";

        WorkflowRun b = run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', failOnCritical: true",
                Result.FAILURE);
        j.assertLogContains("analyzer(s) failed: code-review", b);
    }

    @Test
    void analyzerErrorWithoutGateOnlyWarns() throws Exception {
        llm.fail = true;

        WorkflowRun b = run("forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java'", Result.SUCCESS);
        j.assertLogContains("did not produce a usable result: code-review", b);
    }

    @Test
    void sourceCollectionSkipsFilesBeyondBudgetInsteadOfTruncating() throws Exception {
        llm.response = HIGH_SCORE;
        WorkflowJob p = j.createProject(WorkflowJob.class, "budget");
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        ws.child("src/Big.java").write("class Big { /* " + "x".repeat(70_000) + " */ }", "UTF-8");

        WorkflowRun b = run(p, "forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java'", Result.SUCCESS);

        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("class Small"), "small file should be analyzed in full");
        assertFalse(prompt.contains("class Big"), "oversized file should be skipped, not truncated");
        j.assertLogContains("were NOT analyzed", b);
    }

    @Test
    void commitIntelReceivesGitHistory() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        llm.response = HIGH_SCORE;
        WorkflowJob p = j.createProject(WorkflowJob.class, "commits");
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        ws.child("src/Small.java").write("class Small {}", "UTF-8");
        File dir = new File(ws.getRemote());
        git(dir, "init", "-q");
        git(dir, "add", ".");
        git(dir, "-c", "user.name=ForgeAI Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "feat: add widget support");

        run(p, "forgeAI analyzers: ['commit-intel']", Result.SUCCESS);

        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("feat: add widget support"), "commit message should reach the LLM");
        assertTrue(prompt.contains("DIFF OF HEAD COMMIT"), "HEAD diff should reach the LLM");
    }

    // ── helpers ───────────────────────────────────────────────────────

    private WorkflowRun run(String step, Result expected) throws Exception {
        return run(j.createProject(WorkflowJob.class), step, expected);
    }

    private WorkflowRun run(WorkflowJob p, String step, Result expected) throws Exception {
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        if (!ws.child("src/Small.java").exists()) {
            ws.child("src/Small.java").write("class Small {}", "UTF-8");
        }
        p.setDefinition(new CpsFlowDefinition("node { " + step + " }", true));
        return j.buildAndAssertStatus(expected, p);
    }

    private static boolean gitAvailable() {
        try {
            Process proc = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            proc.getInputStream().transferTo(OutputStream.nullOutputStream());
            return proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process proc = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "git timed out");
        assertEquals(0, proc.exitValue(), "git " + String.join(" ", args) + " failed: " + out);
    }

    /** Records prompts and returns a canned response (or fails) without any network calls. */
    public static class FakeProvider extends LLMProvider {
        private static final long serialVersionUID = 1L;

        final List<String> prompts = new CopyOnWriteArrayList<>();
        volatile String response = HIGH_SCORE;
        volatile boolean fail;

        @Override
        public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
            prompts.add(userPrompt);
            if (fail) throw new LLMException("simulated outage", 503, displayName());
            return response;
        }

        @Override public boolean healthCheck() { return true; }
        @Override public String displayName() { return "Fake LLM"; }

        @TestExtension
        public static class DescriptorImpl extends LLMProviderDescriptor {
            @Override public String getDisplayName() { return "Fake LLM"; }
        }
    }
}
