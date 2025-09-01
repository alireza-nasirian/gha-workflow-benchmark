// src/main/java/app/Main.java
package app;

import app.commands.Crawl;
import app.commands.CrawlGit;
import picocli.CommandLine;

@CommandLine.Command(
        name = "collector",
        mixinStandardHelpOptions = true,
        version = "1.0",
        description = "GitHub Actions Workflow Collector (REST or JGit modes)",
        subcommands = {
                Crawl.class,      // REST API collector
                CrawlGit.class    // JGit collector
        }
)
public class Main implements Runnable {

    @Override
    public void run() {
        // Default action if no subcommand given
        System.out.println("Please choose a subcommand: crawl (REST) or crawl-git (JGit).");
        System.out.println("Example:");
        System.out.println("  java -jar target/gha-workflow-benchmark-0.1.0.jar crawl --orgs-file data/orgs.json");
        System.out.println("  java -jar target/gha-workflow-benchmark-0.1.0.jar crawl-git --orgs-file data/orgs.json");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
