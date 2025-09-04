package app.commands;

import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

@CommandLine.Command(name = "decompress", description = "Convert *.yml.gz snapshots to plain *.yml")
public class DecompressSnapshots implements Callable<Integer> {

    @CommandLine.Option(names="--root", defaultValue = "data/raw",
            description="Root directory containing snapshots (default: ${DEFAULT-VALUE})")
    Path root;

    @CommandLine.Option(names="--delete-original", defaultValue = "true",
            description="Delete .gz after successful decompression (default: ${DEFAULT-VALUE})")
    boolean deleteOriginal;

    @CommandLine.Option(names="--workers", defaultValue = "8",
            description="Parallel workers (default: ${DEFAULT-VALUE})")
    int workers;

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(root)) return 0;

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers));
        CompletionService<Void> ecs = new ExecutorCompletionService<>(pool);
        int submitted = 0;

        try (var paths = Files.walk(root)) {
            for (Path gz : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".yml.gz"))::iterator) {
                ecs.submit(() -> {
                    decompressOne(gz, deleteOriginal);
                    return null;
                });
                submitted++;
            }
        }

        for (int i = 0; i < submitted; i++) { ecs.take().get(); }
        pool.shutdown();
        return 0;
    }

    private static void decompressOne(Path gz, boolean deleteOriginal) throws IOException {
        Path out = Paths.get(gz.toString().replaceFirst("\\.gz$", ""));
        Files.createDirectories(out.getParent());

        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz));
             OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(os);
        }

        if (deleteOriginal) {
            Files.deleteIfExists(gz);
        }
    }
}
