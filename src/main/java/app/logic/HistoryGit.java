package app.logic;

import app.core.Config;
import app.core.Paths;
import app.git.GitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class HistoryGit {
    private final Config cfg;
    private final ObjectMapper om = new ObjectMapper();

    public HistoryGit(Config cfg) { this.cfg = cfg; }

    /** Clone/open into 'localDir', collect workflows, then return. (Caller may delete the dir.) */
    public void collectRepo(String org, String repo, String httpsUrl, Path localDir) throws Exception {
        Files.createDirectories(localDir);
        try (Git git = GitSupport.cloneOrOpen(localDir, httpsUrl, cfg.token())) {
            var workflows = app.git.GitSupport.listWorkflowFilesAtHead(git);
            if (workflows.isEmpty()) return;
            for (String workflowPath : workflows) {
                collectWorkflowHistory(git, org, repo, workflowPath);
            }
        }
        // IMPORTANT: Git is closed here; safe for deletion by caller.
    }

    private void collectWorkflowHistory(Git git, String org, String repo, String workflowPath) throws Exception {
        var indexPath = Paths.workflowIndexPath(cfg, org, repo, workflowPath);
        if (Files.exists(indexPath)) return; // idempotent resume

        List<RevCommit> commits = app.git.GitSupport.commitsForPath(git, workflowPath); // newest first
        List<Map<String, Object>> commitSummaries = new ArrayList<>();

        String lastHash = null;
        for (RevCommit c : commits) {
            String sha = c.getName();
            String content = app.git.GitSupport.readFileAtCommit(git, workflowPath, c);
            if (content == null) continue;

            String hash = sha1(content);
            Path out = Paths.rawSnapshotPath(cfg, org, repo, workflowPath, sha);
            Files.createDirectories(out.getParent());
            if (!hash.equals(lastHash) || !Files.exists(out)) {
                Files.writeString(out, content);
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

    private static String sha1(String s) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-1");
        return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
