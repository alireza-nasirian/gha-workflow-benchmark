package app.logic;

import app.core.Config;
import app.core.Paths;
import app.github.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.nio.file.Path;


public class History {
    private final Config cfg;
    private final GitHubClient gh;
    private final ObjectMapper om = new ObjectMapper();

    public History(Config cfg, GitHubClient gh) { this.cfg = cfg; this.gh = gh; }

    public void collectWorkflowHistory(String org, String repo, String workflowPath, String defaultBranch) throws Exception {
        var commits = gh.listCommitsForPath(org, repo, workflowPath, defaultBranch);
        var commitSummaries = new ArrayList<Map<String,Object>>();

        for (JsonNode c : commits) {
            var sha = c.path("sha").asText();
            var commit = c.path("commit");
            var date = commit.path("committer").path("date").asText();
            var message = commit.path("message").asText();

            // fetch file snapshot at this commit
            JsonNode blob = gh.getFileAtCommit(org, repo, workflowPath, sha);
            var contentB64 = blob.path("content").asText("").replace("\n", "");
            if (contentB64.isEmpty()) continue; // file might not exist at this commit

            byte[] content = Base64.getDecoder().decode(contentB64);
            var out = Paths.rawSnapshotPath(cfg, org, repo, workflowPath, sha);
            Files.createDirectories(out.getParent());
            Files.write(out, content);

            var cs = new LinkedHashMap<String,Object>();
            cs.put("sha", sha);
            cs.put("date", date);
            cs.put("message", message);
            Path base = Path.of(cfg.outDir()).toAbsolutePath();
            Path rel = base.relativize(out.toAbsolutePath());
            cs.put("raw_snapshot_relpath", rel.toString());
            commitSummaries.add(cs);
        }

        // index JSON
        var index = new LinkedHashMap<String,Object>();
        index.put("org", org);
        index.put("repo", repo);
        index.put("workflow_path", workflowPath);
        index.put("nb_commits", commitSummaries.size());
        if (!commitSummaries.isEmpty()) {
            index.put("first_commit_date", commitSummaries.getLast().get("date"));
            index.put("last_commit_date", commitSummaries.getFirst().get("date"));
        }
        index.put("commits", commitSummaries);
        index.put("collected_at", Instant.now().toString());

        var indexPath = Paths.workflowIndexPath(cfg, org, repo, workflowPath);
        Files.createDirectories(indexPath.getParent());
        om.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), index);
    }
}
