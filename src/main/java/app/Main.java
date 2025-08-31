package app;

import app.commands.Crawl;
import picocli.CommandLine;

@CommandLine.Command(
        name = "collector",
        mixinStandardHelpOptions = true,
        subcommands = { Crawl.class }
)
public class Main {
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
