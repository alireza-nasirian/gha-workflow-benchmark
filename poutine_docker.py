#!/usr/bin/env python3
"""
Poutine Analysis using Docker (WSL Alternative)
Runs Poutine in a Docker container to avoid WSL mounting issues.
"""

import json
import csv
import subprocess
import os
import sys
import tempfile
import shutil
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass
import argparse
from datetime import datetime

# Fix Windows console encoding
if sys.platform == 'win32':
    try:
        os.system('chcp 65001 >nul')
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except:
        pass


@dataclass
class CommitInfo:
    """Information about a commit that potentially fixes security issues."""
    org: str
    repo: str
    workflow_path: str
    commit_sha: str
    commit_date: str
    matched_keywords: List[str]
    message: str
    snapshot_relpath: str
    prev_sha: str
    diff_relpath: str


@dataclass
class PoutineResult:
    """Results from running Poutine on a workflow."""
    success: bool
    error_message: Optional[str]
    findings_count: int
    findings: List[Dict]
    raw_output: str
    
    def to_dict(self):
        return {
            'success': self.success,
            'error_message': self.error_message,
            'findings_count': self.findings_count,
            'findings': self.findings,
            'raw_output': self.raw_output
        }


@dataclass
class ComparisonResult:
    """Comparison of Poutine results before and after a commit."""
    commit_info: CommitInfo
    before_result: PoutineResult
    after_result: PoutineResult
    weaknesses_reduced: bool
    before_count: int
    after_count: int
    reduction_count: int
    fixed_issues: List[str]
    
    def to_dict(self):
        return {
            'org': self.commit_info.org,
            'repo': self.commit_info.repo,
            'workflow_path': self.commit_info.workflow_path,
            'commit_sha': self.commit_info.commit_sha,
            'commit_date': self.commit_info.commit_date,
            'prev_sha': self.commit_info.prev_sha,
            'matched_keywords': self.commit_info.matched_keywords,
            'message': self.commit_info.message,
            'weaknesses_reduced': self.weaknesses_reduced,
            'before_count': self.before_count,
            'after_count': self.after_count,
            'reduction_count': self.reduction_count,
            'fixed_issues': self.fixed_issues,
            'before_result': self.before_result.to_dict(),
            'after_result': self.after_result.to_dict()
        }


class PoutineDockerAnalyzer:
    """Runs Poutine analysis using Docker."""
    
    def __init__(self, data_root: Path, docker_image: str = "ghcr.io/boostsecurityio/poutine:latest"):
        self.data_root = Path(data_root)
        self.docker_image = docker_image
        self._check_docker()
        
    def _check_docker(self):
        """Check if Docker is available."""
        try:
            result = subprocess.run(
                ['docker', '--version'],
                capture_output=True,
                text=True,
                timeout=10
            )
            if result.returncode != 0:
                raise Exception("Docker is not available")
            print(f"✓ Docker found: {result.stdout.strip()}")
        except Exception as e:
            raise Exception(f"Docker is required but not available: {e}")
    
    def _pull_poutine_image(self):
        """Pull Poutine Docker image if not already present."""
        print(f"Checking for Poutine Docker image...")
        try:
            # Check if image exists
            result = subprocess.run(
                ['docker', 'image', 'inspect', self.docker_image],
                capture_output=True,
                text=True,
                timeout=10
            )
            
            if result.returncode != 0:
                print(f"Pulling Poutine image: {self.docker_image}")
                print("This may take a few minutes on first run...")
                result = subprocess.run(
                    ['docker', 'pull', self.docker_image],
                    capture_output=True,
                    text=True,
                    timeout=300
                )
                if result.returncode == 0:
                    print("✓ Poutine image ready")
                else:
                    raise Exception(f"Failed to pull image: {result.stderr}")
            else:
                print("✓ Poutine image already available")
        except subprocess.TimeoutExpired:
            raise Exception("Docker image pull timed out")
        except Exception as e:
            raise Exception(f"Error with Docker image: {e}")
    
    def run_poutine_on_workflow(self, workflow_file: Path) -> PoutineResult:
        """
        Run Poutine on a single workflow file using Docker.
        
        Args:
            workflow_file: Path to the workflow YAML file
            
        Returns:
            PoutineResult containing findings
        """
        if not workflow_file.exists():
            return PoutineResult(
                success=False,
                error_message=f"Workflow file not found: {workflow_file}",
                findings_count=0,
                findings=[],
                raw_output=""
            )
        
        # Create a temporary directory structure
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)
            workflows_dir = tmpdir_path / ".github" / "workflows"
            workflows_dir.mkdir(parents=True)
            
            # Copy the workflow file
            dest_workflow = workflows_dir / "workflow.yml"
            shutil.copy2(workflow_file, dest_workflow)
            
            try:
                # Convert Windows path to Unix-style for Docker
                # Docker on Windows uses /c/ for C:\ or /d/ for D:\
                tmpdir_abs = tmpdir_path.absolute()
                
                # Run Poutine via Docker
                # Mount the temp directory as /repo in the container
                cmd = [
                    'docker', 'run', '--rm',
                    '-v', f'{tmpdir_abs}:/repo',
                    self.docker_image,
                    'analyze_local', '/repo',
                    '-f', 'json'
                ]
                
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=60
                )
                
                # Parse JSON output
                findings = []
                findings_count = 0
                
                if result.returncode == 0 and result.stdout:
                    try:
                        # Try to parse as JSON
                        output = json.loads(result.stdout)
                        if isinstance(output, list):
                            findings = output
                            findings_count = len(output)
                        elif isinstance(output, dict):
                            findings = output.get('findings', [])
                            findings_count = len(findings)
                    except json.JSONDecodeError:
                        # If JSON parsing fails, count findings in text
                        findings_count = result.stdout.count('Finding:')
                
                return PoutineResult(
                    success=True,
                    error_message=None,
                    findings_count=findings_count,
                    findings=findings,
                    raw_output=result.stdout + "\n" + result.stderr
                )
                
            except subprocess.TimeoutExpired:
                return PoutineResult(
                    success=False,
                    error_message="Poutine execution timed out",
                    findings_count=0,
                    findings=[],
                    raw_output=""
                )
            except Exception as e:
                return PoutineResult(
                    success=False,
                    error_message=f"Error running Poutine: {str(e)}",
                    findings_count=0,
                    findings=[],
                    raw_output=""
                )
    
    def compare_before_after(self, commit: CommitInfo) -> ComparisonResult:
        """
        Compare Poutine results before and after a commit.
        
        Args:
            commit: CommitInfo object with commit details
            
        Returns:
            ComparisonResult showing the comparison
        """
        # Get workflow files
        before_file = self.data_root / commit.snapshot_relpath.replace(
            commit.commit_sha, commit.prev_sha
        )
        after_file = self.data_root / commit.snapshot_relpath
        
        print(f"  Analyzing: {commit.org}/{commit.repo}/{commit.workflow_path}")
        print(f"    Before: {commit.prev_sha[:8]}")
        
        before_result = self.run_poutine_on_workflow(before_file)
        
        print(f"    After:  {commit.commit_sha[:8]}")
        after_result = self.run_poutine_on_workflow(after_file)
        
        # Determine if weaknesses were reduced
        weaknesses_reduced = False
        reduction_count = 0
        fixed_issues = []
        
        if before_result.success and after_result.success:
            reduction_count = before_result.findings_count - after_result.findings_count
            weaknesses_reduced = reduction_count > 0
            
            # Try to identify specific fixed issues
            if weaknesses_reduced:
                before_issues = set(str(f) for f in before_result.findings)
                after_issues = set(str(f) for f in after_result.findings)
                fixed_issues = list(before_issues - after_issues)
        
        status = '✓ reduced' if weaknesses_reduced else '✗ no reduction'
        print(f"    Result: {before_result.findings_count} -> {after_result.findings_count} ({status})")
        
        return ComparisonResult(
            commit_info=commit,
            before_result=before_result,
            after_result=after_result,
            weaknesses_reduced=weaknesses_reduced,
            before_count=before_result.findings_count,
            after_count=after_result.findings_count,
            reduction_count=reduction_count,
            fixed_issues=fixed_issues
        )


def load_commits_from_jsonl(jsonl_file: Path) -> List[CommitInfo]:
    """Load commit information from JSONL file."""
    commits = []
    with open(jsonl_file, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                data = json.loads(line)
                commits.append(CommitInfo(**data))
    return commits


def load_commits_from_csv(csv_file: Path) -> List[CommitInfo]:
    """Load commit information from CSV file."""
    commits = []
    with open(csv_file, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            # Parse matched_keywords from string representation
            keywords_str = row['matched_keywords']
            keywords = eval(keywords_str) if keywords_str else []
            
            commits.append(CommitInfo(
                org=row['org'],
                repo=row['repo'],
                workflow_path=row['workflow_path'],
                commit_sha=row['commit_sha'],
                commit_date=row['commit_date'],
                matched_keywords=keywords,
                message=row['message'],
                snapshot_relpath=row['snapshot_relpath'],
                prev_sha=row['prev_sha'],
                diff_relpath=row['diff_relpath']
            ))
    return commits


def save_results(results: List[ComparisonResult], output_dir: Path):
    """Save analysis results in multiple formats."""
    output_dir.mkdir(exist_ok=True)
    
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    
    # Save detailed JSON results
    json_file = output_dir / f'poutine_analysis_{timestamp}.json'
    with open(json_file, 'w', encoding='utf-8') as f:
        json.dump([r.to_dict() for r in results], f, indent=2)
    print(f"\n✓ Saved detailed results to: {json_file}")
    
    # Save summary CSV
    csv_file = output_dir / f'poutine_summary_{timestamp}.csv'
    with open(csv_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow([
            'org', 'repo', 'workflow_path', 'commit_sha', 'commit_date',
            'before_count', 'after_count', 'reduction_count', 'weaknesses_reduced',
            'matched_keywords', 'message'
        ])
        
        for r in results:
            writer.writerow([
                r.commit_info.org,
                r.commit_info.repo,
                r.commit_info.workflow_path,
                r.commit_info.commit_sha,
                r.commit_info.commit_date,
                r.before_count,
                r.after_count,
                r.reduction_count,
                r.weaknesses_reduced,
                '|'.join(r.commit_info.matched_keywords),
                r.commit_info.message.replace('\n', ' ').strip()
            ])
    print(f"✓ Saved summary CSV to: {csv_file}")
    
    # Print summary statistics
    print("\n" + "="*70)
    print("ANALYSIS SUMMARY")
    print("="*70)
    
    total = len(results)
    successful = sum(1 for r in results if r.before_result.success and r.after_result.success)
    reduced = sum(1 for r in results if r.weaknesses_reduced)
    
    print(f"Total commits analyzed: {total}")
    print(f"Successful analyses: {successful}")
    print(f"Commits that reduced weaknesses: {reduced} ({reduced/total*100:.1f}%)")
    
    if reduced > 0:
        print(f"\nCommits with real security improvements:")
        for r in results:
            if r.weaknesses_reduced:
                print(f"  • {r.commit_info.org}/{r.commit_info.repo} - " +
                      f"{r.commit_info.commit_sha[:8]} " +
                      f"({r.reduction_count} fewer issues)")


def main():
    parser = argparse.ArgumentParser(
        description='Run Poutine analysis using Docker (WSL alternative)'
    )
    parser.add_argument(
        '--data-root',
        type=Path,
        default=Path('data'),
        help='Root directory containing workflow snapshots'
    )
    parser.add_argument(
        '--input',
        type=Path,
        default=Path('out_security_scan/hits.jsonl'),
        help='Input file (JSONL or CSV) with commit information'
    )
    parser.add_argument(
        '--output',
        type=Path,
        default=Path('poutine_results'),
        help='Output directory for results'
    )
    parser.add_argument(
        '--limit',
        type=int,
        default=None,
        help='Limit number of commits to analyze (for testing)'
    )
    parser.add_argument(
        '--docker-image',
        type=str,
        default='ghcr.io/boostsecurityio/poutine:latest',
        help='Docker image to use for Poutine'
    )
    parser.add_argument(
        '--pull-image',
        action='store_true',
        help='Force pull the Docker image before starting'
    )
    
    args = parser.parse_args()
    
    print("="*70)
    print("POUTINE WORKFLOW ANALYSIS (DOCKER MODE)")
    print("="*70)
    print(f"Data root: {args.data_root}")
    print(f"Input file: {args.input}")
    print(f"Output directory: {args.output}")
    print(f"Docker image: {args.docker_image}")
    
    # Load commits
    print("\nLoading commit data...")
    if args.input.suffix == '.jsonl':
        commits = load_commits_from_jsonl(args.input)
    elif args.input.suffix == '.csv':
        commits = load_commits_from_csv(args.input)
    else:
        raise ValueError(f"Unsupported input format: {args.input.suffix}")
    
    print(f"Loaded {len(commits)} commits")
    
    if args.limit:
        commits = commits[:args.limit]
        print(f"Limited to {len(commits)} commits for testing")
    
    # Initialize analyzer
    print("\nInitializing Docker-based Poutine analyzer...")
    analyzer = PoutineDockerAnalyzer(args.data_root, args.docker_image)
    
    # Pull image if requested
    if args.pull_image:
        analyzer._pull_poutine_image()
    
    # Run analysis
    print("\nRunning Poutine analysis...\n")
    
    results = []
    for i, commit in enumerate(commits, 1):
        print(f"[{i}/{len(commits)}]")
        try:
            result = analyzer.compare_before_after(commit)
            results.append(result)
        except Exception as e:
            print(f"  ERROR: {e}")
    
    # Save results
    save_results(results, args.output)
    
    print("\n✓ Analysis complete!")


if __name__ == '__main__':
    main()

