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

import io.forgeai.jenkins.reports.AnalysisResult;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

/**
 * Granular single-analyzer step.
 *
 * Usage:
 *   def result = forgeAIScan analyzer: 'vulnerability', source: readFile('src/Main.java')
 *   echo "Security score: ${result.score}"
 */
public class ForgeAIScanStep extends Step {

    private String analyzer;
    private String source;
    private String context;

    @DataBoundConstructor
    public ForgeAIScanStep(String analyzer) {
        this.analyzer = analyzer;
        this.source = "";
        this.context = "";
    }

    public String getAnalyzer() { return analyzer; }

    public String getSource() { return source; }
    @DataBoundSetter public void setSource(String v) { this.source = v; }

    public String getContext() { return context; }
    @DataBoundSetter public void setContext(String v) { this.context = v; }

    @Override
    public StepExecution start(StepContext ctx) throws Exception {
        return new Execution(this, ctx);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {
        private static final long serialVersionUID = 1L;
        private final transient ForgeAIScanStep step;

        Execution(ForgeAIScanStep step, StepContext ctx) { super(ctx); this.step = step; }

        @Override
        protected Map<String, Object> run() throws Exception {
            PrintStream log = getContext().get(TaskListener.class).getLogger();
            ForgeAIGlobalConfiguration cfg = ForgeAIGlobalConfiguration.get();
            LLMProvider llm = cfg.getProvider();
            int maxTokens = cfg.getMaxTokens();

            BaseAnalyzer a = switch (step.getAnalyzer()) {
                case "code-review" -> new CodeReviewAnalyzer(llm, log, maxTokens);
                case "vulnerability" -> new VulnerabilityAnalyzer(llm, log, maxTokens);
                case "architecture-drift" -> new ArchitectureDriftAnalyzer(llm, log, maxTokens);
                case "test-gaps" -> new TestGapAnalyzer(llm, log, maxTokens);
                case "dependency-risk" -> new DependencyRiskAnalyzer(llm, log, maxTokens);
                case "commit-intel" -> new CommitIntelligenceAnalyzer(llm, log, maxTokens);
                case "pipeline-advisor" -> new PipelineAdvisorAnalyzer(llm, log, maxTokens);
                case "release-readiness" -> new ReleaseReadinessAnalyzer(llm, log, maxTokens);
                default -> throw new AbortException("[ForgeAI] Unknown analyzer: " + step.getAnalyzer());
            };

            log.println("[ForgeAI] Running: " + a.analyzerName());
            AnalysisResult result = a.analyze(step.getSource(), step.getContext());

            return Map.of(
                    "score", result.getScore(),
                    "severity", result.getSeverity(),
                    "summary", result.getSummary(),
                    "findingsCount", result.getFindings().size(),
                    "criticalCount", result.countBySeverity("CRITICAL"),
                    "highCount", result.countBySeverity("HIGH")
            );
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override public String getFunctionName() { return "forgeAIScan"; }
        @Override public String getDisplayName() { return "ForgeAI Single Analyzer Scan"; }
        @Override public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class, FilePath.class, Run.class, EnvVars.class);
        }
    }
}
