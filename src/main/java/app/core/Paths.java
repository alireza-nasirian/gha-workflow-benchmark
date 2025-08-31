package app.core;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Paths {
    public static Path orgIndexDir(Config cfg, String org) {
        return Path.of(cfg.outDir(), "index", org);
    }
    public static Path repoIndexDir(Config cfg, String org, String repo) {
        return orgIndexDir(cfg, org).resolve(repo);
    }
    public static Path rawSnapshotPath(Config cfg, String org, String repo, String workflowPath, String sha) {
        return Path.of(cfg.outDir(), "raw", org, repo, workflowPath).resolve(sha + ".yml");
    }
    public static Path workflowIndexPath(Config cfg, String org, String repo, String workflowPath) {
        var hash = sha1Hex(workflowPath);
        return repoIndexDir(cfg, org, repo).resolve("workflows").resolve(hash + ".json");
    }
    static String sha1Hex(String s) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes()));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
