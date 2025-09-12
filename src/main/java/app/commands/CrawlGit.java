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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@CommandLine.Command(name = "crawl-git", description = "Clone repos and collect workflow histories via JGit (parallel)")
public class CrawlGit implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(CrawlGit.class);

    @CommandLine.Option(
            names="--orgs-file",
            required = true,
            description="Path to JSON array of org logins"
    )
    Path orgsFile;

    @CommandLine.Option(
            names="--log-every",
            defaultValue = "10",
            description="Log progress every N repos (default: ${DEFAULT-VALUE})"
    )
    int logEvery;

    @CommandLine.Option(
            names="--git-cache-dir",
            description="Directory for clones (default: <out.dir>/.cache/git)"
    )
    Path gitCacheDir;

    @CommandLine.Option(
            names="--task-timeout-min",
            defaultValue = "-1",
            description="Per-repo task timeout in minutes (default: from application.properties)"
    )
    int taskTimeoutMin;

    // Kept for CLI compatibility; we never delete clones in this version.
    @CommandLine.Option(
            names="--keep-clone",
            description="(Ignored) Clones are always kept; delete manually if needed."
    )
    boolean keepClone;

    @Override
    public Integer call() throws Exception {
        final var cfg = Config.load();
        final var http = new Http(cfg);
        final var gh = new GitHubClient(cfg, http);
        final var reposApi = new Repos(gh);
        final var gitHistory = new HistoryGit(cfg);
        final var om = new ObjectMapper();

        final var orgs = readOrgs(orgsFile);
        final Path cacheRoot = (gitCacheDir != null)
                ? gitCacheDir
                : Path.of(cfg.outDir(), ".cache", "git");

        final Semaphore cloneSlots = newCloneLimiter(cfg);
        final int effectiveTimeoutMin = (taskTimeoutMin == -1) ? cfg.taskTimeoutDefaultMinutes() : taskTimeoutMin;
        final Duration taskTimeout = Duration.ofMinutes(Math.max(1, effectiveTimeoutMin));

        for (String org : orgs) {
            long t0 = System.currentTimeMillis();

            // 1) List repos for the org and filter
            List<JsonNode> repoList = reposApi.list(org);
            List<JsonNode> targets = new ArrayList<>();
            for (JsonNode r : repoList) {
                if (r.path("archived").asBoolean(false)) continue;
                if (r.path("fork").asBoolean(false)) continue;
                targets.add(r);
            }
            final int total = targets.size();
            log.info("Org {} → {} repos to process ({} skipped)", org, total, repoList.size() - total);

            // 2) Dispatch tasks (virtual threads) and track completions + timeouts
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CompletionService<RepoResult> ecs = new ExecutorCompletionService<>(pool);

            final Map<Future<RepoResult>, RepoTicket> tickets = new ConcurrentHashMap<>();
            final AtomicInteger completed = new AtomicInteger(0);
            final AtomicInteger reposWithWorkflows = new AtomicInteger(0);
            final AtomicInteger timedOut = new AtomicInteger(0);
            final AtomicInteger failed = new AtomicInteger(0);

            int submitted = 0;
            for (JsonNode r : targets) {
                final String repo = r.path("name").asText();
                final String cloneUrl = r.path("clone_url").asText();
                final String defaultBranch = r.path("default_branch").asText("main");
                final Path local = cacheRoot.resolve(org).resolve(repo);

                Callable<RepoResult> task = () -> {
                    log.info("Start {}/{}", org, repo);
                    boolean hasWorkflows = false;
                    try {
                        cloneSlots.acquire();
                        try {
                            gitHistory.collectRepo(org, repo, cloneUrl, local, defaultBranch);
                        } finally {
                            cloneSlots.release();
                        }
                        hasWorkflows = hasAnyWorkflowIndex(cfg, org, repo);
                        if (hasWorkflows) reposWithWorkflows.incrementAndGet();
                        log.info("Done  {}/{}", org, repo);
                        return RepoResult.ok(org, repo, hasWorkflows);
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("Repo task failed for {}/{}: {}", org, repo, ex.toString());
                        return RepoResult.fail(org, repo, ex);
                    } finally {
                        int done = completed.incrementAndGet();
                        if (done % logEvery == 0 || done == total) {
                            log.info("Org {} → repos scanned {}/{}", org, done, total);
                        }
                    }
                };

                Future<RepoResult> f = ecs.submit(task);
                tickets.put(f, new RepoTicket(org, repo, Instant.now()));
                submitted++;
            }

            // 3) Wait with polling + watchdog for timeouts
            int received = 0;
            Instant lastHeartbeat = Instant.now();
            while (received < submitted) {
                Future<RepoResult> f = ecs.poll(cfg.taskPollTimeoutSeconds(), TimeUnit.SECONDS);
                if (f != null) {
                    try {
                        f.get(); // already logged inside task
                    } catch (CancellationException ce) {
                        // already logged as timeout below
                    } catch (Exception ignore) {
                        // already logged inside task
                    } finally {
                        tickets.remove(f);
                        received++;
                    }
                }

                // Heartbeat every ~30s
                if (Duration.between(lastHeartbeat, Instant.now()).getSeconds() >= 30) {
                    log.info("Org {} → progress heartbeat: {}/{}", org, completed.get(), total);
                    lastHeartbeat = Instant.now();
                }

                // Timeout watchdog: cancel any task exceeding the budget
                Instant now = Instant.now();
                for (Map.Entry<Future<RepoResult>, RepoTicket> e : new ArrayList<>(tickets.entrySet())) {
                    RepoTicket t = e.getValue();
                    if (Duration.between(t.startedAt, now).compareTo(taskTimeout) > 0) {
                        Future<RepoResult> future = e.getKey();
                        boolean cancelled = future.cancel(true); // interrupt virtual thread
                        if (cancelled) {
                            timedOut.incrementAndGet();
                            log.warn("Repo task TIMEOUT for {}/{} after {} min; cancelled",
                                    t.org, t.repo, taskTimeout.toMinutes());
                            tickets.remove(future);
                            received++; // treat as completed for loop progress
                        }
                    }
                }
            }

            pool.shutdown();

            // Ensure the final progress line is printed
            if (completed.get() < total) {
                log.info("Org {} → repos scanned {}/{}", org, completed.get(), total);
            }

            long secs = (System.currentTimeMillis() - t0) / 1000;
            log.info("Org {} → done in {}s (timeouts={}, failures={})", org, secs, timedOut.get(), failed.get());

            // 4) Per-org metrics (this run)
            try {
                int scannedThisRun = total; // attempted after filters
                double ratio = (scannedThisRun > 0)
                        ? (reposWithWorkflows.get() * 1.0 / scannedThisRun)
                        : 0.0;

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("org", org);
                summary.put("repos_scanned", scannedThisRun);
                summary.put("repos_with_workflows", reposWithWorkflows.get());
                summary.put("ratio", ratio);
                summary.put("timeouts", timedOut.get());
                summary.put("failures", failed.get());
                summary.put("updated_at", Instant.now().toString());

                Path outPath = Path.of(cfg.outDir(), "metrics", "orgs", org + ".json");
                Files.createDirectories(outPath.getParent());
                Path tmp = outPath.resolveSibling(outPath.getFileName() + ".tmp");
                om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), summary);
                Files.move(tmp, outPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                log.info("Org {} → wrote metrics summary to {}", org, outPath.toAbsolutePath());
            } catch (Exception e) {
                log.warn("Failed writing metrics for {}: {}", org, e.toString());
            }
        }

        return 0;
    }

    private static List<String> readOrgs(Path p) throws Exception {
        var om = new ObjectMapper();
        var arr = om.readTree(Files.readAllBytes(p));
        return om.convertValue(arr, om.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private Semaphore newCloneLimiter(Config cfg) {
        int max = Math.max(4, Math.min(cfg.maxWorkers(), 8));
        return new Semaphore(max);
    }

    private static boolean hasAnyWorkflowIndex(Config cfg, String org, String repo) {
        try {
            Path wfDir = Path.of(cfg.outDir(), "index", org, repo, "workflows");
            if (!Files.isDirectory(wfDir)) return false;
            try (var stream = Files.list(wfDir)) {
                return stream.anyMatch(p -> p.toString().endsWith(".json"));
            }
        } catch (Exception e) {
            return false;
        }
    }

    // Small holders
    private static final class RepoTicket {
        final String org, repo;
        final Instant startedAt;
        RepoTicket(String org, String repo, Instant startedAt) {
            this.org = org; this.repo = repo; this.startedAt = startedAt;
        }
    }
    private static final class RepoResult {
        final String org, repo;
        final boolean ok;
        final boolean hasWorkflows;
        final Throwable error;
        private RepoResult(String org, String repo, boolean ok, boolean hasWorkflows, Throwable error) {
            this.org = org; this.repo = repo; this.ok = ok; this.hasWorkflows = hasWorkflows; this.error = error;
        }
        static RepoResult ok(String org, String repo, boolean hasWorkflows) {
            return new RepoResult(org, repo, true, hasWorkflows, null);
        }
        static RepoResult fail(String org, String repo, Throwable error) {
            return new RepoResult(org, repo, false, false, error);
        }
    }
}
