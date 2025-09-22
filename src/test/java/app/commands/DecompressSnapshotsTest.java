package app.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

class DecompressSnapshotsTest {

    @TempDir
    Path tempDir;

    private StringWriter outputCapture;
    private StringWriter errorCapture;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        errorCapture = new StringWriter();
        
        DecompressSnapshots decompressSnapshots = new DecompressSnapshots();
        commandLine = new CommandLine(decompressSnapshots);
        commandLine.setOut(new PrintWriter(outputCapture));
        commandLine.setErr(new PrintWriter(errorCapture));
    }

    @Test
    void shouldDecompressGzippedYamlFiles() throws Exception {
        // Given
        Path snapshotsDir = tempDir.resolve("snapshots");
        Path workflowDir = snapshotsDir.resolve("org").resolve("repo").resolve("workflows").resolve("ci");
        Files.createDirectories(workflowDir);
        
        Path gzFile = workflowDir.resolve("commit123.yml.gz");
        String yamlContent = "name: CI\non:\n  push:\n    branches: [main]\njobs:\n  test:\n    runs-on: ubuntu-latest";
        
        // Create gzipped file
        try (var gzOut = new GZIPOutputStream(Files.newOutputStream(gzFile))) {
            gzOut.write(yamlContent.getBytes());
        }

        // When
        int exitCode = commandLine.execute("--root", snapshotsDir.toString(), "--workers", "1");

        // Then
        assertThat(exitCode).isEqualTo(0);
        
        Path decompressedFile = workflowDir.resolve("commit123.yml");
        assertThat(decompressedFile).exists();
        assertThat(Files.readString(decompressedFile)).isEqualTo(yamlContent);
        
        // Original file should be deleted by default
        assertThat(gzFile).doesNotExist();
    }

    @Test
    void shouldHandleMultipleFiles() throws Exception {
        // Given
        Path snapshotsDir = tempDir.resolve("snapshots");
        Path workflowDir1 = snapshotsDir.resolve("org1").resolve("repo1").resolve("workflows").resolve("ci");
        Path workflowDir2 = snapshotsDir.resolve("org2").resolve("repo2").resolve("workflows").resolve("deploy");
        Files.createDirectories(workflowDir1);
        Files.createDirectories(workflowDir2);
        
        // Create multiple gzipped files
        createGzippedYaml(workflowDir1.resolve("commit1.yml.gz"), "name: CI1");
        createGzippedYaml(workflowDir1.resolve("commit2.yml.gz"), "name: CI2");
        createGzippedYaml(workflowDir2.resolve("commit3.yml.gz"), "name: Deploy");

        // When
        int exitCode = commandLine.execute("--root", snapshotsDir.toString(), "--workers", "2");

        // Then
        assertThat(exitCode).isEqualTo(0);
        
        assertThat(workflowDir1.resolve("commit1.yml")).exists();
        assertThat(workflowDir1.resolve("commit2.yml")).exists();
        assertThat(workflowDir2.resolve("commit3.yml")).exists();
        
        assertThat(Files.readString(workflowDir1.resolve("commit1.yml"))).isEqualTo("name: CI1");
        assertThat(Files.readString(workflowDir1.resolve("commit2.yml"))).isEqualTo("name: CI2");
        assertThat(Files.readString(workflowDir2.resolve("commit3.yml"))).isEqualTo("name: Deploy");
    }

    @Test
    void shouldReturnZeroWhenRootDirectoryDoesNotExist() {
        // Given
        Path nonExistentDir = tempDir.resolve("nonexistent");

        // When
        int exitCode = commandLine.execute("--root", nonExistentDir.toString());

        // Then
        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void shouldIgnoreNonGzippedFiles() throws Exception {
        // Given
        Path snapshotsDir = tempDir.resolve("snapshots");
        Path workflowDir = snapshotsDir.resolve("org").resolve("repo").resolve("workflows").resolve("ci");
        Files.createDirectories(workflowDir);
        
        // Create non-gzipped files
        Files.writeString(workflowDir.resolve("regular.yml"), "name: Regular");
        Files.writeString(workflowDir.resolve("other.txt"), "some text");
        
        // Create one gzipped file
        createGzippedYaml(workflowDir.resolve("compressed.yml.gz"), "name: Compressed");

        // When
        int exitCode = commandLine.execute("--root", snapshotsDir.toString());

        // Then
        assertThat(exitCode).isEqualTo(0);
        
        // Only the gzipped file should be processed
        assertThat(workflowDir.resolve("compressed.yml")).exists();
        assertThat(workflowDir.resolve("compressed.yml.gz")).doesNotExist();
        
        // Other files should remain unchanged
        assertThat(workflowDir.resolve("regular.yml")).exists();
        assertThat(workflowDir.resolve("other.txt")).exists();
    }

    @Test
    void shouldValidateWorkersParameter() {
        // When - using invalid workers value
        int exitCode = commandLine.execute("--workers", "invalid");

        // Then
        assertThat(exitCode).isNotEqualTo(0);
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).containsAnyOf("invalid", "number", "Integer");
    }

    @Test
    void shouldUseDefaultParameters() throws Exception {
        // Given
        Path defaultDataDir = tempDir.resolve("data").resolve("raw");
        Files.createDirectories(defaultDataDir.resolve("workflows"));
        createGzippedYaml(defaultDataDir.resolve("workflows").resolve("test.yml.gz"), "name: Test");

        // When - using all defaults (but override root to use temp dir)
        int exitCode = commandLine.execute("--root", defaultDataDir.toString());

        // Then
        assertThat(exitCode).isEqualTo(0);
        assertThat(defaultDataDir.resolve("workflows").resolve("test.yml")).exists();
        assertThat(defaultDataDir.resolve("workflows").resolve("test.yml.gz")).doesNotExist(); // deleted by default
    }

    private void createGzippedYaml(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        try (var gzOut = new GZIPOutputStream(Files.newOutputStream(path))) {
            gzOut.write(content.getBytes());
        }
    }
}
