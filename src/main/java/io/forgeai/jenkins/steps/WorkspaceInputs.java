package io.forgeai.jenkins.steps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import io.forgeai.jenkins.analyzers.CommitIntelligenceAnalyzer;
import io.forgeai.jenkins.analyzers.DependencyRiskAnalyzer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads what the analyzers need from the build workspace: source files, the Jenkinsfile,
 * dependency manifests and git history. All file and git access for the forgeAI step lives here.
 */
final class WorkspaceInputs {

    /** Source packed into one or more LLM-request-sized chunks, plus what was left out. */
    record Sources(List<String> chunks, int filesIncluded, int filesSkipped) {}

    private static final String[] MANIFEST_GLOBS = {
            "**/pom.xml", "**/build.gradle", "**/build.gradle.kts",
            "**/package.json", "**/requirements.txt", "**/Pipfile",
            "**/go.mod", "**/Cargo.toml", "**/Gemfile", "**/composer.json"
    };

    /** A git revision we are willing to pass on a command line: no leading dash, no whitespace. */
    private static final Pattern SAFE_REF = Pattern.compile("[^\\s-]\\S*");

    private final FilePath workspace;
    private final Launcher launcher;
    private final EnvVars env;
    private final PrintStream log;

    WorkspaceInputs(FilePath workspace, Launcher launcher, EnvVars env, PrintStream log) {
        this.workspace = workspace;
        this.launcher = launcher;
        this.env = env;
        this.log = log;
    }

    // ── Change scope ──────────────────────────────────────────────────

    /**
     * Revisions the build could be compared against, most specific first: an explicit {@code baseRef},
     * else the pull request's target branch, else (when asked) the previous build's commit.
     */
    List<String> changeBaseCandidates(String explicitBaseRef, boolean includePreviousBuild) {
        List<String> candidates = new ArrayList<>();
        if (notBlank(explicitBaseRef)) {
            candidates.add(explicitBaseRef.strip());
            return candidates;
        }
        String target = env.get("CHANGE_TARGET");
        if (notBlank(target)) {
            candidates.add("origin/" + target.strip());
            candidates.add(target.strip());
        }
        if (includePreviousBuild) {
            for (String var : List.of("GIT_PREVIOUS_SUCCESSFUL_COMMIT", "GIT_PREVIOUS_COMMIT")) {
                String sha = env.get(var);
                if (notBlank(sha)) candidates.add(sha.strip());
            }
        }
        return candidates;
    }

    /** Returns the first candidate that exists in the workspace's git repository, or {@code null}. */
    String resolveChangeBase(List<String> candidates) throws InterruptedException {
        for (String ref : candidates) {
            if (SAFE_REF.matcher(ref).matches() && runGit("rev-parse", "--verify", "--quiet", ref) != null) {
                return ref;
            }
        }
        return null;
    }

    /**
     * Workspace-relative paths (with {@code /} separators) added, copied, modified or renamed since
     * {@code base}, or {@code null} when git cannot tell.
     */
    Set<String> changedFiles(String base) throws InterruptedException {
        // Three-dot compares against the merge base, which is what a pull request shows. It needs
        // shared history; fall back to a plain two-point diff for shallow clones.
        String out = runGit("diff", "--name-only", "--relative", "--diff-filter=ACMR", "-z", base + "...HEAD");
        if (out == null) {
            out = runGit("diff", "--name-only", "--relative", "--diff-filter=ACMR", "-z", base, "HEAD");
        }
        if (out == null) return null;
        Set<String> files = new LinkedHashSet<>();
        for (String path : out.split("\0")) {
            if (!path.isBlank()) files.add(path);
        }
        return files;
    }

    // ── Files ─────────────────────────────────────────────────────────

    /**
     * Collects whole files matching the patterns into chunks of at most {@code budget} characters, so an
     * analyzer sees every file it is given instead of having the middle silently truncated. At most
     * {@code maxChunks} chunks are produced; files that do not fit are skipped and reported in the build log.
     *
     * @param only when not null, only these workspace-relative paths are included
     */
    Sources collect(String[] patterns, Set<String> only, int budget, int maxChunks, String kind)
            throws IOException, InterruptedException {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Set<String> seen = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        int included = 0;
        for (String g : patterns) {
            String pattern = g.strip();
            if (pattern.isEmpty()) continue;
            for (FilePath file : workspace.list(pattern)) {
                if (!seen.add(file.getRemote())) continue;
                String rel = relativePath(file);
                if (only != null && !only.contains(rel)) continue;
                String header = "\n=== FILE: " + rel + " ===\n";
                // length() is in bytes: an upper bound on chars that avoids reading files that cannot fit
                long size = header.length() + file.length();
                if (size > budget) {
                    skipped.add(rel); // larger than a whole request on its own
                    continue;
                }
                if (current.length() + size > budget) {
                    if (chunks.size() + 1 >= maxChunks) {
                        skipped.add(rel); // no chunk left to start; smaller files may still fit
                        continue;
                    }
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                current.append(header).append(file.readToString());
                included++;
            }
        }
        if (current.length() > 0) chunks.add(current.toString());

        log.println("[ForgeAI] Included " + included + " " + kind + " file(s) in the analysis"
                + (chunks.size() > 1 ? ", split across " + chunks.size() + " requests per analyzer." : "."));
        if (!skipped.isEmpty()) {
            log.println("[ForgeAI] WARNING: " + skipped.size() + " " + kind + " file(s) did not fit in the analysis budget ("
                    + maxChunks + " x " + budget + " characters) and were NOT analyzed: "
                    + String.join(", ", skipped.subList(0, Math.min(10, skipped.size())))
                    + (skipped.size() > 10 ? ", ..." : "")
                    + ". Narrow the glob, analyze only changes, or raise Max Source Chunks.");
        }
        return new Sources(chunks, included, skipped.size());
    }

    /** Dependency manifests in one request, or a placeholder when the workspace has none. */
    String manifests() throws IOException, InterruptedException {
        Sources manifests = collect(MANIFEST_GLOBS, null, DependencyRiskAnalyzer.MANIFEST_CHAR_LIMIT, 1, "manifest");
        return manifests.chunks().isEmpty() ? "(No dependency manifests found)" : manifests.chunks().get(0);
    }

    String jenkinsfile() {
        try {
            FilePath jf = new FilePath(workspace, "Jenkinsfile");
            if (jf.exists()) return jf.readToString();
            // Try .jenkins/Jenkinsfile
            jf = new FilePath(workspace, ".jenkins/Jenkinsfile");
            if (jf.exists()) return jf.readToString();
        } catch (IOException | InterruptedException e) {
            log.println("[ForgeAI] Could not read Jenkinsfile: " + e.getMessage());
        }
        return "(Jenkinsfile not found in workspace)";
    }

    /** Top-level workspace listing, used to give analyzers a sense of the project layout. */
    String directoryTree() {
        try {
            return workspace.act(new DirectoryTreeCallable());
        } catch (IOException | InterruptedException e) {
            return "(could not list)";
        }
    }

    // ── Git history ───────────────────────────────────────────────────

    /**
     * Commit messages and a diff for commit-intel, or {@code null} when git history is unavailable.
     * With a {@code base}, covers exactly the commits since it; otherwise the last 20 commits and the HEAD diff.
     */
    String commitHistory(String base) throws InterruptedException {
        String commits = null;
        String diff = null;
        boolean sinceBase = false;
        if (base != null) {
            commits = runGit("log", "-n", "50", "--stat", "--no-color", base + "..HEAD");
            if (commits != null && !commits.isBlank()) {
                sinceBase = true;
                diff = runGit("diff", "--no-color", base + "...HEAD");
            }
        }
        if (!sinceBase) {
            // No base, or nothing new since it: describe recent history instead
            commits = runGit("log", "-n", "20", "--stat", "--no-color");
            if (commits == null || commits.isBlank()) return null;
            diff = runGit("show", "--no-color", "--format=", "HEAD");
        }
        String diffTitle = sinceBase ? "DIFF AGAINST " + base : "DIFF OF HEAD COMMIT";

        StringBuilder sb = new StringBuilder(sinceBase ? "=== COMMITS SINCE " + base + " ===\n" : "=== RECENT COMMITS ===\n")
                .append(commits.strip());
        if (diff != null && !diff.isBlank()) {
            // Cut the diff rather than letting head/tail truncation drop the commit messages
            int room = CommitIntelligenceAnalyzer.COMMIT_CHAR_LIMIT - sb.length() - 100;
            if (room > 0) {
                String d = diff.strip();
                sb.append("\n\n=== ").append(diffTitle).append(" ===\n")
                        .append(d.length() > room ? d.substring(0, room) + "\n... [diff truncated]" : d);
            }
        }
        return sb.toString();
    }

    private String runGit(String... args) throws InterruptedException {
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

    private String relativePath(FilePath file) {
        String ws = workspace.getRemote();
        String path = file.getRemote();
        String rel = path.startsWith(ws) ? path.substring(ws.length()).replaceFirst("^[/\\\\]+", "") : path;
        return rel.replace('\\', '/');
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
