#!/usr/bin/env python3
"""
Batch Poutine Analysis with Docker - OPTIMIZED + PROGRESS TRACKING
Combines the speed of optimized Docker with resume capability.
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

# Import from the optimized Docker version
from poutine_docker_optimized import (
    CommitInfo, PoutineResult, ComparisonResult,
    PoutineDockerOptimized, load_commits_from_jsonl, load_commits_from_csv
)


class BatchAnalyzer:
    """Manages batch processing with progress tracking."""
    
    def __init__(self, output_dir: Path, batch_size: int = 100):
        self.output_dir = output_dir
        self.batch_size = batch_size
        self.output_dir.mkdir(exist_ok=True)
        self.progress_file = self.output_dir / 'progress.json'
        self.results_file = self.output_dir / 'batch_results.jsonl'
        
    def load_progress(self) -> dict:
        """Load progress from previous run."""
        if self.progress_file.exists():
            with open(self.progress_file, 'r') as f:
                return json.load(f)
        return {
            'processed_count': 0,
            'last_commit_sha': None,
            'start_time': datetime.now().isoformat(),
            'last_update': None
        }
    
    def save_progress(self, progress: dict):
        """Save current progress."""
        progress['last_update'] = datetime.now().isoformat()
        with open(self.progress_file, 'w') as f:
            json.dump(progress, f, indent=2)
    
    def append_result(self, result: ComparisonResult):
        """Append a result to the JSONL file."""
        with open(self.results_file, 'a', encoding='utf-8') as f:
            f.write(json.dumps(result.to_dict()) + '\n')
    
    def count_actual_results(self) -> int:
        """Count actual results in the JSONL file."""
        count = 0
        if self.results_file.exists():
            with open(self.results_file, 'r', encoding='utf-8') as f:
                for line in f:
                    if line.strip():
                        count += 1
        return count
    
    def load_all_results(self) -> List[dict]:
        """Load all results from JSONL file."""
        results = []
        if self.results_file.exists():
            with open(self.results_file, 'r', encoding='utf-8') as f:
                for line in f:
                    if line.strip():
                        results.append(json.loads(line))
        return results
    
    def generate_final_reports(self):
        """Generate final CSV and JSON reports."""
        print("\nGenerating final reports...")
        results = self.load_all_results()
        
        if not results:
            print("No results to report")
            return
        
        timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
        
        # Save consolidated JSON
        json_file = self.output_dir / f'poutine_analysis_final_{timestamp}.json'
        with open(json_file, 'w', encoding='utf-8') as f:
            json.dump(results, f, indent=2)
        print(f"✓ Saved consolidated JSON: {json_file}")
        
        # Save summary CSV
        csv_file = self.output_dir / f'poutine_summary_final_{timestamp}.csv'
        with open(csv_file, 'w', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            writer.writerow([
                'org', 'repo', 'workflow_path', 'commit_sha', 'commit_date',
                'before_count', 'after_count', 'reduction_count', 'weaknesses_reduced',
                'before_success', 'after_success', 'matched_keywords', 'message'
            ])
            
            for r in results:
                writer.writerow([
                    r['org'],
                    r['repo'],
                    r['workflow_path'],
                    r['commit_sha'],
                    r['commit_date'],
                    r['before_count'],
                    r['after_count'],
                    r['reduction_count'],
                    r['weaknesses_reduced'],
                    r['before_result']['success'],
                    r['after_result']['success'],
                    '|'.join(r['matched_keywords']),
                    r['message'].replace('\n', ' ').strip()
                ])
        print(f"✓ Saved summary CSV: {csv_file}")
        
        # Print summary statistics
        print("\n" + "="*70)
        print("FINAL ANALYSIS SUMMARY")
        print("="*70)
        
        total = len(results)
        successful = sum(1 for r in results 
                        if r['before_result']['success'] and r['after_result']['success'])
        reduced = sum(1 for r in results if r['weaknesses_reduced'])
        
        print(f"Total commits analyzed: {total:,}")
        print(f"Successful analyses: {successful:,} ({successful/total*100:.1f}%)")
        print(f"Commits that reduced weaknesses: {reduced:,} ({reduced/total*100:.1f}%)")
        
        if reduced > 0:
            print(f"\nTop commits with most improvements:")
            sorted_results = sorted(results, key=lambda x: x['reduction_count'], reverse=True)
            for r in sorted_results[:20]:
                if r['weaknesses_reduced']:
                    print(f"  • {r['org']}/{r['repo']} - {r['commit_sha'][:8]} " +
                          f"({r['reduction_count']} fewer issues)")


def main():
    parser = argparse.ArgumentParser(
        description='Batch Poutine analysis with Docker - optimized with progress tracking'
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
        '--batch-size',
        type=int,
        default=100,
        help='Number of commits to process before saving progress'
    )
    parser.add_argument(
        '--limit',
        type=int,
        default=None,
        help='Limit total number of commits to analyze'
    )
    parser.add_argument(
        '--resume',
        action='store_true',
        help='Resume from previous run'
    )
    parser.add_argument(
        '--docker-image',
        type=str,
        default='ghcr.io/boostsecurityio/poutine:latest',
        help='Docker image to use'
    )
    
    args = parser.parse_args()
    
    print("="*70)
    print("BATCH POUTINE ANALYSIS (OPTIMIZED DOCKER + PROGRESS)")
    print("="*70)
    print(f"Data root: {args.data_root}")
    print(f"Input file: {args.input}")
    print(f"Output directory: {args.output}")
    print(f"Batch size: {args.batch_size}")
    print("\n⚡ Optimized Docker + Resume capability!\n")
    
    # Initialize batch analyzer
    batch_analyzer = BatchAnalyzer(args.output, args.batch_size)
    
    # Load or initialize progress
    progress = batch_analyzer.load_progress()
    
    # FIX: Always verify progress count against actual results to prevent corruption
    actual_count = batch_analyzer.count_actual_results()
    if progress['processed_count'] != actual_count:
        print(f"⚠ Progress counter mismatch detected!")
        print(f"  Progress file says: {progress['processed_count']:,}")
        print(f"  Actual results: {actual_count:,}")
        print(f"  Fixing progress counter...")
        progress['processed_count'] = actual_count
        batch_analyzer.save_progress(progress)
    
    if args.resume or progress['processed_count'] > 0:
        print(f"✓ Resuming from previous run")
        print(f"  Previously processed: {progress['processed_count']:,} commits")
        if progress['last_commit_sha']:
            print(f"  Last commit: {progress['last_commit_sha'][:8]}")
    else:
        print(f"✓ Starting new analysis")
        progress = {
            'processed_count': 0,
            'last_commit_sha': None,
            'start_time': datetime.now().isoformat(),
            'last_update': None
        }
    
    # Load commits
    print("\nLoading commit data...")
    if args.input.suffix == '.jsonl':
        all_commits = load_commits_from_jsonl(args.input)
    elif args.input.suffix == '.csv':
        all_commits = load_commits_from_csv(args.input)
    else:
        raise ValueError(f"Unsupported input format: {args.input.suffix}")
    
    print(f"Loaded {len(all_commits):,} total commits")
    
    # Filter to commits not yet processed
    commits_to_process = all_commits[progress['processed_count']:]
    
    if args.limit:
        remaining = args.limit - progress['processed_count']
        if remaining > 0:
            commits_to_process = commits_to_process[:remaining]
        else:
            print(f"Already processed {progress['processed_count']} commits (limit: {args.limit})")
            commits_to_process = []
    
    print(f"Commits to process in this run: {len(commits_to_process):,}")
    
    if not commits_to_process:
        print("\n✓ No commits to process. Generating final reports...")
        batch_analyzer.generate_final_reports()
        return
    
    # Initialize Docker analyzer
    print("\nInitializing optimized Docker analyzer...")
    analyzer = PoutineDockerOptimized(args.data_root, args.docker_image)
    analyzer._ensure_image()
    
    # Start persistent container
    if not analyzer.start_container():
        print("✗ Failed to start Docker container")
        return 1
    
    try:
        # Process commits in batches
        print(f"\nProcessing {len(commits_to_process):,} commits...\n")
        
        start_time = time.time()
        
        for i, commit in enumerate(commits_to_process):
            current_index = progress['processed_count'] + i
            total = progress['processed_count'] + len(commits_to_process)
            
            print(f"[{current_index + 1}/{total}] ({(current_index + 1)/total*100:.1f}%)")
            
            try:
                result = analyzer.compare_before_after(commit)
                batch_analyzer.append_result(result)
                
                # Update progress - use actual count to prevent corruption
                progress['processed_count'] = batch_analyzer.count_actual_results()
                progress['last_commit_sha'] = commit.commit_sha
                
                # Save progress every batch_size commits
                if (i + 1) % args.batch_size == 0:
                    batch_analyzer.save_progress(progress)
                    elapsed = time.time() - start_time
                    avg_time = elapsed / (i + 1)
                    remaining = (len(commits_to_process) - i - 1) * avg_time
                    
                    print(f"\n  ✓ Progress saved ({progress['processed_count']:,} commits)")
                    print(f"  ⏱ Avg: {avg_time:.2f}s per commit")
                    print(f"  ⏱ Remaining: ~{remaining/3600:.1f} hours")
                    print(f"  ✓ You can safely interrupt and resume later\n")
                
            except KeyboardInterrupt:
                print("\n\n⚠ Interrupted by user")
                batch_analyzer.save_progress(progress)
                print(f"✓ Progress saved ({progress['processed_count']:,} commits)")
                print(f"✓ Run with --resume to continue from where you left off")
                analyzer.stop_container()
                sys.exit(0)
                
            except Exception as e:
                print(f"  ERROR: {e}")
                # Continue with next commit
        
        # Save final progress
        batch_analyzer.save_progress(progress)
        
        elapsed = time.time() - start_time
        print(f"\n✓ Batch processing complete")
        print(f"⏱ Total time: {elapsed:.1f}s ({elapsed/len(commits_to_process):.2f}s per commit)")
        print(f"✓ Processed {progress['processed_count']:,} commits total")
        
    except KeyboardInterrupt:
        print("\n\n⚠ Interrupted by user")
        batch_analyzer.save_progress(progress)
        print(f"✓ Progress saved ({progress['processed_count']:,} commits)")
        print(f"✓ Run with --resume to continue")
        analyzer.stop_container()
        sys.exit(0)
    
    finally:
        # Always stop container
        analyzer.stop_container()
    
    # Generate final reports
    batch_analyzer.generate_final_reports()
    
    print("\n✓ Analysis complete!")
    print(f"  Total commits processed: {progress['processed_count']:,}")
    print(f"  Results saved in: {args.output}")


if __name__ == '__main__':
    main()

