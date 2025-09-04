package app.logic;

import app.core.Config;
import app.core.Paths;
import app.git.GitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * JGit-based collector:
 *  - Clones a repo (bare) to a temp cache dir passed by the caller
 *  - Lists workflow files present at HEAD
 *  - For each workflow, walks commit history that touched that path
 *  - Writes snapshots (.yml.gz by default) and an index JSON
 *  - Idempotent: if the index JSON exists, skips that workflow
 */
public class HistoryGit {
    private final Config cfg;
    private final ObjectMapper om = new ObjectMapper();

    // Toggle gzip for snapshots. If false, writes plain .yml files.
    private static final boolean USE_GZIP = false;

    public HistoryGit(Config cfg) {
        this.cfg = cfg;
    }

    /** Clone/open into 'localDir', collect workflows on defaultBranch, then return. Caller may delete the dir. */
    public void collectRepo(String org, String repo, String httpsUrl, Path localDir, String defaultBranch) throws Exception {
        Files.createDirectories(localDir);
        try (Git git = GitSupport.cloneOrOpen(localDir, httpsUrl, cfg.token(), defaultBranch)) {
            var workflows = listWorkflowFilesAtHead(git);
            if (workflows.isEmpty()) return;

            // Small, bounded parallelism per repo to utilize IO without thrashing
            var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (String workflowPath : workflows) {
                tasks.add(() -> { collectWorkflowHistoryFast(git, org, repo, workflowPath); return null; });
            }
            pool.invokeAll(tasks);
            pool.shutdown();
        }
        // Git closed here → safe for deletion by caller (Windows-friendly).
    }

    /** Head tree walk limited to .github/workflows to find YAML files */
    private static List<String> listWorkflowFilesAtHead(Git git) throws Exception {
        var repo = git.getRepository();
        var headTree = repo.resolve("HEAD^{tree}");
        if (headTree == null) return List.of();

        List<String> result = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(headTree);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(".github/workflows"));
            while (tw.next()) {
                String path = tw.getPathString();
                if (GitSupport.isWorkflowPath(path)) result.add(path);
            }
        }
        return result;
    }

    /** Walks commit history for a given workflow path, writes snapshots + index. */
    private void collectWorkflowHistoryFast(Git git, String org, String repo, String workflowPath) throws Exception {
        var indexPath = Paths.workflowIndexPath(cfg, org, repo, workflowPath);
        if (Files.exists(indexPath)) return; // idempotent resume

        // Get commits that touched this path (newest first)
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit c : git.log().addPath(workflowPath).call()) commits.add(c);

        var repoObj = git.getRepository();
        var commitSummaries = new ArrayList<Map<String, Object>>();
        String lastHash = null;

        try (ObjectReader reader = repoObj.newObjectReader()) {
            for (RevCommit c : commits) {
                String sha = c.getName();
                String content = readFileAtCommit(git, workflowPath, c);
                if (content == null) continue; // file not present in this commit

                String hash = sha1(content);
                Path out = outPathForSnapshot(org, repo, workflowPath, sha);
                Files.createDirectories(out.getParent());

                // Write only if content changed or file missing
                if (!hash.equals(lastHash) || !Files.exists(out)) {
                    if (USE_GZIP) {
                        try (var os = new GZIPOutputStream(Files.newOutputStream(out))) {
                            os.write(content.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        Files.writeString(out, content, StandardCharsets.UTF_8);
                    }
                    lastHash = hash;
                }

                var cs = new LinkedHashMap<String, Object>();
                cs.put("sha", sha);
                cs.put("date", Instant.ofEpochSecond(c.getCommitTime()).toString());
                cs.put("message", c.getFullMessage());
                cs.put("content_hash", hash);
                cs.put("raw_snapshot_relpath",
                        Path.of(cfg.outDir()).toAbsolutePath().relativize(out.toAbsolutePath()).toString());
                commitSummaries.add(cs);
            }
        }

        var index = new LinkedHashMap<String, Object>();
        index.put("org", org);
        index.put("repo", repo);
        index.put("workflow_path", workflowPath);
        index.put("nb_commits", commitSummaries.size());
        if (!commitSummaries.isEmpty()) {
            index.put("last_commit_date", commitSummaries.get(0).get("date"));
            index.put("first_commit_date", commitSummaries.get(commitSummaries.size() - 1).get("date"));
        }
        index.put("commits", commitSummaries);
        index.put("collected_at", Instant.now().toString());
        Files.createDirectories(indexPath.getParent());
        om.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index);
    }

    /** Reads a file’s content at a specific commit; returns null if not present. */
    private static String readFileAtCommit(Git git, String path, RevCommit commit) throws Exception {
        var repo = git.getRepository();
        var tree = commit.getTree();

        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            tw.setFilter(PathFilter.create(path));
            if (!tw.next()) return null;
            var blobId = tw.getObjectId(0);
            try (var reader = repo.newObjectReader()) {
                var loader = reader.open(blobId);
                return new String(loader.getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private Path outPathForSnapshot(String org, String repo, String workflowPath, String sha, boolean dummy) {
        // Build the output path manually using cfg.outDir()
        Path base = Path.of(cfg.outDir(), "raw", org, repo).resolve(workflowPath);
        String fileName = USE_GZIP ? (sha + ".yml.gz") : (sha + ".yml");
        return base.resolve(fileName);
    }

    // Overload actually used above (fixes the placeholder misuse)
    private Path outPathForSnapshot(String org, String repo, String workflowPath, String sha) {
        Path base = Path.of(cfg.outDir(), "raw", org, repo).resolve(workflowPath);
        String fileName = USE_GZIP ? (sha + ".yml.gz") : (sha + ".yml");
        return base.resolve(fileName);
    }

    private static String sha1(String s) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-1");
        return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
