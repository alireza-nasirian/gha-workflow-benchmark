package app.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class CrawlGitTest {

    @TempDir
    Path tempDir;

    private StringWriter outputCapture;
    private StringWriter errorCapture;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        errorCapture = new StringWriter();
        
        CrawlGit crawlGit = new CrawlGit();
        commandLine = new CommandLine(crawlGit);
        commandLine.setOut(new PrintWriter(outputCapture));
        commandLine.setErr(new PrintWriter(errorCapture));
    }

    @Test
    void shouldRequireOrgsFileParameter() {
        // When
        int exitCode = commandLine.execute();

        // Then
        assertThat(exitCode).isNotEqualTo(0);
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).contains("--orgs-file");
        assertThat(errorOutput).contains("required");
    }

    @Test
    void shouldAcceptAllValidParameters() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Path cacheDir = tempDir.resolve("cache");
        Files.writeString(orgsFile, "[\"testorg\"]");
        Files.createDirectories(cacheDir);

        // When
        int exitCode = commandLine.execute(
                "--orgs-file", orgsFile.toString(),
                "--log-every", "5",
                "--git-cache-dir", cacheDir.toString(),
                "--task-timeout-min", "30"
        );

        // Then - should parse without CLI errors
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("Unknown option");
        assertThat(errorOutput).doesNotContain("required");
    }

    @Test
    void shouldAcceptKeepCloneOption() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When
        int exitCode = commandLine.execute(
                "--orgs-file", orgsFile.toString(),
                "--keep-clone"
        );

        // Then - should parse without CLI errors
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("Unknown option");
    }

    @Test
    void shouldValidateNumericParameters() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When - using invalid numeric values
        int exitCode1 = commandLine.execute("--orgs-file", orgsFile.toString(), "--log-every", "invalid");
        
        // Reset for second test
        setUp();
        int exitCode2 = commandLine.execute("--orgs-file", orgsFile.toString(), "--task-timeout-min", "invalid");

        // Then
        assertThat(exitCode1).isNotEqualTo(0);
        assertThat(exitCode2).isNotEqualTo(0);
    }

    @Test
    void shouldUseDefaultValues() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When - using minimal parameters (relying on defaults)
        int exitCode = commandLine.execute("--orgs-file", orgsFile.toString());

        // Then - should parse successfully
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("required");
        assertThat(errorOutput).doesNotContain("Unknown option");
    }

    @Test
    void shouldAcceptNegativeTaskTimeout() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When - using -1 (which means use default from config)
        int exitCode = commandLine.execute(
                "--orgs-file", orgsFile.toString(),
                "--task-timeout-min", "-1"
        );

        // Then - should parse successfully
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("Unknown option");
    }
}
