package app.commands;

import app.core.Config;
import app.core.Http;
import app.github.GitHubClient;
import app.logic.History;
import app.logic.Repos;
import app.logic.Workflows;
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

@CommandLine.Command(name = "crawl", description = "Crawl orgs and collect workflow histories (parallel)")
public class Crawl implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(Crawl.class);

    @CommandLine.Option(
            names = "--orgs-file",
            required = true,
            description = "Path to JSON array of org logins (e.g., data/orgs.json)"
    )
    Path orgsFile;

    // log every N repos completed
    @CommandLine.Option(
            names = "--log-every",
            defaultValue = "10",
            description = "Log progress every N repos (default: ${DEFAULT-VALUE})"
    )
    int logEvery;

    @Override
    public Integer call() throws Exception {
        final var cfg = Config.load();
        final var http = new Http(cfg);
        final var gh = new GitHubClient(cfg, http);
        final var repos = new Repos(gh);
        final var wfs = new Workflows(gh);
        final var history = new History(cfg, gh);

        final var orgs = readOrgs(orgsFile);

        for (String org : orgs) {
            long t0 = System.currentTimeMillis();

            // 1) list repos
            List<JsonNode> repoList = repos.list(org);
            // filter out forks/archived
            List<JsonNode> targets = new ArrayList<>();
            for (JsonNode r : repoList) {
                if (r.path("archived").asBoolean(false)) continue;
                if (r.path("fork").asBoolean(false)) continue;
                targets.add(r);
            }

            int total = targets.size();
            log.info("Org {} → {} repos to scan ({} total, {} skipped as fork/archived)",
                    org, total, repoList.size(), repoList.size() - total);

            // 2) bounded parallel processing
            ExecutorService pool = Executors.newFixedThreadPool(cfg.maxWorkers());
            CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);

            int submitted = 0;
            for (JsonNode r : targets) {
                final String repo = r.path("name").asText();
                final String branch = Repos.defaultBranch(r);

                ecs.submit(() -> {
                    try {
                        List<JsonNode> contents;
                        try {
                            contents = wfs.list(org, repo);          // .github/workflows
                        } catch (Exception e) {
                            // No workflows dir or 404, skip quietly
                            return null;
                        }

                        for (JsonNode f : contents) {
                            if (!Workflows.isYaml(f)) continue;
                            final String path = f.path("path").asText();
                            try {
                                history.collectWorkflowHistory(org, repo, path, branch);
                            } catch (Exception ex) {
                                log.warn("Failed collecting history for {}/{}/{}: {}", org, repo, path, ex.getMessage());
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Repo task failed for {}/{}: {}", org, repo, ex.toString());
                    }
                    return null;
                });
                submitted++;
            }

            // 3) progress logging as repos complete
            for (int i = 1; i <= submitted; i++) {
                try {
                    ecs.take().get();
                } catch (Exception ignored) {
                    // already logged in the task
                }
                if (i % logEvery == 0 || i == submitted) {
                    log.info("Org {} → repos scanned {}/{}", org, i, submitted);
                }
            }

            pool.shutdown();

            long seconds = (System.currentTimeMillis() - t0) / 1000;
            log.info("Org {} → done in {}s", org, seconds);
        }

        return 0;
    }

    private static List<String> readOrgs(Path p) throws Exception {
        var om = new ObjectMapper();
        var bytes = Files.readAllBytes(p);
        var arr = om.readTree(bytes);
        return om.convertValue(arr, om.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
