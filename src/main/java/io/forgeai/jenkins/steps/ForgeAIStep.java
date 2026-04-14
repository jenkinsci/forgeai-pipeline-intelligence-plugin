package io.forgeai.jenkins.steps;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.forgeai.jenkins.analyzers.*;
import io.forgeai.jenkins.config.ForgeAIGlobalConfiguration;
import io.forgeai.jenkins.llm.LLMProvider;
import io.forgeai.jenkins.llm.LLMProviderFactory;
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
    private boolean failOnCritical;
    private int criticalThreshold;

    @DataBoundConstructor
    public ForgeAIStep() {
        this.analyzers = List.of("code-review", "vulnerability", "architecture-drift",
                "test-gaps", "dependency-risk", "commit-intel", "pipeline-advisor");
        this.sourceGlob = "**/*.java,**/*.py,**/*.js,**/*.ts,**/*.go,**/*.rs";
        this.contextInfo = "";
        this.failOnCritical = false;
        this.criticalThreshold = 3;
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

    public boolean isFailOnCritical() { return failOnCritical; }
    @DataBoundSetter public void setFailOnCritical(boolean v) { this.failOnCritical = v; }

    public int getCriticalThreshold() { return criticalThreshold; }
    @DataBoundSetter public void setCriticalThreshold(int v) { this.criticalThreshold = v; }

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
            PrintStream log = listener.getLogger();

            ForgeAIGlobalConfiguration cfg = ForgeAIGlobalConfiguration.get();
            LLMProvider llm = LLMProviderFactory.create(cfg);
            int maxTokens = cfg.getMaxTokens();

            log.println("╔══════════════════════════════════════════════════╗");
            log.println("║   🔥 ForgeAI Pipeline Intelligence v1.0         ║");
            log.println("║   Provider: " + padRight(llm.displayName(), 37) + "║");
            log.println("╚══════════════════════════════════════════════════╝");

            // Collect source code from workspace
            String sourceCode = collectSources(workspace, step.getSourceGlob(), log);
            String context = buildContext(workspace, env, step.getContextInfo(), log);

            List<AnalysisResult> results = new ArrayList<>();
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

                    AnalysisResult result = analyzer.analyze(input, ctx);
                    result.setDurationMs(System.currentTimeMillis() - start);
                    results.add(result);

                    log.printf("[ForgeAI/%s] Score: %d/10 | Findings: %d | Time: %.1fs%n",
                            id, result.getScore(), result.getFindings().size(),
                            result.getDurationMs() / 1000.0);
                } catch (Exception e) {
                    log.println("[ForgeAI/" + id + "] ERROR: " + e.getMessage());
                }
            }

            // ── Generate HTML Report ──
            if (cfg.isPublishHtmlReport() && !results.isEmpty()) {
                String projectName = env.getOrDefault("JOB_NAME", "unknown");
                String buildNum = env.getOrDefault("BUILD_NUMBER", "0");
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

            for (AnalysisResult r : results) {
                output.put(r.getAnalyzerId() + "Score", r.getScore());
            }

            log.println("\n╔══════════════════════════════════════════════════╗");
            log.printf( "║   Composite Score:  %.1f / 10                     ║%n", composite);
            log.printf( "║   Total Findings:   %-5d                        ║%n",
                    output.get("totalFindings"));
            log.println("╚══════════════════════════════════════════════════╝");

            // ── Fail build if threshold not met ──
            boolean shouldFail = step.isFailOnCritical()
                    || (cfg.isFailOnCritical() && step.getCriticalThreshold() <= 0);
            int threshold = step.getCriticalThreshold() > 0
                    ? step.getCriticalThreshold() : cfg.getCriticalThreshold();

            if (shouldFail && composite < threshold) {
                throw new AbortException(
                        "[ForgeAI] BUILD FAILED — Composite score " + String.format("%.1f", composite)
                                + " is below threshold " + threshold);
            }

            return output;
        }

        // ── Helper: collect source files matching glob ────────────────
        private String collectSources(FilePath workspace, String glob, PrintStream log) throws Exception {
            StringBuilder sb = new StringBuilder();
            String[] globs = glob.split(",");
            int fileCount = 0;
            for (String g : globs) {
                for (FilePath file : workspace.list(g.strip())) {
                    if (sb.length() > 200_000) {  // stay within context limits
                        log.println("[ForgeAI] Source collection truncated at 200KB");
                        break;
                    }
                    sb.append("\n=== FILE: ").append(file.getRemote()).append(" ===\n");
                    sb.append(file.readToString());
                    fileCount++;
                }
            }
            log.println("[ForgeAI] Collected " + fileCount + " source files ("
                    + (sb.length() / 1024) + " KB)");
            return sb.toString();
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
            StringBuilder sb = new StringBuilder();
            for (String g : manifestGlobs) {
                for (FilePath f : workspace.list(g)) {
                    if (sb.length() > 100_000) break;
                    sb.append("\n=== ").append(f.getName()).append(" ===\n");
                    sb.append(f.readToString());
                }
            }
            if (sb.length() == 0) sb.append("(No dependency manifests found)");
            return sb.toString();
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
            return Set.of(TaskListener.class, FilePath.class, Run.class, EnvVars.class);
        }
    }
}
