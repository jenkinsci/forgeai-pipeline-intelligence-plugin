package io.forgeai.jenkins.steps;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.forgeai.jenkins.analyzers.*;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import io.forgeai.jenkins.llm.LLMException;
import io.forgeai.jenkins.llm.LLMProvider;

import io.forgeai.jenkins.reports.AnalysisResult;
import io.forgeai.jenkins.reports.ForgeAIReportGenerator;
import io.forgeai.jenkins.reports.ForgeAISarifGenerator;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Pipeline DSL step: forgeAI
 *
 * Usage in Jenkinsfile:
 *   forgeAI analyzers: ['code-review', 'vulnerability', 'architecture-drift'],
 *           sourceGlob: 'src/main/java/**',
 *           failOnCritical: true
 */
public class ForgeAIStep extends Step {

    /** Analyze changed files on pull request builds, everything otherwise. */
    public static final String SCOPE_AUTO = "auto";
    /** Analyze only files changed since the base revision. */
    public static final String SCOPE_CHANGES = "changes";
    /** Analyze every file matching {@code sourceGlob}. */
    public static final String SCOPE_ALL = "all";

    private static final String REPORT_DIR = "forgeai-reports";
    private static final String SARIF_FILE = REPORT_DIR + "/forgeai-report.sarif";

    private List<String> analyzers;
    private String sourceGlob;
    private String contextInfo;
    private String scope;
    private String baseRef;
    private Boolean failOnCritical;     // null → use the global "Fail Build on Low Score" setting
    private Integer criticalThreshold;  // null → use the global "Score Threshold" setting

    @DataBoundConstructor
    public ForgeAIStep() {
        this.analyzers = List.of("code-review", "vulnerability", "architecture-drift",
                "test-gaps", "dependency-risk", "commit-intel", "pipeline-advisor");
        this.sourceGlob = "**/*.java,**/*.py,**/*.js,**/*.ts,**/*.go,**/*.rs";
        this.contextInfo = "";
        this.scope = SCOPE_AUTO;
        this.baseRef = "";
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ForgeAIStepExecution(this, context);
    }

    // ── Getters / Setters ─────────────────────────────────────────────
    public List<String> getAnalyzers() { return analyzers; }
    @DataBoundSetter public void setAnalyzers(List<String> v) { this.analyzers = v; }

    public String getSourceGlob() { return sourceGlob; }
    @DataBoundSetter public void setSourceGlob(String v) { this.sourceGlob = v; }

    public String getContextInfo() { return contextInfo; }
    @DataBoundSetter public void setContextInfo(String v) { this.contextInfo = v; }

    public String getScope() { return scope; }
    @DataBoundSetter public void setScope(String v) { this.scope = v; }

    public String getBaseRef() { return baseRef; }
    @DataBoundSetter public void setBaseRef(String v) { this.baseRef = v; }

    public Boolean getFailOnCritical() { return failOnCritical; }
    @DataBoundSetter public void setFailOnCritical(Boolean v) { this.failOnCritical = v; }

    public Integer getCriticalThreshold() { return criticalThreshold; }
    @DataBoundSetter public void setCriticalThreshold(Integer v) { this.criticalThreshold = v; }

    // ── Execution ─────────────────────────────────────────────────────
    private static class ForgeAIStepExecution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {
        private static final long serialVersionUID = 1L;
        private final transient ForgeAIStep step;

        ForgeAIStepExecution(ForgeAIStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            FilePath workspace = getContext().get(FilePath.class);
            Run<?, ?> build = getContext().get(Run.class);
            EnvVars env = getContext().get(EnvVars.class);
            Launcher launcher = getContext().get(Launcher.class);
            PrintStream log = listener.getLogger();

            ForgeAIGlobalConfiguration cfg = ForgeAIGlobalConfiguration.get();
            LLMProvider llm = cfg.getProvider();
            int maxTokens = cfg.getMaxTokens();
            String scope = normalizeScope(step.getScope());

            log.println("╔══════════════════════════════════════════════════╗");
            log.println("║   🔥 ForgeAI Pipeline Intelligence v1.0         ║");
            log.println("║   Provider: " + padRight(llm.displayName(), 37) + "║");
            log.println("╚══════════════════════════════════════════════════╝");

            WorkspaceInputs inputs = new WorkspaceInputs(workspace, launcher, env, log);

            // ── Decide what to analyze: only what changed, or everything matching the glob ──
            String base = null;
            Set<String> changed = null;
            if (!SCOPE_ALL.equals(scope)) {
                List<String> candidates = inputs.changeBaseCandidates(step.getBaseRef(), SCOPE_CHANGES.equals(scope));
                base = inputs.resolveChangeBase(candidates);
                if (base != null) {
                    changed = inputs.changedFiles(base);
                }
                if (changed != null) {
                    log.println("[ForgeAI] Scope: " + changed.size() + " file(s) changed relative to " + base + ".");
                } else {
                    base = null;
                    if (!candidates.isEmpty() || SCOPE_CHANGES.equals(scope)) {
                        log.println("[ForgeAI] WARNING: could not determine changed files"
                                + (candidates.isEmpty() ? " (no pull request target, baseRef or previous build commit)"
                                        : " relative to " + String.join(" / ", candidates))
                                + " — analyzing all files matching sourceGlob."
                                + " Fetch the target branch in your checkout, or set baseRef.");
                    }
                }
            }

            WorkspaceInputs.Sources sources = inputs.collect(step.getSourceGlob().split(","), changed,
                    BaseAnalyzer.SOURCE_CHAR_LIMIT, cfg.getMaxSourceChunks(), "source");
            String context = buildContext(inputs, env, step.getContextInfo(), base);

            List<AnalysisResult> results = new ArrayList<>();
            Set<String> failed = new LinkedHashSet<>();
            Map<String, BaseAnalyzer> registry = buildAnalyzerRegistry(llm, log, maxTokens);

            // Phase 1: gather each analyzer's input from the workspace (sequential I/O)
            List<Job> jobs = new ArrayList<>();
            BaseAnalyzer releaseReadiness = null;
            for (String id : step.getAnalyzers()) {
                BaseAnalyzer analyzer = registry.get(id);
                if (analyzer == null) {
                    log.println("[ForgeAI] WARNING: Unknown analyzer '" + id + "' — skipping.");
                    continue;
                }

                // Check if globally enabled
                if (!isAnalyzerEnabled(id, cfg)) {
                    log.println("[ForgeAI] Analyzer '" + id + "' is disabled in global config — skipping.");
                    continue;
                }

                try {
                    switch (id) {
                        // Release readiness weighs the other results, so it runs after them
                        case "release-readiness" -> releaseReadiness = analyzer;
                        // Pipeline advisor gets the Jenkinsfile
                        case "pipeline-advisor" -> jobs.add(new Job(id, analyzer, inputs.jenkinsfile(), context, 1, 1));
                        // Dependency risk gets manifest files
                        case "dependency-risk" -> jobs.add(new Job(id, analyzer, inputs.manifests(), context, 1, 1));
                        // Commit intelligence gets git history, not source files
                        case "commit-intel" -> {
                            String history = inputs.commitHistory(base);
                            if (history == null) {
                                log.println("[ForgeAI/commit-intel] No git history available in the workspace — skipping.");
                            } else {
                                jobs.add(new Job(id, analyzer, history, context, 1, 1));
                            }
                        }
                        // Everything else reads source, one request per chunk
                        default -> {
                            int parts = sources.chunks().size();
                            if (parts == 0) {
                                log.println("[ForgeAI/" + id + "] No source files to analyze — skipping.");
                            }
                            for (int i = 0; i < parts; i++) {
                                String ctx = parts > 1
                                        ? context + "\nThis request covers part " + (i + 1) + " of " + parts + " of the source."
                                        : context;
                                jobs.add(new Job(id, analyzer, sources.chunks().get(i), ctx, i + 1, parts));
                            }
                        }
                    }
                } catch (IOException e) {
                    log.println("[ForgeAI/" + id + "] ERROR: " + e.getMessage());
                    failed.add(id);
                }
            }

            // Phase 2: LLM calls, several at once; chunked analyzers are merged back into one result
            List<AnalysisResult> raw = runJobs(jobs, cfg.effectiveParallelism(), log, failed);
            Map<String, List<AnalysisResult>> byAnalyzer = new LinkedHashMap<>();
            for (int i = 0; i < jobs.size(); i++) {
                if (raw.get(i) != null) {
                    byAnalyzer.computeIfAbsent(jobs.get(i).id(), k -> new ArrayList<>()).add(raw.get(i));
                }
            }
            for (List<AnalysisResult> parts : byAnalyzer.values()) {
                AnalysisResult merged = AnalysisResult.merge(parts);
                if (parts.size() > 1) {
                    log.printf("[ForgeAI/%s] Combined: %d/10 from %d parts | Findings: %d%n",
                            merged.getAnalyzerId(), merged.getScore(), parts.size(), merged.getFindings().size());
                }
                results.add(merged);
            }

            if (releaseReadiness != null) {
                String ctx = results.isEmpty() ? context : serializeResults(results);
                String sample = sources.chunks().isEmpty() ? "" : sources.chunks().get(0);
                Job rr = new Job("release-readiness", releaseReadiness, sample, ctx, 1, 1);
                AnalysisResult verdict = runJobs(List.of(rr), 1, log, failed).get(0);
                if (verdict != null) {
                    results.add(verdict);
                }
            }

            // ── Generate reports ──
            Map<String, Object> output = new LinkedHashMap<>();
            if (!results.isEmpty()) {
                FilePath reportDir = new FilePath(workspace, REPORT_DIR);
                reportDir.mkdirs();

                if (cfg.isPublishHtmlReport()) {
                    String projectName = build.getParent().getFullName();
                    String buildNum = String.valueOf(build.getNumber());
                    String html = ForgeAIReportGenerator.generateHtml(results, projectName, buildNum);
                    new FilePath(reportDir, "forgeai-report.html").write(html, StandardCharsets.UTF_8.name());
                    log.println("\n📊 ForgeAI report saved to: " + REPORT_DIR + "/forgeai-report.html");
                }

                // SARIF for Warnings NG, code scanning and other static-analysis consumers
                new FilePath(reportDir, "forgeai-report.sarif")
                        .write(ForgeAISarifGenerator.generate(results), StandardCharsets.UTF_8.name());
                log.println("[ForgeAI] SARIF saved to: " + SARIF_FILE + " ("
                        + ForgeAISarifGenerator.locatedFindings(results) + " finding(s) with a file location)");
                output.put("sarifFile", SARIF_FILE);
            }

            // ── Build return map for pipeline consumption ──
            double composite = results.stream().mapToInt(AnalysisResult::getScore)
                    .average().orElse(5.0);
            output.put("compositeScore", composite);
            output.put("analyzerCount", results.size());
            output.put("totalFindings", results.stream()
                    .mapToLong(r -> r.getFindings().size()).sum());
            output.put("criticalCount", results.stream()
                    .mapToLong(r -> r.countBySeverity("CRITICAL")).sum());
            output.put("failedAnalyzers", new ArrayList<>(failed));
            output.put("analysisScope", changed != null ? SCOPE_CHANGES : SCOPE_ALL);
            output.put("filesAnalyzed", sources.filesIncluded());
            output.put("filesSkipped", sources.filesSkipped());

            for (AnalysisResult r : results) {
                output.put(r.getAnalyzerId() + "Score", r.getScore());
            }

            log.println("\n╔══════════════════════════════════════════════════╗");
            log.printf( "║   Composite Score:  %.1f / 10                     ║%n", composite);
            log.printf( "║   Total Findings:   %-5d                        ║%n",
                    output.get("totalFindings"));
            log.println("╚══════════════════════════════════════════════════╝");

            if (!failed.isEmpty()) {
                log.println("[ForgeAI] WARNING: analyzer(s) did not produce a usable result: "
                        + String.join(", ", failed));
            }

            // ── Fail build if threshold not met ──
            Boolean stepFail = step.getFailOnCritical();
            Integer stepThreshold = step.getCriticalThreshold();
            boolean shouldFail = stepFail != null ? stepFail : cfg.isFailOnCritical();
            int threshold = stepThreshold != null && stepThreshold > 0
                    ? stepThreshold : cfg.getCriticalThreshold();

            if (shouldFail) {
                // A gate that could not evaluate everything it was asked to must not pass.
                if (!failed.isEmpty()) {
                    throw new AbortException("[ForgeAI] BUILD FAILED — quality gate could not be evaluated; "
                            + "analyzer(s) failed: " + String.join(", ", failed));
                }
                if (results.isEmpty()) {
                    log.println("[ForgeAI] No analyzers ran — quality gate skipped.");
                } else if (composite < threshold) {
                    throw new AbortException(
                            "[ForgeAI] BUILD FAILED — Composite score " + String.format("%.1f", composite)
                                    + " is below threshold " + threshold);
                }
            }

            return output;
        }

        private static String normalizeScope(String scope) throws AbortException {
            String s = scope == null || scope.isBlank() ? SCOPE_AUTO : scope.strip().toLowerCase(Locale.ROOT);
            if (!Set.of(SCOPE_AUTO, SCOPE_CHANGES, SCOPE_ALL).contains(s)) {
                throw new AbortException("[ForgeAI] Unknown scope '" + scope + "'. Use auto, changes or all.");
            }
            return s;
        }

        /** One LLM request: an analyzer with the input it should analyze (one chunk of it when {@code parts > 1}). */
        private record Job(String id, BaseAnalyzer analyzer, String input, String context, int part, int parts) {}

        /**
         * Runs the jobs with up to {@code parallelism} LLM requests in flight. The returned list lines up
         * with {@code jobs}: a result, or {@code null} where the request failed. Failures are logged and
         * recorded in {@code failed}; a response that could not be parsed still returns a result.
         */
        private List<AnalysisResult> runJobs(List<Job> jobs, int parallelism, PrintStream log, Set<String> failed)
                throws InterruptedException {
            List<AnalysisResult> out = new ArrayList<>();
            if (jobs.isEmpty()) return out;
            int threads = Math.max(1, Math.min(parallelism, jobs.size()));
            if (threads > 1) {
                log.println("\n[ForgeAI] Running " + jobs.size() + " analysis requests, up to " + threads + " at a time.");
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads,
                    new NamingThreadFactory(new DaemonThreadFactory(), "ForgeAI analyzer"));
            try {
                List<Future<AnalysisResult>> futures = new ArrayList<>();
                for (Job job : jobs) {
                    futures.add(pool.submit(() -> runJob(job, log)));
                }
                for (int i = 0; i < jobs.size(); i++) {
                    String id = jobs.get(i).id();
                    try {
                        AnalysisResult result = futures.get(i).get();
                        out.add(result);
                        if (result.isParseFailed()) {
                            failed.add(id);
                        }
                    } catch (ExecutionException e) {
                        log.println("[ForgeAI/" + id + "] ERROR: " + e.getCause().getMessage());
                        failed.add(id);
                        out.add(null);
                    }
                }
                return out;
            } finally {
                pool.shutdownNow(); // also cancels in-flight work if the build was aborted
            }
        }

        private static AnalysisResult runJob(Job job, PrintStream log) throws LLMException {
            String part = job.parts() > 1 ? " (part " + job.part() + " of " + job.parts() + ")" : "";
            log.println("\n━━━ Running: " + job.analyzer().analyzerName() + part + " ━━━");
            long start = System.currentTimeMillis();
            AnalysisResult result = job.analyzer().analyze(job.input(), job.context());
            result.setDurationMs(System.currentTimeMillis() - start);
            log.printf("[ForgeAI/%s]%s Score: %d/10 | Findings: %d | Time: %.1fs%n",
                    job.id(), part, result.getScore(), result.getFindings().size(), result.getDurationMs() / 1000.0);
            return result;
        }

        // ── Helper: build context string ──────────────────────────────
        private String buildContext(WorkspaceInputs inputs, EnvVars env, String extra, String changeBase) {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Project: ").append(env.getOrDefault("JOB_NAME", "unknown")).append("\n");
            ctx.append("Branch: ").append(env.getOrDefault("GIT_BRANCH", "unknown")).append("\n");
            ctx.append("Build: #").append(env.getOrDefault("BUILD_NUMBER", "0")).append("\n");

            // Detect language from files present
            ctx.append("Files in workspace: ").append(inputs.directoryTree());

            if (changeBase != null) {
                ctx.append("\n\nAnalysis scope: only files changed relative to ").append(changeBase)
                        .append(" are included, so the code below is a partial view of the project.");
            }
            if (extra != null && !extra.isBlank()) {
                ctx.append("\n\nAdditional context: ").append(extra);
            }
            return ctx.toString();
        }

        // ── Helper: serialize prior results for release-readiness ─────
        private String serializeResults(List<AnalysisResult> results) {
            return results.stream()
                    .map(r -> String.format("Analyzer: %s | Score: %d/10 | Findings: %d | Summary: %s",
                            r.getAnalyzerName(), r.getScore(), r.getFindings().size(), r.getSummary()))
                    .collect(Collectors.joining("\n"));
        }

        private Map<String, BaseAnalyzer> buildAnalyzerRegistry(LLMProvider llm, PrintStream log, int maxTokens) {
            Map<String, BaseAnalyzer> reg = new LinkedHashMap<>();
            reg.put("code-review", new CodeReviewAnalyzer(llm, log, maxTokens));
            reg.put("vulnerability", new VulnerabilityAnalyzer(llm, log, maxTokens));
            reg.put("architecture-drift", new ArchitectureDriftAnalyzer(llm, log, maxTokens));
            reg.put("test-gaps", new TestGapAnalyzer(llm, log, maxTokens));
            reg.put("dependency-risk", new DependencyRiskAnalyzer(llm, log, maxTokens));
            reg.put("commit-intel", new CommitIntelligenceAnalyzer(llm, log, maxTokens));
            reg.put("pipeline-advisor", new PipelineAdvisorAnalyzer(llm, log, maxTokens));
            reg.put("release-readiness", new ReleaseReadinessAnalyzer(llm, log, maxTokens));
            return reg;
        }

        private boolean isAnalyzerEnabled(String id, ForgeAIGlobalConfiguration cfg) {
            return switch (id) {
                case "code-review" -> cfg.isEnableCodeReview();
                case "vulnerability" -> cfg.isEnableVulnerabilityAnalysis();
                case "architecture-drift" -> cfg.isEnableArchitectureDrift();
                case "test-gaps" -> cfg.isEnableTestGapAnalysis();
                case "dependency-risk" -> cfg.isEnableDependencyRisk();
                case "commit-intel" -> cfg.isEnableCommitIntelligence();
                case "pipeline-advisor" -> cfg.isEnablePipelineAdvisor();
                case "release-readiness" -> cfg.isEnableReleaseReadiness();
                default -> true;
            };
        }

        private static String padRight(String s, int n) {
            return String.format("%-" + n + "s", s);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() { return "forgeAI"; }

        @Override
        public String getDisplayName() { return "ForgeAI Pipeline Intelligence Analysis"; }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class, FilePath.class, Run.class, EnvVars.class, Launcher.class);
        }
    }
}
