package io.forgeai.jenkins.actions;

import hudson.model.Run;
import io.forgeai.jenkins.reports.AnalysisResult;
import io.forgeai.jenkins.reports.ForgeAIReportGenerator;
import jenkins.model.RunAction2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The result of a {@code forgeAI} step, kept with the build. It renders the analysis as a page of the build
 * ({@code forgeai-analysis/}), styled by Jenkins itself, and feeds the score trend on the job page.
 *
 * <p>When a build calls {@code forgeAI} more than once, the result of the last call is kept.
 */
public class ForgeAIBuildAction implements RunAction2 {

    public static final String URL_NAME = "forgeai-analysis";
    static final String ICON = "symbol-flame-outline plugin-ionicons-api";

    private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private final List<AnalysisResult> results;
    private final List<String> failedAnalyzers;
    private final String analysisScope;
    private final int filesAnalyzed;
    private final int filesSkipped;
    private final boolean gateEnabled;
    private final int threshold;

    private transient Run<?, ?> run;

    public ForgeAIBuildAction(List<AnalysisResult> results, List<String> failedAnalyzers, String analysisScope,
                              int filesAnalyzed, int filesSkipped, boolean gateEnabled, int threshold) {
        this.results = new ArrayList<>();
        for (AnalysisResult r : results) {
            this.results.add(withoutRawResponse(r));
        }
        this.failedAnalyzers = new ArrayList<>(failedAnalyzers);
        this.analysisScope = analysisScope;
        this.filesAnalyzed = filesAnalyzed;
        this.filesSkipped = filesSkipped;
        this.gateEnabled = gateEnabled;
        this.threshold = threshold;
    }

    /** The raw LLM response can be large and is already in the HTML report, so the build record leaves it out. */
    private static AnalysisResult withoutRawResponse(AnalysisResult r) {
        AnalysisResult copy = new AnalysisResult(r.getAnalyzerId(), r.getAnalyzerName());
        copy.setScore(r.getScore());
        copy.setSeverity(r.getSeverity());
        copy.setSummary(r.getSummary());
        copy.setDurationMs(r.getDurationMs());
        copy.setParseFailed(r.isParseFailed());
        r.getFindings().forEach(copy::addFinding);
        return copy;
    }

    // ── Action ────────────────────────────────────────────────────────

    @Override public String getIconFileName() { return ICON; }
    @Override public String getDisplayName() { return "ForgeAI Analysis"; }
    @Override public String getUrlName() { return URL_NAME; }

    @Override public void onAttached(Run<?, ?> r) { this.run = r; }
    @Override public void onLoad(Run<?, ?> r) { this.run = r; }

    public Run<?, ?> getRun() { return run; }

    // ── Scores ────────────────────────────────────────────────────────

    /** False when every analyzer failed, so there is no score to show. */
    public boolean isScored() { return !results.isEmpty(); }

    /** Average of the analyzer scores, or 0 when every analyzer failed. */
    public double getCompositeScore() {
        return results.stream().mapToInt(AnalysisResult::getScore).average().orElse(0);
    }

    public String getCompositeScoreText() {
        return isScored() ? String.format(Locale.ROOT, "%.1f", getCompositeScore()) : "–";
    }

    public String getVerdict() {
        return isScored() ? ForgeAIReportGenerator.verdict(getCompositeScore()) : "NO RESULT";
    }

    /** CSS modifier for the verdict: ship, caution, hold, block, or none. */
    public String getVerdictKey() {
        return isScored() ? band(getCompositeScore()) : "none";
    }

    public static String band(double score) {
        if (score >= 8) return "ship";
        if (score >= 6) return "caution";
        if (score >= 4) return "hold";
        return "block";
    }

    public long getTotalFindings() {
        return results.stream().mapToLong(r -> r.getFindings().size()).sum();
    }

    public long getCriticalCount() {
        return results.stream().mapToLong(r -> r.countBySeverity("CRITICAL")).sum();
    }

    public long getHighCount() {
        return results.stream().mapToLong(r -> r.countBySeverity("HIGH")).sum();
    }

    public List<AnalysisResult> getResults() { return Collections.unmodifiableList(results); }
    public List<String> getFailedAnalyzers() { return Collections.unmodifiableList(failedAnalyzers); }
    public String getFailedAnalyzersText() { return String.join(", ", failedAnalyzers); }
    public String getAnalysisScope() { return analysisScope; }
    public int getFilesAnalyzed() { return filesAnalyzed; }
    public int getFilesSkipped() { return filesSkipped; }
    public boolean isGateEnabled() { return gateEnabled; }
    public int getThreshold() { return threshold; }

    public String getScopeText() {
        String files = filesAnalyzed + " source file(s) analyzed";
        if (filesSkipped > 0) files += ", " + filesSkipped + " did not fit";
        return ("changes".equals(analysisScope) ? "Changed files only: " : "All matching files: ") + files;
    }

    /** PASSED, FAILED or OFF, matching what the step did with the build. */
    public String getGateStatus() {
        if (!gateEnabled) return "OFF";
        if (!failedAnalyzers.isEmpty()) return "FAILED";
        if (!isScored()) return "OFF";
        return getCompositeScore() < threshold ? "FAILED" : "PASSED";
    }

    public String getGateText() {
        switch (getGateStatus()) {
            case "PASSED":
                return "Quality gate passed (threshold " + threshold + ")";
            case "FAILED":
                return failedAnalyzers.isEmpty()
                        ? "Quality gate failed: score below " + threshold
                        : "Quality gate failed: not every analyzer produced a result";
            default:
                return "Quality gate off";
        }
    }

    // ── Views ─────────────────────────────────────────────────────────

    public List<AnalyzerRow> getAnalyzerRows() {
        List<AnalyzerRow> rows = new ArrayList<>();
        for (AnalysisResult r : results) rows.add(new AnalyzerRow(r));
        return rows;
    }

    /** Every finding, most severe first, in analyzer order within a severity. */
    public List<FindingRow> getFindingRows() {
        List<FindingRow> rows = new ArrayList<>();
        for (AnalysisResult r : results) {
            for (AnalysisResult.Finding f : r.getFindings()) rows.add(new FindingRow(r.getAnalyzerName(), f));
        }
        rows.sort(Comparator.comparingInt(row -> rank(row.getSeverity())));
        return rows;
    }

    static int rank(String severity) {
        int i = SEVERITIES.indexOf(severity == null ? "" : severity.toUpperCase(Locale.ROOT));
        return i < 0 ? SEVERITIES.size() : i;
    }

    static String severityKey(String severity) {
        int i = rank(severity);
        return i < SEVERITIES.size() ? SEVERITIES.get(i).toLowerCase(Locale.ROOT) : "info";
    }

    /** One analyzer's line in the analyzer table. */
    public static final class AnalyzerRow {
        private final AnalysisResult r;

        AnalyzerRow(AnalysisResult r) { this.r = r; }

        public String getName() { return r.getAnalyzerName(); }
        public int getScore() { return r.getScore(); }
        /** Width of the score bar, out of 100. */
        public int getBarWidth() { return r.getScore() * 10; }
        public String getBand() { return band(r.getScore()); }
        public String getSeverity() { return r.getSeverity() == null ? "INFO" : r.getSeverity(); }
        public String getSeverityKey() { return severityKey(r.getSeverity()); }
        public int getFindingCount() { return r.getFindings().size(); }
        public String getSummary() { return r.getSummary() == null ? "" : r.getSummary(); }
        public boolean isParseFailed() { return r.isParseFailed(); }
    }

    /** One finding, with the analyzer that reported it. */
    public static final class FindingRow {
        private final String analyzerName;
        private final AnalysisResult.Finding f;

        FindingRow(String analyzerName, AnalysisResult.Finding f) {
            this.analyzerName = analyzerName;
            this.f = f;
        }

        public String getAnalyzerName() { return analyzerName; }
        public String getSeverity() {
            return f.getSeverity() == null ? "INFO" : f.getSeverity().toUpperCase(Locale.ROOT);
        }
        public String getSeverityKey() { return severityKey(f.getSeverity()); }
        public String getTitle() { return f.getTitle() == null ? "" : f.getTitle(); }
        public String getDescription() { return f.getDescription() == null ? "" : f.getDescription(); }
        public String getSuggestion() { return f.getSuggestion() == null ? "" : f.getSuggestion(); }

        /** {@code file:line}, {@code file}, or empty when the finding has no location. */
        public String getLocation() {
            String file = f.getFile();
            if (file == null || file.isBlank()) return "";
            return f.getLine() > 0 ? file + ":" + f.getLine() : file;
        }
    }
}
