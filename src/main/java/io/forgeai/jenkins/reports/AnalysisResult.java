package io.forgeai.jenkins.reports;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured result from any ForgeAI analyzer.
 * Used to aggregate across analyzers and render the final HTML report.
 */
public class AnalysisResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String analyzerId;
    private final String analyzerName;
    private int score;                  // 1-10
    private String severity;            // CRITICAL | HIGH | MEDIUM | LOW | INFO
    private String summary;
    private String rawMarkdown;
    private final List<Finding> findings = new ArrayList<>();
    private long durationMs;

    public AnalysisResult(String analyzerId, String analyzerName) {
        this.analyzerId = analyzerId;
        this.analyzerName = analyzerName;
    }

    // ── Finding inner class ────────────────────────────────────────────
    public static class Finding implements Serializable {
        private static final long serialVersionUID = 1L;
        private String title;
        private String description;
        private String severity; // CRITICAL | HIGH | MEDIUM | LOW | INFO
        private String file;
        private int line;
        private String suggestion;

        public Finding(String title, String severity, String description) {
            this.title = title;
            this.severity = severity;
            this.description = description;
        }

        // Getters & setters
        public String getTitle() { return title; }
        public void setTitle(String v) { this.title = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public String getSeverity() { return severity; }
        public void setSeverity(String v) { this.severity = v; }
        public String getFile() { return file; }
        public void setFile(String v) { this.file = v; }
        public int getLine() { return line; }
        public void setLine(int v) { this.line = v; }
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String v) { this.suggestion = v; }
    }

    // ── Convenience ────────────────────────────────────────────────────
    public void addFinding(Finding f) { findings.add(f); }

    public long countBySeverity(String sev) {
        return findings.stream().filter(f -> sev.equalsIgnoreCase(f.getSeverity())).count();
    }

    // ── Getters & Setters ──────────────────────────────────────────────
    public String getAnalyzerId() { return analyzerId; }
    public String getAnalyzerName() { return analyzerName; }
    public int getScore() { return score; }
    public void setScore(int v) { this.score = Math.max(1, Math.min(10, v)); }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { this.severity = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getRawMarkdown() { return rawMarkdown; }
    public void setRawMarkdown(String v) { this.rawMarkdown = v; }
    public List<Finding> getFindings() { return findings; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long v) { this.durationMs = v; }
}
