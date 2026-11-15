package io.forgeai.jenkins.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import jenkins.model.TransientActionFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ForgeAI on the job page: a chart of the composite score over recent builds, and a page listing them.
 * Added to any job whose recent builds ran {@code forgeAI}.
 */
public class ForgeAIJobAction implements Action {

    public static final String URL_NAME = "forgeai-trend";

    /** Builds shown in the trend. */
    static final int MAX_BUILDS = 30;

    /** Builds loaded to find them, so a long history without ForgeAI does not load every build. */
    static final int MAX_SCANNED = 100;

    /** How many of the latest builds the job page looks at to decide whether to show ForgeAI. */
    static final int LOOKBACK = 5;

    private final Job<?, ?> job;

    public ForgeAIJobAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override public String getIconFileName() { return ForgeAIBuildAction.ICON; }
    @Override public String getDisplayName() { return "ForgeAI Trend"; }
    @Override public String getUrlName() { return URL_NAME; }

    public Job<?, ?> getJob() { return job; }

    /** For views rendered with the job as context, such as the job page's floating box. */
    public static ForgeAIJobAction of(Job<?, ?> job) {
        return new ForgeAIJobAction(job);
    }

    /** The most recent builds that ran ForgeAI, oldest first. Looks at no more than the last 100 builds. */
    public List<Point> getTrend() {
        List<Point> points = new ArrayList<>();
        int scanned = 0;
        for (Run<?, ?> r : job.getBuilds()) {
            if (points.size() >= MAX_BUILDS || ++scanned > MAX_SCANNED) break;
            ForgeAIBuildAction a = r.getAction(ForgeAIBuildAction.class);
            if (a != null && a.isScored()) points.add(new Point(r, a));
        }
        Collections.reverse(points);
        return points;
    }

    /** The trend, newest first, for the history table. */
    public List<Point> getHistory() {
        List<Point> points = getTrend();
        Collections.reverse(points);
        return points;
    }

    public Chart getChart() {
        List<Point> trend = getTrend();
        Integer threshold = null;
        if (!trend.isEmpty()) {
            ForgeAIBuildAction latest = trend.get(trend.size() - 1).action;
            if (latest.isGateEnabled()) threshold = latest.getThreshold();
        }
        return new Chart(trend, threshold);
    }

    /** One build in the trend. */
    public static final class Point {
        private final Run<?, ?> run;
        private final ForgeAIBuildAction action;
        private int x;
        private int y;

        Point(Run<?, ?> run, ForgeAIBuildAction action) {
            this.run = run;
            this.action = action;
        }

        public int getNumber() { return run.getNumber(); }
        public String getDisplayName() { return run.getDisplayName(); }
        /** Relative to the Jenkins root URL. */
        public String getUrl() { return run.getUrl() + ForgeAIBuildAction.URL_NAME + "/"; }
        public double getScore() { return action.getCompositeScore(); }
        public String getScoreText() { return action.getCompositeScoreText(); }
        public String getVerdict() { return action.getVerdict(); }
        public String getBand() { return action.getVerdictKey(); }
        public long getFindings() { return action.getTotalFindings(); }
        public long getCritical() { return action.getCriticalCount(); }
        public String getGateStatus() { return action.getGateStatus(); }
        public String getScope() { return action.getAnalysisScope(); }
        public int getX() { return x; }
        public int getY() { return y; }
    }

    /** Geometry of the trend chart, in SVG user units, so the view only places what is computed here. */
    public static final class Chart {
        public static final int WIDTH = 520;
        public static final int HEIGHT = 180;
        public static final int LEFT = 30;
        public static final int RIGHT = WIDTH - 12;
        public static final int TOP = 12;
        public static final int BOTTOM = HEIGHT - 24;

        private final List<Point> points;
        private final Integer threshold;

        Chart(List<Point> points, Integer threshold) {
            this.points = points;
            this.threshold = threshold;
            int n = points.size();
            for (int i = 0; i < n; i++) {
                Point p = points.get(i);
                p.x = n == 1 ? (LEFT + RIGHT) / 2 : LEFT + (int) Math.round((RIGHT - LEFT) * (double) i / (n - 1));
                p.y = y(p.getScore());
            }
        }

        static int y(double score) {
            double clamped = Math.max(0, Math.min(10, score));
            return BOTTOM - (int) Math.round((BOTTOM - TOP) * clamped / 10);
        }

        public int getWidth() { return WIDTH; }
        public int getHeight() { return HEIGHT; }
        public int getLeft() { return LEFT; }
        public int getRight() { return RIGHT; }
        public int getBottom() { return BOTTOM; }
        public List<Point> getPoints() { return points; }
        public boolean isEmpty() { return points.isEmpty(); }

        /** {@code x,y x,y ...} for an SVG polyline. */
        public String getPolyline() {
            StringBuilder sb = new StringBuilder();
            for (Point p : points) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p.x).append(',').append(p.y);
            }
            return sb.toString();
        }

        /** Horizontal grid lines at 0, 2, 4, 6, 8 and 10. */
        public List<Grid> getGrid() {
            List<Grid> grid = new ArrayList<>();
            for (int s = 0; s <= 10; s += 2) grid.add(new Grid(y(s), String.valueOf(s)));
            return grid;
        }

        public boolean isHasThreshold() { return threshold != null && !points.isEmpty(); }
        public int getThresholdY() { return threshold == null ? BOTTOM : y(threshold); }
        public String getThresholdLabel() { return threshold == null ? "" : "gate " + threshold; }

        public String getFirstLabel() { return points.isEmpty() ? "" : "#" + points.get(0).getNumber(); }
        public String getLastLabel() {
            return points.size() < 2 ? "" : "#" + points.get(points.size() - 1).getNumber();
        }

        public String getSummary() {
            if (points.isEmpty()) return "No ForgeAI scores yet.";
            Point last = points.get(points.size() - 1);
            return String.format(Locale.ROOT, "Composite score of the last %d build(s); latest %s/10 in #%d.",
                    points.size(), last.getScoreText(), last.getNumber());
        }
    }

    /** One horizontal grid line of the chart. */
    public static final class Grid {
        private final int y;
        private final String label;

        Grid(int y, String label) {
            this.y = y;
            this.label = label;
        }

        public int getY() { return y; }
        public int getLabelY() { return y + 3; }
        public String getLabel() { return label; }
    }

    /** Shows ForgeAI on jobs whose latest builds ran it. */
    @Extension
    @SuppressWarnings("rawtypes")
    public static final class Factory extends TransientActionFactory<Job> {
        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @NonNull
        @Override
        public Collection<? extends Action> createFor(@NonNull Job target) {
            int seen = 0;
            for (Run<?, ?> r = target.getLastBuild(); r != null && seen < LOOKBACK; r = r.getPreviousBuild(), seen++) {
                if (r.getAction(ForgeAIBuildAction.class) != null) {
                    return List.of(new ForgeAIJobAction((Job<?, ?>) target));
                }
            }
            return List.of();
        }
    }
}
