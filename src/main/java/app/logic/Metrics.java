package app.logic;

import app.core.Config;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public class Metrics {
    private static final ObjectMapper OM = new ObjectMapper();

    public static void writeOrgSummary(Config cfg, String org) throws IOException {
        Path indexOrgDir = Path.of(cfg.outDir(), "index", org);
        if (!Files.exists(indexOrgDir)) {
            // nothing scanned yet; write empty summary
            writeSummary(cfg, org, 0, 0, 0, 0);
            return;
        }

        int reposScanned;
        int reposWithWf  = 0;
        int workflowsTotal = 0;
        int snapshotsTotal = 0;

        // reposScanned: count repo.done files
        try (Stream<Path> walk = Files.walk(indexOrgDir)) {
            reposScanned = (int) walk.filter(p -> p.getFileName().toString().equals("repo.done")).count();
        }

        // For each repo directory, inspect workflows folder
        try (Stream<Path> repos = Files.list(indexOrgDir)) {
            for (Path repoDir : repos.filter(Files::isDirectory).toList()) {
                Path wfDir = repoDir.resolve("workflows");
                if (Files.isDirectory(wfDir)) {
                    int wfCount = countJson(wfDir);
                    if (wfCount > 0) {
                        reposWithWf++;
                        workflowsTotal += wfCount;
                        snapshotsTotal += sumSnapshots(wfDir);
                    }
                }
            }
        }

        writeSummary(cfg, org, reposScanned, reposWithWf, workflowsTotal, snapshotsTotal);
    }

    private static int countJson(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.toString().endsWith(".json")).count();
        }
    }

    // Sum commits[].length across all workflow index JSONs in wfDir
    private static int sumSnapshots(Path wfDir) throws IOException {
        int total = 0;
        try (Stream<Path> s = Files.list(wfDir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    var node = OM.readTree(Files.readAllBytes(f));
                    var commits = node.get("commits");
                    if (commits != null && commits.isArray()) total += commits.size();
                } catch (Exception ignore) {}
            }
        }
        return total;
    }

    private static void writeSummary(Config cfg, String org, int scanned, int withWf, int wfTotal, int snapsTotal) throws IOException {
        double ratio = scanned > 0 ? (withWf * 1.0 / scanned) : 0.0;
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("org", org);
        out.put("repos_scanned", scanned);
        out.put("repos_with_workflows", withWf);
        out.put("ratio", ratio);
        out.put("workflows_total", wfTotal);
        out.put("snapshots_total", snapsTotal);
        out.put("updated_at", Instant.now().toString());

        Path outPath = Path.of(cfg.outDir(), "metrics", "orgs", org + ".json");
        Files.createDirectories(outPath.getParent());

        // atomic write
        Path tmp = outPath.resolveSibling(outPath.getFileName() + ".tmp");
        OM.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), out);
        Files.move(tmp, outPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
