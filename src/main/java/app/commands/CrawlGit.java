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

import java.nio.file.*;
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

    @CommandLine.Option(names="--git-cache-dir",
            description="Directory for temporary clones (default: <out.dir>/.cache/git)")
    Path gitCacheDir;

    @CommandLine.Option(names="--keep-clone", description="Keep local clone after processing (default: delete)")
    boolean keepClone;

    @Override
    public Integer call() throws Exception {
        final var cfg = Config.load();

        // REST only for listing repos; all history handled by git
        final var http = new Http(cfg);
        final var gh = new GitHubClient(cfg, http);
        final var reposApi = new Repos(gh);
        final var gitHistory = new HistoryGit(cfg);

        final var orgs = readOrgs(orgsFile);
        final Path cacheRoot = (gitCacheDir != null)
                ? gitCacheDir
                : Path.of(cfg.outDir(), ".cache", "git");

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

//            ExecutorService pool = Executors.newFixedThreadPool(cfg.maxWorkers());
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);
            int submitted = 0;

            for (JsonNode r : targets) {
                final String repo = r.path("name").asText();
                final String cloneUrl = r.path("clone_url").asText();
                final Path local = cacheRoot.resolve(org).resolve(repo);

                ecs.submit(() -> {
                    try {
                        // run the git-based collection
                        gitHistory.collectRepo(org, repo, cloneUrl, local);
                    } catch (Exception ex) {
                        log.warn("Git collect failed for {}/{}: {}", org, repo, ex.toString());
                    } finally {
                        // cleanup clone to save disk, unless user asked to keep it
                        if (!keepClone) {
                            try {
                                deleteDirectoryQuietly(local);
                            } catch (Exception delEx) {
                                log.warn("Failed to delete clone for {}/{} at {}: {}", org, repo, local, delEx.toString());
                            }
                        }
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
        }
        return 0;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        // best effort recursive delete (close repos before calling this!)
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    private static List<String> readOrgs(Path p) throws Exception {
        var om = new ObjectMapper();
        var arr = om.readTree(Files.readAllBytes(p));
        return om.convertValue(arr, om.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
