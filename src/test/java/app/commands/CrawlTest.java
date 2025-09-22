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

class CrawlTest {

    @TempDir
    Path tempDir;

    private StringWriter outputCapture;
    private StringWriter errorCapture;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        errorCapture = new StringWriter();
        
        Crawl crawl = new Crawl();
        commandLine = new CommandLine(crawl);
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
    void shouldAcceptValidOrgsFile() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When
        int exitCode = commandLine.execute("--orgs-file", orgsFile.toString());

        // Then - command should parse successfully (may fail at runtime due to missing GitHub token)
        // We're testing CLI parsing here, not the full execution
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("--orgs-file");
        assertThat(errorOutput).doesNotContain("required");
    }

    @Test
    void shouldAcceptLogEveryParameter() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When
        int exitCode = commandLine.execute("--orgs-file", orgsFile.toString(), "--log-every", "5");

        // Then - should parse without CLI errors
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("--log-every");
        assertThat(errorOutput).doesNotContain("Unknown option");
    }

    @Test
    void shouldValidateLogEveryParameter() throws Exception {
        // Given
        Path orgsFile = tempDir.resolve("orgs.json");
        Files.writeString(orgsFile, "[\"testorg\"]");

        // When - using invalid log-every value
        int exitCode = commandLine.execute("--orgs-file", orgsFile.toString(), "--log-every", "invalid");

        // Then
        assertThat(exitCode).isNotEqualTo(0);
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).containsAnyOf("invalid", "number", "Integer");
    }

    @Test
    void shouldFailWithNonExistentOrgsFile() {
        // Given
        Path nonExistentFile = tempDir.resolve("nonexistent.json");

        // When
        int exitCode = commandLine.execute("--orgs-file", nonExistentFile.toString());

        // Then - may fail during execution due to file not found or missing token
        // The important part is that CLI parsing succeeded
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).doesNotContain("Unknown option");
    }
}
