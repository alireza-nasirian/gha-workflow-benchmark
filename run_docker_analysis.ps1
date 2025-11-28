# PowerShell script to run Poutine analysis using Docker
# Solves WSL mounting issues

param(
    [switch]$Test,
    [switch]$Full,
    [int]$Limit = 0,
    [switch]$Fast,
    [switch]$Stable
)

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "POUTINE ANALYSIS - DOCKER VERSION" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host ""

# Check Docker
Write-Host "Checking Docker..." -ForegroundColor Yellow
try {
    $dockerVersion = docker --version 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] $dockerVersion" -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Docker is not running" -ForegroundColor Red
        Write-Host "Please start Docker Desktop and try again" -ForegroundColor Yellow
        exit 1
    }
} catch {
    Write-Host "[ERROR] Docker is not installed" -ForegroundColor Red
    Write-Host "Please install Docker Desktop from: https://www.docker.com/products/docker-desktop" -ForegroundColor Yellow
    exit 1
}

Write-Host ""

# Determine which version to use
$UseStable = $true
$ScriptToRun = "poutine_docker_batch_stable.py"
$ExpectedTime = "7-8 days"

if ($Fast) {
    $UseStable = $false
    $ScriptToRun = "poutine_docker_batch.py"
    $ExpectedTime = "5-6 days"
    Write-Host "Using FAST version (may have container issues)" -ForegroundColor Yellow
} elseif ($Stable) {
    Write-Host "Using STABLE version (more reliable)" -ForegroundColor Green
} else {
    # Default to stable
    Write-Host "Using STABLE version by default (more reliable)" -ForegroundColor Green
    Write-Host "Use -Fast flag if you want the faster version" -ForegroundColor Gray
}

Write-Host ""

# Determine what to run
if ($Test) {
    Write-Host "Running test analysis (10 commits)..." -ForegroundColor Yellow
    Write-Host ""
    python $ScriptToRun --limit 10 --resume
}
elseif ($Limit -gt 0) {
    Write-Host "Running analysis on $Limit commits..." -ForegroundColor Yellow
    Write-Host "Progress will be saved automatically" -ForegroundColor Cyan
    Write-Host "Run again with same limit to process next batch" -ForegroundColor Cyan
    Write-Host ""
    python $ScriptToRun --limit $Limit --resume
}
elseif ($Full) {
    Write-Host "Running FULL analysis (85,576 commits)..." -ForegroundColor Yellow
    Write-Host "This will take $ExpectedTime" -ForegroundColor Yellow
    Write-Host "Progress saved every 100 commits" -ForegroundColor Cyan
    Write-Host ""
    
    Write-Host "Continue? (y/n): " -NoNewline
    $confirm = Read-Host
    
    if ($confirm -ne "y") {
        Write-Host "Cancelled" -ForegroundColor Yellow
        exit 0
    }
    
    Write-Host ""
    python $ScriptToRun --resume
}
else {
    Write-Host "Usage:" -ForegroundColor Yellow
    Write-Host "  .\run_docker_analysis.ps1 -Test              # Test with 10 commits" -ForegroundColor White
    Write-Host "  .\run_docker_analysis.ps1 -Limit 100         # Process 100 commits" -ForegroundColor White
    Write-Host "  .\run_docker_analysis.ps1 -Limit 100         # Run again = next 100!" -ForegroundColor White
    Write-Host "  .\run_docker_analysis.ps1 -Full              # Full analysis (85K)" -ForegroundColor White
    Write-Host ""
    Write-Host "Options:" -ForegroundColor Yellow
    Write-Host "  -Stable     Use stable version (default, more reliable)" -ForegroundColor White
    Write-Host "  -Fast       Use fast version (faster but may have container issues)" -ForegroundColor White
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor Yellow
    Write-Host "  .\run_docker_analysis.ps1 -Test" -ForegroundColor Cyan
    Write-Host "  .\run_docker_analysis.ps1 -Limit 100" -ForegroundColor Cyan
    Write-Host "  .\run_docker_analysis.ps1 -Limit 100 -Fast" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Recommended: Start with -Test" -ForegroundColor Green
    exit 0
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "SUCCESS!" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "Results saved in: poutine_results/" -ForegroundColor Green
    Write-Host "  - poutine_summary_*.csv (open in Excel)" -ForegroundColor Green
    Write-Host "  - poutine_analysis_*.json (detailed data)" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "[ERROR] Analysis failed" -ForegroundColor Red
    Write-Host "Try: python poutine_analysis_fixed.py --limit 10" -ForegroundColor Yellow
}

