package io.forgeai.jenkins.steps;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.forgeai.jenkins.analyzers.*;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import io.forgeai.jenkins.llm.LLMProvider;

import io.forgeai.jenkins.reports.AnalysisResult;
import io.forgeai.jenkins.reports.ForgeAIReportGenerator;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    private List<String> analyzers;
    private String sourceGlob;
    private String contextInfo;
    private Boolean failOnCritical;     // null → use the global "Fail Build on Low Score" setting
    private Integer criticalThreshold;  // null → use the global "Score Threshold" setting

    @DataBoundConstructor
    public ForgeAIStep() {
        this.analyzers = List.of("code-review", "vulnerability", "architecture-drift",
                "test-gaps", "dependency-risk", "commit-intel", "pipeline-advisor");
        this.sourceGlob = "**/*.java,**/*.py,**/*.js,**/*.ts,**/*.go,**/*.rs";
        this.contextInfo = "";
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

            log.println("╔══════════════════════════════════════════════════╗");
            log.println("║   🔥 ForgeAI Pipeline Intelligence v1.0         ║");
            log.println("║   Provider: " + padRight(llm.displayName(), 37) + "║");
            log.println("╚══════════════════════════════════════════════════╝");

            // Collect source code from workspace
            String sourceCode = collectSources(workspace, step.getSourceGlob(), log);
            String context = buildContext(workspace, env, step.getContextInfo(), log);

            List<AnalysisResult> results = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            Map<String, BaseAnalyzer> registry = buildAnalyzerRegistry(llm, log, maxTokens);

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

                log.println("\n━━━ Running: " + analyzer.analyzerName() + " ━━━");
                long start = System.currentTimeMillis();
                try {
                    String input = sourceCode;
                    String ctx = context;

                    // Release readiness gets the prior results as input
                    if ("release-readiness".equals(id) && !results.isEmpty()) {
                        ctx = serializeResults(results);
                    }

                    // Pipeline advisor gets the Jenkinsfile
                    if ("pipeline-advisor".equals(id)) {
                        input = readJenkinsfile(workspace, log);
                    }

                    // Dependency risk gets manifest files
                    if ("dependency-risk".equals(id)) {
                        input = collectManifests(workspace, log);
                    }

                    // Commit intelligence gets git history, not source files
                    if ("commit-intel".equals(id)) {
                        input = collectCommitHistory(workspace, launcher, env);
                        if (input == null) {
                            log.println("[ForgeAI/commit-intel] No git history available in the workspace — skipping.");
                            continue;
                        }
                    }

                    AnalysisResult result = analyzer.analyze(input, ctx);
                    result.setDurationMs(System.currentTimeMillis() - start);
                    results.add(result);
                    if (result.isParseFailed()) {
                        failed.add(id);
                    }

                    log.printf("[ForgeAI/%s] Score: %d/10 | Findings: %d | Time: %.1fs%n",
                            id, result.getScore(), result.getFindings().size(),
                            result.getDurationMs() / 1000.0);
                } catch (Exception e) {
                    log.println("[ForgeAI/" + id + "] ERROR: " + e.getMessage());
                    failed.add(id);
                }
            }

            // ── Generate HTML Report ──
            if (cfg.isPublishHtmlReport() && !results.isEmpty()) {
                String projectName = build.getParent().getFullName();
                String buildNum = String.valueOf(build.getNumber());
                String html = ForgeAIReportGenerator.generateHtml(results, projectName, buildNum);

                FilePath reportDir = new FilePath(workspace, "forgeai-reports");
                reportDir.mkdirs();
                FilePath reportFile = new FilePath(reportDir, "forgeai-report.html");
                reportFile.write(html, StandardCharsets.UTF_8.name());
                log.println("\n📊 ForgeAI report saved to: forgeai-reports/forgeai-report.html");
            }

            // ── Build return map for pipeline consumption ──
            Map<String, Object> output = new LinkedHashMap<>();
            double composite = results.stream().mapToInt(AnalysisResult::getScore)
                    .average().orElse(5.0);
            output.put("compositeScore", composite);
            output.put("analyzerCount", results.size());
            output.put("totalFindings", results.stream()
                    .mapToLong(r -> r.getFindings().size()).sum());
            output.put("criticalCount", results.stream()
                    .mapToLong(r -> r.countBySeverity("CRITICAL")).sum());
            output.put("failedAnalyzers", failed);

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

        // ── Helper: collect source files matching glob ────────────────
        private String collectSources(FilePath workspace, String glob, PrintStream log) throws Exception {
            return collectFiles(workspace, glob.split(","), BaseAnalyzer.SOURCE_CHAR_LIMIT, "source", log);
        }

        /**
         * Concatenates whole files matching the given patterns, stopping at {@code budget} characters
         * so the analyzer sees every file it is given instead of silently truncating the middle.
         * Files that do not fit are skipped and reported in the build log.
         */
        private String collectFiles(FilePath workspace, String[] patterns, int budget, String kind,
                                    PrintStream log) throws Exception {
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new HashSet<>();
            List<String> skipped = new ArrayList<>();
            int included = 0;
            for (String g : patterns) {
                String pattern = g.strip();
                if (pattern.isEmpty()) continue;
                for (FilePath file : workspace.list(pattern)) {
                    if (!seen.add(file.getRemote())) continue;
                    String rel = relativePath(workspace, file);
                    String header = "\n=== FILE: " + rel + " ===\n";
                    // length() is in bytes: an upper bound on chars that avoids reading files that cannot fit
                    if (sb.length() + header.length() + file.length() > budget) {
                        skipped.add(rel);
                        continue;
                    }
                    sb.append(header).append(file.readToString());
                    included++;
                }
            }
            log.println("[ForgeAI] Included " + included + " " + kind + " file(s) in the analysis.");
            if (!skipped.isEmpty()) {
                log.println("[ForgeAI] WARNING: " + skipped.size() + " " + kind + " file(s) exceeded the "
                        + budget + "-character analysis budget and were NOT analyzed: "
                        + String.join(", ", skipped.subList(0, Math.min(10, skipped.size())))
                        + (skipped.size() > 10 ? ", ..." : "")
                        + ". Narrow the glob to the files that matter.");
            }
            return sb.toString();
        }

        private static String relativePath(FilePath workspace, FilePath file) {
            String ws = workspace.getRemote();
            String path = file.getRemote();
            return path.startsWith(ws) ? path.substring(ws.length()).replaceFirst("^[/\\\\]+", "") : path;
        }

        // ── Helper: collect git history for commit intelligence ───────
        /** Returns recent commits plus the HEAD diff, or {@code null} when git history is unavailable. */
        private String collectCommitHistory(FilePath workspace, Launcher launcher, EnvVars env)
                throws InterruptedException {
            String commits = runGit(workspace, launcher, env, "log", "-n", "20", "--stat", "--no-color");
            if (commits == null || commits.isBlank()) return null;

            StringBuilder sb = new StringBuilder("=== RECENT COMMITS ===\n").append(commits.strip());
            String diff = runGit(workspace, launcher, env, "show", "--no-color", "--format=", "HEAD");
            if (diff != null && !diff.isBlank()) {
                // Cut the diff rather than letting head/tail truncation drop the commit messages
                int room = CommitIntelligenceAnalyzer.COMMIT_CHAR_LIMIT - sb.length() - 100;
                if (room > 0) {
                    String d = diff.strip();
                    sb.append("\n\n=== DIFF OF HEAD COMMIT ===\n")
                            .append(d.length() > room ? d.substring(0, room) + "\n... [diff truncated]" : d);
                }
            }
            return sb.toString();
        }

        private String runGit(FilePath workspace, Launcher launcher, EnvVars env, String... args)
                throws InterruptedException {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                int rc = launcher.launch().cmds(cmd).envs(env).pwd(workspace).quiet(true)
                        .stdout(out).stderr(OutputStream.nullOutputStream()).join();
                return rc == 0 ? out.toString(StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                return null; // git not installed on the agent
            }
        }

        // ── Helper: build context string ──────────────────────────────
        private String buildContext(FilePath workspace, EnvVars env,
                                    String extra, PrintStream log) throws Exception {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Project: ").append(env.getOrDefault("JOB_NAME", "unknown")).append("\n");
            ctx.append("Branch: ").append(env.getOrDefault("GIT_BRANCH", "unknown")).append("\n");
            ctx.append("Build: #").append(env.getOrDefault("BUILD_NUMBER", "0")).append("\n");

            // Detect language from files present
            ctx.append("Files in workspace: ");
            try {
                String tree = workspace.act(new DirectoryTreeCallable());
                ctx.append(tree);
            } catch (Exception e) {
                ctx.append("(could not list)");
            }

            if (extra != null && !extra.isBlank()) {
                ctx.append("\n\nAdditional context: ").append(extra);
            }
            return ctx.toString();
        }

        // ── Helper: read Jenkinsfile ──────────────────────────────────
        private String readJenkinsfile(FilePath workspace, PrintStream log) {
            try {
                FilePath jf = new FilePath(workspace, "Jenkinsfile");
                if (jf.exists()) return jf.readToString();
                // Try .jenkins/Jenkinsfile
                jf = new FilePath(workspace, ".jenkins/Jenkinsfile");
                if (jf.exists()) return jf.readToString();
            } catch (Exception e) {
                log.println("[ForgeAI] Could not read Jenkinsfile: " + e.getMessage());
            }
            return "(Jenkinsfile not found in workspace)";
        }

        // ── Helper: collect dependency manifests ──────────────────────
        private String collectManifests(FilePath workspace, PrintStream log) throws Exception {
            String[] manifestGlobs = {
                    "**/pom.xml", "**/build.gradle", "**/build.gradle.kts",
                    "**/package.json", "**/requirements.txt", "**/Pipfile",
                    "**/go.mod", "**/Cargo.toml", "**/Gemfile", "**/composer.json"
            };
            String manifests = collectFiles(workspace, manifestGlobs,
                    DependencyRiskAnalyzer.MANIFEST_CHAR_LIMIT, "manifest", log);
            return manifests.isEmpty() ? "(No dependency manifests found)" : manifests;
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
