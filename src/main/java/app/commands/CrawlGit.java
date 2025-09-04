package app.commands;

import app.core.Config;
import app.core.Http;
import app.github.GitHubClient;
import app.logic.HistoryGit;
import app.logic.Repos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

@CommandLine.Command(name = "crawl-git", description = "Clone repos and collect workflow histories via JGit (parallel)")
public class CrawlGit implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(CrawlGit.class);

    @CommandLine.Option(names="--orgs-file", required = true, description="Path to JSON array of org logins")
    Path orgsFile;

    @CommandLine.Option(names="--log-every", defaultValue = "10", description="Log progress every N repos")
    int logEvery;

    @CommandLine.Option(names="--git-cache-dir", description="Directory for temporary clones (default: <out.dir>/.cache/git)")
    Path gitCacheDir;

    @CommandLine.Option(names="--keep-clone", description="Keep local clone after processing (default: delete)")
    boolean keepClone;

    @Override
    public Integer call() throws Exception {
        final var cfg = Config.load();
        final var http = new Http(cfg);
        final var gh = new GitHubClient(cfg, http);
        final var reposApi = new Repos(gh);
        final var gitHistory = new HistoryGit(cfg);

        final var orgs = readOrgs(orgsFile);
        final Path cacheRoot = (gitCacheDir != null) ? gitCacheDir : Path.of(cfg.outDir(), ".cache", "git");

        // Limit concurrent clones (network bottleneck) — let history walk parallelize freely per repo
        final var cloneSlots = new Semaphore(Math.max(4, Math.min(cfg.maxWorkers(), 8)));

        for (String org : orgs) {
            long t0 = System.currentTimeMillis();

            List<JsonNode> repoList = reposApi.list(org);
            List<JsonNode> targets = new ArrayList<>();
            for (JsonNode r : repoList) {
                if (r.path("archived").asBoolean(false)) continue;
                if (r.path("fork").asBoolean(false)) continue;
                targets.add(r);
            }
            int total = targets.size();
            log.info("Org {} → {} repos to process ({} skipped)", org, total, repoList.size() - total);

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);
            int submitted = 0;

            for (JsonNode r : targets) {
                final String repo = r.path("name").asText();
                final String cloneUrl = r.path("clone_url").asText();
                final String defaultBranch = r.path("default_branch").asText("main");
                final Path local = cacheRoot.resolve(org).resolve(repo);

                ecs.submit(() -> {
                    try {
                        cloneSlots.acquire();
                        try {
                            gitHistory.collectRepo(org, repo, cloneUrl, local, defaultBranch);
                        } finally {
                            cloneSlots.release();
                        }
                    } catch (Exception ex) {
                        log.warn("Git collect failed for {}/{}: {}", org, repo, ex.toString());
                    } finally {
                        try { markRepoDone(cfg, org, repo); } catch (Exception ignore) {}
//                        if (!keepClone) deleteDirectoryQuietly(local);
                    }
                    return null;
                });
                submitted++;
            }

            for (int i = 1; i <= submitted; i++) {
                try { ecs.take().get(); } catch (Exception ignore) {}
                if (i % logEvery == 0 || i == submitted) {
                    log.info("Org {} → repos scanned {}/{}", org, i, submitted);
                }
            }
            pool.shutdown();

            long secs = (System.currentTimeMillis() - t0) / 1000;
            log.info("Org {} → done in {}s", org, secs);

            try {
                app.logic.Metrics.writeOrgSummary(cfg, org);
                var summaryPath = Path.of(cfg.outDir(), "metrics", "orgs", org + ".json");
                log.info("Org {} → wrote metrics summary to {}", org, summaryPath.toAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed writing metrics for {}: {}", org, e.toString());
            }
        }
        return 0;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    private static List<String> readOrgs(Path p) throws Exception {
        var om = new ObjectMapper();
        var arr = om.readTree(Files.readAllBytes(p));
        return om.convertValue(arr, om.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private void markRepoDone(app.core.Config cfg, String org, String repo) {
        try {
            var done = java.nio.file.Path.of(cfg.outDir(), "index", org, repo, "repo.done");
            java.nio.file.Files.createDirectories(done.getParent());
            java.nio.file.Files.writeString(done, java.time.Instant.now().toString());
        } catch (Exception ignore) {}
    }
}
