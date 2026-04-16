package io.forgeai.jenkins.reports;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Generates a self-contained HTML report from aggregated analysis results.
 * The report is saved as a build artifact and linked from the build page.
 */
public final class ForgeAIReportGenerator {

    private ForgeAIReportGenerator() {}

    public static String generateHtml(List<AnalysisResult> results, String projectName,
                                       String buildNumber) {
        StringBuilder html = new StringBuilder();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date());

        // Calculate composite score
        double compositeScore = results.stream()
                .mapToInt(AnalysisResult::getScore)
                .average().orElse(5.0);

        long totalFindings = results.stream()
                .mapToLong(r -> r.getFindings().size()).sum();
        long criticalCount = results.stream()
                .mapToLong(r -> r.countBySeverity("CRITICAL")).sum();
        long highCount = results.stream()
                .mapToLong(r -> r.countBySeverity("HIGH")).sum();

        String verdict = compositeScore >= 8 ? "SHIP IT" :
                compositeScore >= 6 ? "CAUTION" :
                        compositeScore >= 4 ? "HOLD" : "BLOCK";
        String verdictColor = compositeScore >= 8 ? "#22c55e" :
                compositeScore >= 6 ? "#eab308" :
                        compositeScore >= 4 ? "#f97316" : "#ef4444";

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>ForgeAI Report — %s #%s</title>
            <style>
              :root { --bg: #0f172a; --surface: #1e293b; --border: #334155;
                      --text: #e2e8f0; --muted: #94a3b8; --accent: #6366f1; }
              * { margin: 0; padding: 0; box-sizing: border-box; }
              body { font-family: 'Segoe UI', system-ui, sans-serif; background: var(--bg);
                     color: var(--text); line-height: 1.6; padding: 2rem; }
              .container { max-width: 1100px; margin: 0 auto; }
              .header { display: flex; align-items: center; justify-content: space-between;
                        margin-bottom: 2rem; padding-bottom: 1rem; border-bottom: 1px solid var(--border); }
              .header h1 { font-size: 1.5rem; }
              .header h1 span { color: var(--accent); }
              .meta { color: var(--muted); font-size: 0.85rem; }
              .score-ring { width: 120px; height: 120px; position: relative; }
              .score-ring svg { transform: rotate(-90deg); }
              .score-ring .value { position: absolute; inset: 0; display: flex;
                                   align-items: center; justify-content: center;
                                   font-size: 2rem; font-weight: 700; }
              .kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                         gap: 1rem; margin-bottom: 2rem; }
              .kpi { background: var(--surface); border-radius: 12px; padding: 1.2rem;
                     border: 1px solid var(--border); }
              .kpi .label { font-size: 0.75rem; color: var(--muted); text-transform: uppercase;
                            letter-spacing: 0.05em; }
              .kpi .value { font-size: 1.8rem; font-weight: 700; margin-top: 0.25rem; }
              .verdict { color: %s; }
              .section { background: var(--surface); border-radius: 12px; padding: 1.5rem;
                         margin-bottom: 1.5rem; border: 1px solid var(--border); }
              .section h2 { font-size: 1.1rem; margin-bottom: 0.5rem; display: flex;
                            align-items: center; gap: 0.5rem; }
              .section .score-badge { display: inline-block; padding: 0.15rem 0.6rem;
                                      border-radius: 999px; font-size: 0.75rem; font-weight: 600; }
              .finding { padding: 0.8rem; margin-top: 0.8rem; border-radius: 8px;
                         border-left: 4px solid var(--border); background: rgba(255,255,255,0.02); }
              .finding h3 { font-size: 0.9rem; }
              .finding p { font-size: 0.85rem; color: var(--muted); margin-top: 0.3rem; }
              .finding .suggestion { background: rgba(99,102,241,0.1); padding: 0.5rem 0.8rem;
                                     border-radius: 6px; margin-top: 0.5rem; font-size: 0.8rem; }
              .sev-CRITICAL { border-color: #ef4444; }  .sev-CRITICAL h3 { color: #ef4444; }
              .sev-HIGH     { border-color: #f97316; }  .sev-HIGH h3     { color: #f97316; }
              .sev-MEDIUM   { border-color: #eab308; }  .sev-MEDIUM h3   { color: #eab308; }
              .sev-LOW      { border-color: #22c55e; }  .sev-LOW h3      { color: #22c55e; }
              .sev-INFO     { border-color: #6366f1; }  .sev-INFO h3     { color: #6366f1; }
              .footer { text-align: center; color: var(--muted); font-size: 0.75rem;
                        margin-top: 2rem; padding-top: 1rem; border-top: 1px solid var(--border); }
            </style>
            </head>
            <body>
            <div class="container">
            <div class="header">
              <div>
                <h1>🔥 <span>ForgeAI</span> Pipeline Intelligence Report</h1>
                <div class="meta">%s &bull; Build #%s &bull; %s</div>
              </div>
            </div>
            """.formatted(projectName, buildNumber, verdictColor, projectName, buildNumber, timestamp));

        // KPI Row
        html.append("""
            <div class="kpi-row">
              <div class="kpi"><div class="label">Composite Score</div>
                <div class="value">%.1f<span style="font-size:0.9rem;color:var(--muted)">/10</span></div></div>
              <div class="kpi"><div class="label">Verdict</div>
                <div class="value verdict">%s</div></div>
              <div class="kpi"><div class="label">Total Findings</div>
                <div class="value">%d</div></div>
              <div class="kpi"><div class="label">Critical / High</div>
                <div class="value" style="color:#ef4444">%d / %d</div></div>
              <div class="kpi"><div class="label">Analyzers Run</div>
                <div class="value">%d</div></div>
            </div>
            """.formatted(compositeScore, verdict, totalFindings, criticalCount, highCount, results.size()));

        // Individual analyzer sections
        for (AnalysisResult r : results) {
            String badgeColor = r.getScore() >= 8 ? "#22c55e" : r.getScore() >= 5 ? "#eab308" : "#ef4444";
            html.append("""
                <div class="section">
                  <h2>%s
                    <span class="score-badge" style="background:%s;color:#000">%d/10</span>
                  </h2>
                  <p style="color:var(--muted);font-size:0.9rem">%s</p>
                """.formatted(r.getAnalyzerName(), badgeColor, r.getScore(), escHtml(r.getSummary())));

            for (AnalysisResult.Finding f : r.getFindings()) {
                html.append("""
                    <div class="finding sev-%s">
                      <h3>[%s] %s</h3>
                      <p>%s</p>
                    """.formatted(f.getSeverity(), f.getSeverity(), escHtml(f.getTitle()),
                        escHtml(f.getDescription())));
                if (f.getFile() != null && !f.getFile().isBlank()) {
                    html.append("<p style='font-size:0.8rem'>📄 " + escHtml(f.getFile()));
                    if (f.getLine() > 0) html.append(" (line " + f.getLine() + ")");
                    html.append("</p>");
                }
                if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
                    html.append("<div class='suggestion'>💡 " + escHtml(f.getSuggestion()) + "</div>");
                }
                html.append("</div>");
            }
            html.append("</div>");
        }

        html.append("""
            <div class="footer">
              Generated by ForgeAI Pipeline Intelligence v1.0 &bull; Open Source &bull; MIT License
            </div>
            </div></body></html>
            """);

        return html.toString();
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
