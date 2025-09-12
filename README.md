# GitHub Actions Workflow Benchmark

A high-performance Java application for collecting and analyzing GitHub Actions workflow data from organizations and repositories. This tool provides both REST API and Git-based collection methods to build comprehensive datasets of workflow histories.

## Features

- **Dual Collection Modes**: REST API-based collection and JGit-based collection (faster, no API rate limits)
- **Parallel Processing**: Multi-threaded workflow collection with configurable concurrency
- **Workflow History**: Collects complete workflow evolution history through Git commits
- **Data Compression**: Automatic compression of workflow snapshots (`.yml.gz` format)
- **Organization Scanning**: Batch processing of multiple GitHub organizations
- **Filtering**: Automatically excludes archived and forked repositories
- **Progress Tracking**: Detailed logging and progress reporting
- **Timeout Management**: Configurable timeouts with automatic task cancellation
- **Metrics Collection**: Per-organization statistics and summaries

## Architecture

The application consists of three main commands:

1. **`crawl`** - REST API-based collection (subject to GitHub API rate limits)
2. **`crawl-git`** - JGit-based collection (recommended for large-scale data collection)
3. **decompress`** - Post-processing tool to convert compressed snapshots to plain YAML

## Prerequisites

- Java 21 or higher
- Maven 3.6+
- GitHub Personal Access Token (for private repositories and higher rate limits)

## Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd gha-workflow-benchmark
```

2. Build the project:
```bash
mvn clean compile
```

3. Create configuration file (`application.properties`):
```properties
# GitHub API Configuration
github.token=your_github_token_here
github.api.base.url=https://api.github.com

# Output Configuration
out.dir=data/raw
max.workers=8

# Timeout Configuration
task.timeout.default.minutes=30
task.poll.timeout.seconds=10
```

## Usage

### Basic Commands

Run the application using Maven:

```bash
# Show available commands
mvn exec:java -Dexec.mainClass="app.Main"

# JGit-based collection (recommended)
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="crawl-git --orgs-file data/orgs.json"

# REST API-based collection
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="crawl --orgs-file data/orgs.json"

# Decompress workflow snapshots
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="decompress --root data/raw"
```

### Command Options

#### `crawl-git` (Recommended)
```bash
crawl-git --orgs-file <path>              # Required: JSON file with organization names
         [--log-every <n>]                # Log progress every N repos (default: 10)
         [--git-cache-dir <path>]          # Directory for Git clones (default: <out.dir>/.cache/git)
         [--task-timeout-min <minutes>]    # Per-repo timeout (default: from config)
```

#### `crawl` (REST API)
```bash
crawl --orgs-file <path>                  # Required: JSON file with organization names
      [--log-every <n>]                   # Log progress every N repos (default: 10)
```

#### `decompress`
```bash
decompress [--root <path>]                # Root directory for snapshots (default: data/raw)
           [--delete-original <true|false>] # Delete .gz files after decompression (default: true)
           [--workers <n>]                # Parallel workers (default: 8)
```

### Organization File Format

Create a JSON file containing an array of GitHub organization names:

```json
[
  "microsoft",
  "google",
  "facebook",
  "elastic",
  "kubernetes"
]
```

## Output Structure

The tool generates the following directory structure:

```
data/
├── raw/
│   ├── index/
│   │   └── <org>/
│   │       └── <repo>/
│   │           └── workflows/
│   │               └── <workflow>.json     # Workflow metadata and history
│   └── snapshots/
│       └── <org>/
│           └── <repo>/
│               └── workflows/
│                   └── <workflow>/
│                       ├── <commit1>.yml.gz # Compressed workflow snapshots
│                       ├── <commit2>.yml.gz
│                       └── ...
├── metrics/
│   └── orgs/
│       ├── <org1>.json                     # Per-organization metrics
│       ├── <org2>.json
│       └── ...
└── .cache/
    └── git/                                # Git repository cache (for crawl-git)
        └── <org>/
            └── <repo>/
```

## Configuration

### Application Properties

| Property | Description | Default |
|----------|-------------|---------|
| `github.token` | GitHub Personal Access Token | Required |
| `github.api.base.url` | GitHub API base URL | `https://api.github.com` |
| `out.dir` | Output directory for collected data | `data/raw` |
| `max.workers` | Maximum concurrent workers | `8` |
| `task.timeout.default.minutes` | Default task timeout in minutes | `30` |
| `task.poll.timeout.seconds` | Polling timeout for task completion | `10` |

### Performance Tuning

The application automatically tunes JGit cache settings for optimal performance:

- **PackedGit Limit**: 512 MB
- **Window Size**: 1 MB  
- **Delta Base Cache**: 50 MB
- **Open Files**: 256

For large-scale collection, consider:
- Increasing `max.workers` based on available CPU cores
- Adjusting `task.timeout.default.minutes` for large repositories
- Using SSD storage for the Git cache directory

## Examples

### Collect workflows from specific organizations:

```bash
# Create organizations file
echo '["elastic", "kubernetes", "microsoft"]' > orgs.json

# Run JGit-based collection
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="crawl-git --orgs-file orgs.json --log-every 5"
```

### Large-scale collection with custom settings:

```bash
# High-performance collection with custom cache directory
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="crawl-git --orgs-file orgs.json --git-cache-dir /fast-ssd/git-cache --task-timeout-min 60"
```

### Post-process collected data:

```bash
# Decompress all workflow snapshots
mvn exec:java -Dexec.mainClass="app.Main" -Dexec.args="decompress --root data/raw --workers 12"
```

## Data Analysis

The collected data can be analyzed using the included Python utilities:

```bash
# Count dataset statistics
python count_dataset.py
```

## Logging

The application uses Logback for comprehensive logging:

- **Console Output**: Progress updates and important events
- **File Logging**: Detailed logs in `logs/app.YYYY-MM-DD.log`
- **Log Levels**: Configurable via `logback.xml`

## Troubleshooting

### Common Issues

1. **Rate Limiting**: Use `crawl-git` instead of `crawl` to avoid GitHub API rate limits
2. **Timeouts**: Increase `task.timeout.default.minutes` for large repositories
3. **Memory Issues**: Reduce `max.workers` or increase JVM heap size with `-Xmx`
4. **Storage Space**: Monitor disk usage as workflow histories can be large

### Performance Tips

- Use `crawl-git` for better performance and no API rate limits
- Place Git cache directory on fast storage (SSD)
- Adjust worker count based on available CPU cores and network bandwidth
- Monitor system resources during large collection jobs

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Built with [PicoCLI](https://picocli.info/) for command-line interface
- Uses [JGit](https://www.eclipse.org/jgit/) for Git operations
- Powered by [Jackson](https://github.com/FasterXML/jackson) for JSON processing

