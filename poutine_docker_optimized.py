#!/usr/bin/env python3
"""
Poutine Analysis using Docker - OPTIMIZED VERSION
Keeps one Docker container running to avoid overhead of starting/stopping containers.
Much faster for analyzing many commits!
"""

import json
import csv
import subprocess
import os
import sys
import tempfile
import shutil
from pathlib import Path
from typing import Dict, List, Optional
from dataclasses import dataclass
import argparse
from datetime import datetime
import time

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


class PoutineDockerOptimized:
    """
    Optimized Poutine analyzer that keeps Docker container running.
    Much faster than starting/stopping container for each analysis.
    """
    
    def __init__(self, data_root: Path, docker_image: str = "ghcr.io/boostsecurityio/poutine:latest"):
        self.data_root = Path(data_root)
        self.docker_image = docker_image
        self.container_id = None
        self.shared_mount = None
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
    
    def _ensure_image(self):
        """Ensure Poutine Docker image is available."""
        print(f"Checking for Poutine Docker image...")
        try:
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
        except Exception as e:
            raise Exception(f"Error with Docker image: {e}")
    
    def start_container(self):
        """
        Start a long-running Docker container with mounted directory.
        This container will be reused for all analyses.
        """
        print("\n🚀 Starting persistent Docker container...")
        
        # Create a shared temp directory that will be mounted
        self.shared_mount = Path(tempfile.mkdtemp(prefix="poutine_"))
        print(f"  Shared mount: {self.shared_mount}")
        
        try:
            # Start container in detached mode with sleep to keep it alive
            cmd = [
                'docker', 'run',
                '-d',  # Detached mode
                '--name', f'poutine-analyzer-{int(time.time())}',
                '-v', f'{self.shared_mount.absolute()}:/workspace',
                self.docker_image,
                'sleep', 'infinity'  # Keep container running
            ]
            
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if result.returncode == 0:
                self.container_id = result.stdout.strip()
                print(f"✓ Container started: {self.container_id[:12]}")
                print("  This container will be reused for all analyses (much faster!)\n")
                return True
            else:
                raise Exception(f"Failed to start container: {result.stderr}")
                
        except Exception as e:
            print(f"✗ Error starting container: {e}")
            return False
    
    def stop_container(self):
        """Stop and remove the Docker container."""
        if self.container_id:
            print("\n🛑 Stopping Docker container...")
            try:
                subprocess.run(
                    ['docker', 'stop', self.container_id],
                    capture_output=True,
                    timeout=30
                )
                subprocess.run(
                    ['docker', 'rm', self.container_id],
                    capture_output=True,
                    timeout=10
                )
                print(f"✓ Container {self.container_id[:12]} stopped and removed")
            except Exception as e:
                print(f"⚠ Error stopping container: {e}")
        
        # Clean up shared mount
        if self.shared_mount and self.shared_mount.exists():
            try:
                shutil.rmtree(self.shared_mount)
            except:
                pass
    
    def _check_container_running(self):
        """Check if container is still running, restart if needed."""
        if not self.container_id:
            return False
        
        try:
            result = subprocess.run(
                ['docker', 'inspect', '-f', '{{.State.Running}}', self.container_id],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode == 0 and result.stdout.strip() == 'true':
                return True
            else:
                # Container stopped or doesn't exist, restart it
                print("\n⚠ Container stopped, restarting...")
                self.stop_container()
                success = self.start_container()
                if success:
                    print("✓ Container restarted successfully\n")
                return success
        except Exception as e:
            print(f"⚠ Error checking container: {e}")
            return False
    
    def run_poutine_on_workflow(self, workflow_file: Path) -> PoutineResult:
        """
        Run Poutine on a workflow using the persistent container.
        Much faster than starting a new container each time!
        """
        if not workflow_file.exists():
            return PoutineResult(
                success=False,
                error_message=f"Workflow file not found: {workflow_file}",
                findings_count=0,
                findings=[],
                raw_output=""
            )
        
        # Check if container is still running
        if not self._check_container_running():
            return PoutineResult(
                success=False,
                error_message="Container not running and failed to restart",
                findings_count=0,
                findings=[],
                raw_output=""
            )
        
        try:
            # Create temp directory structure in shared mount
            analysis_dir = self.shared_mount / f"analysis_{int(time.time() * 1000000)}"
            workflows_dir = analysis_dir / ".github" / "workflows"
            workflows_dir.mkdir(parents=True, exist_ok=True)
            
            # Copy workflow file
            dest_workflow = workflows_dir / "workflow.yml"
            shutil.copy2(workflow_file, dest_workflow)
            
            # Get relative path in container
            rel_path = analysis_dir.relative_to(self.shared_mount)
            container_path = f"/workspace/{rel_path}"
            
            # Execute Poutine in the running container
            cmd = [
                'docker', 'exec',
                self.container_id,
                'poutine', 'analyze_local', container_path,
                '-f', 'json'
            ]
            
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=60
            )
            
            # Clean up this analysis directory
            try:
                shutil.rmtree(analysis_dir)
            except:
                pass
            
            # Parse results
            findings = []
            findings_count = 0
            
            if result.stdout:
                try:
                    output = json.loads(result.stdout)
                    if isinstance(output, list):
                        findings = output
                        findings_count = len(output)
                    elif isinstance(output, dict):
                        findings = output.get('findings', [])
                        findings_count = len(findings)
                except json.JSONDecodeError:
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
        """Compare Poutine results before and after a commit."""
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
    """Save analysis results."""
    output_dir.mkdir(exist_ok=True)
    
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    
    # JSON
    json_file = output_dir / f'poutine_analysis_{timestamp}.json'
    with open(json_file, 'w', encoding='utf-8') as f:
        json.dump([r.to_dict() for r in results], f, indent=2)
    print(f"\n✓ Saved: {json_file}")
    
    # CSV
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
    print(f"✓ Saved: {csv_file}")
    
    # Summary
    print("\n" + "="*70)
    print("ANALYSIS SUMMARY")
    print("="*70)
    
    total = len(results)
    successful = sum(1 for r in results if r.before_result.success and r.after_result.success)
    reduced = sum(1 for r in results if r.weaknesses_reduced)
    
    print(f"Total: {total}")
    print(f"Successful: {successful}")
    print(f"Reduced weaknesses: {reduced} ({reduced/total*100:.1f}%)")
    
    if reduced > 0:
        print(f"\nTop commits with improvements:")
        for r in sorted([r for r in results if r.weaknesses_reduced], 
                       key=lambda x: x.reduction_count, reverse=True)[:10]:
            print(f"  • {r.commit_info.org}/{r.commit_info.repo} - " +
                  f"{r.commit_info.commit_sha[:8]} ({r.reduction_count} fewer issues)")


def main():
    parser = argparse.ArgumentParser(
        description='Poutine analysis with optimized Docker (keeps container running)'
    )
    parser.add_argument('--data-root', type=Path, default=Path('data'))
    parser.add_argument('--input', type=Path, default=Path('out_security_scan/hits.jsonl'))
    parser.add_argument('--output', type=Path, default=Path('poutine_results'))
    parser.add_argument('--limit', type=int, help='Limit commits')
    parser.add_argument('--docker-image', type=str, 
                       default='ghcr.io/boostsecurityio/poutine:latest')
    
    args = parser.parse_args()
    
    print("="*70)
    print("POUTINE ANALYSIS (OPTIMIZED DOCKER)")
    print("="*70)
    print("\n⚡ This version keeps one container running - much faster!\n")
    
    # Load commits
    print("Loading commits...")
    if args.input.suffix == '.jsonl':
        commits = load_commits_from_jsonl(args.input)
    else:
        commits = load_commits_from_csv(args.input)
    
    print(f"Loaded {len(commits):,} commits")
    
    if args.limit:
        commits = commits[:args.limit]
        print(f"Limited to {args.limit:,}")
    
    # Initialize analyzer
    analyzer = PoutineDockerOptimized(args.data_root, args.docker_image)
    analyzer._ensure_image()
    
    # Start persistent container
    if not analyzer.start_container():
        print("✗ Failed to start container")
        return 1
    
    try:
        # Analyze all commits
        print(f"Analyzing {len(commits):,} commits...\n")
        
        results = []
        start_time = time.time()
        
        for i, commit in enumerate(commits, 1):
            print(f"[{i}/{len(commits)}]")
            try:
                result = analyzer.compare_before_after(commit)
                results.append(result)
            except Exception as e:
                print(f"  ERROR: {e}")
        
        elapsed = time.time() - start_time
        print(f"\n⏱ Total time: {elapsed:.1f} seconds ({elapsed/len(commits):.2f}s per commit)")
        
        # Save results
        save_results(results, args.output)
        print("\n✓ Analysis complete!")
        
    finally:
        # Always stop container
        analyzer.stop_container()


if __name__ == '__main__':
    main()

