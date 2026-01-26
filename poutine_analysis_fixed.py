#!/usr/bin/env python3
"""
Poutine Analysis Script - Fixed for WSL Issues
Works around WSL mounting problems by copying files to WSL home directory.
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
import uuid

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


class PoutineAnalyzerFixed:
    """Runs Poutine analysis with WSL workarounds."""
    
    def __init__(self, data_root: Path, use_wsl: bool = True):
        self.data_root = Path(data_root)
        self.use_wsl = use_wsl
        
        if self.use_wsl:
            # Check if we can use WSL at all
            try:
                result = subprocess.run(
                    ['wsl', 'echo', 'test'],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                if result.returncode != 0 or 'ERROR' in result.stderr:
                    print("⚠ WSL has issues, consider using Docker instead")
                    print("  Run: python poutine_docker.py")
            except:
                pass
        
    def run_poutine_on_workflow(self, workflow_file: Path) -> PoutineResult:
        """
        Run Poutine on a single workflow file.
        Uses a workaround for WSL mounting issues.
        
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
        
        try:
            if self.use_wsl:
                return self._run_with_wsl_workaround(workflow_file)
            else:
                return self._run_native(workflow_file)
        except Exception as e:
            return PoutineResult(
                success=False,
                error_message=f"Error running Poutine: {str(e)}",
                findings_count=0,
                findings=[],
                raw_output=""
            )
    
    def _run_with_wsl_workaround(self, workflow_file: Path) -> PoutineResult:
        """
        Run Poutine with WSL mounting workaround.
        Copies files to WSL home directory to avoid mounting issues.
        """
        # Generate unique ID for this run
        run_id = str(uuid.uuid4())[:8]
        wsl_temp_dir = f"/tmp/poutine_{run_id}"
        
        try:
            # Create temp directory in WSL
            subprocess.run(
                ['wsl', 'mkdir', '-p', f'{wsl_temp_dir}/.github/workflows'],
                capture_output=True,
                text=True,
                timeout=10
            )
            
            # Read workflow content
            with open(workflow_file, 'r', encoding='utf-8') as f:
                workflow_content = f.read()
            
            # Create temp file on Windows
            with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.yml') as tmp:
                tmp.write(workflow_content)
                tmp_path = tmp.name
            
            try:
                # Copy file to WSL using cat (avoids path mounting issues)
                # First, escape the content for shell
                escaped_content = workflow_content.replace('\\', '\\\\').replace('$', '\\$').replace('`', '\\`')
                
                # Write content to WSL file using echo
                copy_cmd = f'cat > {wsl_temp_dir}/.github/workflows/workflow.yml << "EOFWORKFLOW"\n{workflow_content}\nEOFWORKFLOW'
                
                subprocess.run(
                    ['wsl', 'bash', '-c', copy_cmd],
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                
                # Run Poutine on the WSL copy
                cmd = [
                    'wsl', 'bash', '-c',
                    f'export PATH=$PATH:$HOME/.local/bin && cd {wsl_temp_dir} && poutine analyze_local . -f json 2>&1'
                ]
                
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=60
                )
                
                # Clean up WSL temp directory
                subprocess.run(
                    ['wsl', 'rm', '-rf', wsl_temp_dir],
                    capture_output=True,
                    timeout=5
                )
                
                # Parse results
                findings = []
                findings_count = 0
                
                # Check for WSL errors
                if 'ERROR' in result.stderr and 'CreateProcessParseCommon' in result.stderr:
                    return PoutineResult(
                        success=False,
                        error_message="WSL mounting error - use poutine_docker.py instead",
                        findings_count=0,
                        findings=[],
                        raw_output=result.stderr
                    )
                
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
                
            finally:
                # Clean up Windows temp file
                try:
                    os.unlink(tmp_path)
                except:
                    pass
                    
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
                error_message=f"Error in WSL workaround: {str(e)}",
                findings_count=0,
                findings=[],
                raw_output=""
            )
    
    def _run_native(self, workflow_file: Path) -> PoutineResult:
        """Run Poutine natively (non-WSL)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir_path = Path(tmpdir)
            workflows_dir = tmpdir_path / ".github" / "workflows"
            workflows_dir.mkdir(parents=True)
            
            dest_workflow = workflows_dir / "workflow.yml"
            shutil.copy2(workflow_file, dest_workflow)
            
            try:
                cmd = ['poutine', 'analyze_local', str(tmpdir_path), '-f', 'json']
                
                result = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=60
                )
                
                findings = []
                findings_count = 0
                
                if result.returncode == 0 and result.stdout:
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
                    raw_output=result.stdout + result.stderr
                )
                
            except subprocess.TimeoutExpired:
                return PoutineResult(
                    success=False,
                    error_message="Poutine execution timed out",
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


def main():
    parser = argparse.ArgumentParser(description='Poutine analysis with WSL workarounds')
    parser.add_argument('--data-root', type=Path, default=Path('data'))
    parser.add_argument('--input', type=Path, default=Path('out_security_scan/hits.jsonl'))
    parser.add_argument('--output', type=Path, default=Path('poutine_results'))
    parser.add_argument('--limit', type=int, help='Limit commits')
    parser.add_argument('--no-wsl', action='store_true', help='Run without WSL')
    
    args = parser.parse_args()
    
    print("="*70)
    print("POUTINE ANALYSIS (WSL FIXED VERSION)")
    print("="*70)
    print("\n⚠ If you continue to have WSL issues, use: python poutine_docker.py\n")
    
    # Load commits
    print("Loading commits...")
    if args.input.suffix == '.jsonl':
        commits = load_commits_from_jsonl(args.input)
    else:
        commits = load_commits_from_csv(args.input)
    
    print(f"Loaded {len(commits)} commits")
    
    if args.limit:
        commits = commits[:args.limit]
        print(f"Limited to {args.limit}")
    
    # Analyze
    analyzer = PoutineAnalyzerFixed(args.data_root, use_wsl=not args.no_wsl)
    
    results = []
    for i, commit in enumerate(commits, 1):
        print(f"[{i}/{len(commits)}]")
        try:
            result = analyzer.compare_before_after(commit)
            results.append(result)
            
            # If we're seeing WSL errors, suggest Docker
            if result.before_result.error_message and 'WSL mounting' in result.before_result.error_message:
                print("\n⚠ WSL mounting errors detected!")
                print("   Consider using Docker version instead:")
                print("   python poutine_docker.py --limit 10\n")
                
        except Exception as e:
            print(f"  ERROR: {e}")
    
    # Save
    save_results(results, args.output)
    print("\n✓ Complete!")


if __name__ == '__main__':
    main()

