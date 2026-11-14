package io.forgeai.jenkins;

import hudson.FilePath;
import hudson.model.Result;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.llm.OllamaProvider;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void analyzersCallTheLlmInParallel() throws Exception {
        llm.delayMs = 400;

        WorkflowRun b = run("forgeAI analyzers: ['code-review', 'vulnerability', 'architecture-drift', 'test-gaps'], "
                + "sourceGlob: 'src/**/*.java'", Result.SUCCESS);

        assertTrue(llm.maxInFlight.get() > 1, "expected overlapping LLM calls, saw " + llm.maxInFlight.get());
        assertTrue(llm.maxInFlight.get() <= 4, "must not exceed the provider default of 4");
        j.assertLogContains("Running 4 analysis requests, up to 4 at a time.", b);
    }

    @Test
    void parallelismOfOneRunsAnalyzersOneAtATime() throws Exception {
        cfg.setMaxParallelRequests(1);
        llm.delayMs = 100;

        run("forgeAI analyzers: ['code-review', 'vulnerability', 'test-gaps'], sourceGlob: 'src/**/*.java'", Result.SUCCESS);

        assertEquals(1, llm.maxInFlight.get());
        assertEquals(3, llm.prompts.size());
    }

    @Test
    void ollamaDefaultsToOneRequestAtATime() {
        cfg.setProvider(new OllamaProvider());
        assertEquals(1, cfg.effectiveParallelism());
        cfg.setMaxParallelRequests(3);
        assertEquals(3, cfg.effectiveParallelism());
    }

    @Test
    void releaseReadinessRunsLastWithEveryOtherResult() throws Exception {
        run("forgeAI analyzers: ['release-readiness', 'code-review', 'vulnerability'], sourceGlob: 'src/**/*.java'",
                Result.SUCCESS);

        assertEquals(3, llm.prompts.size());
        String last = llm.prompts.get(2);
        assertTrue(last.contains("Analyzer: AI Code Review"), "release-readiness should see code-review's result");
        assertTrue(last.contains("Analyzer: Security Vulnerability Analysis"), "and vulnerability's result");
    }

    // ── change scope ──────────────────────────────────────────────────

    @Test
    void changesScopeAnalyzesOnlyFilesChangedSinceBaseRef() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("changes", true);

        WorkflowRun b = run(p, "forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', "
                + "scope: 'changes', baseRef: 'base'", Result.SUCCESS);

        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("class Added"), "new file should be analyzed");
        assertTrue(prompt.contains("int changed"), "modified file should be analyzed");
        assertFalse(prompt.contains("class Untouched"), "unchanged file must be left out");
        assertTrue(prompt.contains("only files changed relative to base"), "the model should be told the view is partial");
        j.assertLogContains("3 file(s) changed relative to base", b); // README.md plus the two source files
    }

    @Test
    void autoScopeUsesThePullRequestTargetBranch() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("pr-build", true);

        run(p, "withEnv(['CHANGE_TARGET=base']) { forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java' }",
                Result.SUCCESS);

        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("class Added"));
        assertFalse(prompt.contains("class Untouched"), "a pull request build should analyze only its changes");
    }

    @Test
    void autoScopeAnalyzesEverythingOutsidePullRequests() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("branch-build", true);

        run(p, "forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java'", Result.SUCCESS);

        assertTrue(llm.prompts.get(0).contains("class Untouched"), "a branch build should analyze all matching files");
    }

    @Test
    void sourceAnalyzersAreSkippedWhenNoSourceChanged() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("docs-only", false);

        WorkflowRun b = run(p, "forgeAI analyzers: ['code-review', 'vulnerability'], sourceGlob: 'src/**/*.java', "
                + "scope: 'changes', baseRef: 'base', failOnCritical: true", Result.SUCCESS);

        assertTrue(llm.prompts.isEmpty(), "no LLM call should be made when there is nothing to analyze");
        j.assertLogContains("[ForgeAI/code-review] No source files to analyze", b);
        j.assertLogContains("No analyzers ran", b);
    }

    @Test
    void unknownBaseRefFallsBackToAllFilesWithAWarning() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("bad-base", true);

        WorkflowRun b = run(p, "forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java', "
                + "scope: 'changes', baseRef: 'no-such-branch'", Result.SUCCESS);

        assertTrue(llm.prompts.get(0).contains("class Untouched"));
        j.assertLogContains("could not determine changed files relative to no-such-branch", b);
    }

    @Test
    void commitIntelCoversCommitsSinceTheBase() throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        WorkflowJob p = repoWithChange("commits-since", true);

        run(p, "forgeAI analyzers: ['commit-intel'], scope: 'changes', baseRef: 'base'", Result.SUCCESS);

        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("feat: second change"), "commits after the base should be included");
        assertFalse(prompt.contains("chore: initial import"), "commits before the base should not");
        assertTrue(prompt.contains("DIFF AGAINST base"));
    }

    @Test
    void unknownScopeFailsTheStep() throws Exception {
        WorkflowRun b = run("forgeAI analyzers: ['code-review'], scope: 'everything'", Result.FAILURE);
        j.assertLogContains("Unknown scope 'everything'", b);
    }

    // ── chunking ──────────────────────────────────────────────────────

    @Test
    void sourceLargerThanOneRequestIsSplitAndMerged() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "chunks");
        writeLargeSources(p);

        WorkflowRun b = run(p, "def r = forgeAI(analyzers: ['code-review'], sourceGlob: 'src/**/*.java')\n"
                + "echo \"analyzers=${r.analyzerCount} files=${r.filesAnalyzed} skipped=${r.filesSkipped}\"", Result.SUCCESS);

        assertEquals(2, llm.prompts.size(), "75K characters of source should take two requests");
        String all = String.join("\n", llm.prompts);
        assertTrue(all.contains("class A") && all.contains("class B") && all.contains("class C"), "every file is analyzed");
        j.assertLogContains("split across 2 requests per analyzer", b);
        j.assertLogContains("Combined: 9/10 from 2 parts", b);
        j.assertLogContains("analyzers=1 files=4 skipped=0", b);
    }

    @Test
    void maxSourceChunksLimitsRequestsPerAnalyzer() throws Exception {
        cfg.setMaxSourceChunks(1);
        WorkflowJob p = j.createProject(WorkflowJob.class, "one-chunk");
        writeLargeSources(p);

        WorkflowRun b = run(p, "forgeAI analyzers: ['code-review'], sourceGlob: 'src/**/*.java'", Result.SUCCESS);

        assertEquals(1, llm.prompts.size());
        j.assertLogContains("were NOT analyzed", b);
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * A workspace that is a git repository with a branch {@code base} and one commit after it. The later commit
     * adds README.md and, when {@code touchSource} is set, modifies src/Small.java and adds src/Added.java.
     */
    private WorkflowJob repoWithChange(String name, boolean touchSource) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        File dir = new File(ws.getRemote());
        ws.child("src/Small.java").write("class Small {}", "UTF-8");
        ws.child("src/Untouched.java").write("class Untouched {}", "UTF-8");
        git(dir, "init", "-q");
        commitAll(dir, "chore: initial import");
        git(dir, "branch", "base");
        ws.child("README.md").write("docs", "UTF-8");
        if (touchSource) {
            ws.child("src/Small.java").write("class Small { int changed; }", "UTF-8");
            ws.child("src/Added.java").write("class Added {}", "UTF-8");
        }
        commitAll(dir, "feat: second change");
        return p;
    }

    private static void commitAll(File dir, String message) throws Exception {
        git(dir, "add", ".");
        git(dir, "-c", "user.name=ForgeAI Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", message);
    }

    /** Three 25,000-character files: any two fit one 60,000-character request, the third needs another. */
    private void writeLargeSources(WorkflowJob p) throws Exception {
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        for (String name : List.of("A", "B", "C")) {
            ws.child("src/" + name + ".java").write("class " + name + " { /* " + "x".repeat(25_000) + " */ }", "UTF-8");
        }
    }

    private WorkflowRun run(String step, Result expected) throws Exception {
        return run(j.createProject(WorkflowJob.class), step, expected);
    }

    private WorkflowRun run(WorkflowJob p, String step, Result expected) throws Exception {
        FilePath ws = j.jenkins.getWorkspaceFor(p);
        if (!ws.child("src/Small.java").exists()) {
            ws.child("src/Small.java").write("class Small {}", "UTF-8");
        }
        // Blank out variables a surrounding CI build may have set, so scope detection sees only what a test sets
        p.setDefinition(new CpsFlowDefinition("node { withEnv(['CHANGE_TARGET=', 'GIT_PREVIOUS_SUCCESSFUL_COMMIT=', "
                + "'GIT_PREVIOUS_COMMIT=']) { " + step + " } }", true));
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
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile String response = HIGH_SCORE;
        volatile boolean fail;
        volatile long delayMs;

        @Override
        public String complete(String systemPrompt, String userPrompt, int maxTokens) throws LLMException {
            prompts.add(userPrompt);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LLMException("interrupted", e, displayName());
            } finally {
                inFlight.decrementAndGet();
            }
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
