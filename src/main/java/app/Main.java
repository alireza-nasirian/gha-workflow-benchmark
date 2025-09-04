package app;

import app.commands.Crawl;                // keep your REST command if you have it
import app.commands.CrawlGit;             // JGit collector
import app.commands.DecompressSnapshots;  // .yml.gz -> .yml
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import picocli.CommandLine;

@CommandLine.Command(
        name = "collector",
        mixinStandardHelpOptions = true,
        version = "1.0",
        description = "GitHub Actions Workflow Collector (REST or JGit modes)",
        subcommands = {
                Crawl.class,              // REST API (optional; remove if not present)
                CrawlGit.class,           // JGit collector
                DecompressSnapshots.class // Post-process
        }
)
public class Main implements Runnable {

    @Override
    public void run() {
        System.out.println("Subcommands:");
        System.out.println("  crawl        (REST API collection)");
        System.out.println("  crawl-git    (JGit-based, fast & no API limits)");
        System.out.println("  decompress   (convert *.yml.gz to *.yml)");
    }

    public static void main(String[] args) {
        // Optional: tune JGit cache for speed on large repos
        tuneJGitCache();
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    private static void tuneJGitCache() {
        try {
            WindowCacheConfig wcc = new WindowCacheConfig();
            wcc.setPackedGitOpenFiles(256);
            wcc.setPackedGitLimit(512 * 1024 * 1024);       // 512 MB
            wcc.setPackedGitWindowSize(1 * 1024 * 1024);    // 1 MB
            wcc.setDeltaBaseCacheLimit(50 * 1024 * 1024);   // 50 MB
            wcc.install();
        } catch (Throwable ignored) {
            // Safe to ignore; defaults will be used
        }
    }
}
