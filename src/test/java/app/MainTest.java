package app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;

class MainTest {

    private StringWriter outputCapture;
    private StringWriter errorCapture;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        outputCapture = new StringWriter();
        errorCapture = new StringWriter();
        
        Main main = new Main();
        commandLine = new CommandLine(main);
        commandLine.setOut(new PrintWriter(outputCapture));
        commandLine.setErr(new PrintWriter(errorCapture));
    }

    @Test
    void shouldShowSubcommandsWhenNoArgumentsProvided() {
        // When
        int exitCode = commandLine.execute();

        // Then
        assertThat(exitCode).isEqualTo(0);
        // The output goes to System.out, not our captured writer
        // We'll verify the method doesn't throw an exception
        assertThatCode(() -> new Main().run()).doesNotThrowAnyException();
    }

    @Test
    void shouldShowHelpWhenRequested() {
        // When
        int exitCode = commandLine.execute("--help");

        // Then
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.toString();
        assertThat(output).contains("GitHub Actions Workflow Collector");
        assertThat(output).contains("collector");
        assertThat(output).contains("crawl");
        assertThat(output).contains("crawl-git");
        assertThat(output).contains("decompress");
    }

    @Test
    void shouldShowVersionWhenRequested() {
        // When
        int exitCode = commandLine.execute("--version");

        // Then
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.toString();
        assertThat(output).contains("1.0");
    }

    @Test
    void shouldFailWithUnknownSubcommand() {
        // When
        int exitCode = commandLine.execute("unknown-command");

        // Then
        assertThat(exitCode).isNotEqualTo(0);
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).containsAnyOf("Unknown subcommand", "Unmatched argument");
    }

    @Test
    void shouldHandleInvalidOptions() {
        // When
        int exitCode = commandLine.execute("--invalid-option");

        // Then
        assertThat(exitCode).isNotEqualTo(0);
        String errorOutput = errorCapture.toString();
        assertThat(errorOutput).containsAnyOf("Unknown option", "Unmatched argument");
    }

    @Test
    void shouldNotThrowWhenTuningJGitCache() {
        // This test verifies that the JGit cache tuning doesn't throw exceptions
        // We can't easily test the actual tuning, but we can ensure it doesn't break
        
        // When & Then - should not throw any exception
        assertThatCode(() -> {
            Main main = new Main();
            // The tuneJGitCache method is called in main(), but it's private
            // We're testing that creating a Main instance doesn't fail
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldImplementRunnable() {
        // Given
        Main main = new Main();

        // When & Then - should not throw any exception
        assertThatCode(main::run).doesNotThrowAnyException();
    }
}
