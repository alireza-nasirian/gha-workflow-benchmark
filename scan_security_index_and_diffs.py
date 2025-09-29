#!/usr/bin/env python3
import argparse
import csv
import gzip
import io
import json
import re
import sys
import difflib
from pathlib import Path, PurePosixPath, PureWindowsPath
from datetime import datetime
from typing import List, Dict, Any, Optional

DEFAULT_KEYWORDS = [
    r"\bfix(?:es|ed)?\b",
    r"\bsecurity\b",
    r"\bvuln(?:erability|erable|erabilities)?\b",
    r"\bcve[- ]?\d{4}-\d{3,7}\b",
    r"\bpin(?:ned|ning)?\b",
    r"\bsha[- ]?pin(?:ned|ning)?\b",
    r"\binject(?:ion|s|ed)?\b",
    r"\bsanitiz(?:e|ed|ing|ation)\b",
    r"\bescape(?:d|s|ing)?\b",
    r"\bharden(?:ing|ed)?\b",
    r"\bpermission(?:s)?\b",
    r"\bleak(?:ed|s|age)?\b",
    r"\bxss\b",
    r"\bcsrf\b",
    r"\brce\b",
    r"\bsecret(?:s)?\b",
    r"\btokens?\b",
    r"\bauth(?:n|z|entication|orization)?\b",
]

def load_keywords(path: Optional[Path]) -> List[re.Pattern]:
    pats = DEFAULT_KEYWORDS.copy()
    if path and path.exists():
        extra = [ln.strip() for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip() and not ln.strip().startswith("#")]
        pats.extend(extra)
    try:
        return [re.compile(p, re.IGNORECASE) for p in pats]
    except re.error as e:
        print(f"Regex error in keywords: {e}", file=sys.stderr)
        sys.exit(2)

def normalize_relpath(rel: str) -> Path:
    # Handle windows-style backslashes in the JSON
    if "\\" in rel and "/" not in rel:
        p = PureWindowsPath(rel)
    else:
        p = PurePosixPath(rel)
    # Drop any leading 'raw' or 'data'—we'll root from --root
    return Path(*p.parts)

def read_text_maybe_gz(path: Path) -> str:
    if path.suffix == ".gz":
        with gzip.open(path, "rb") as f:
            data = f.read()
        try:
            return data.decode("utf-8", errors="ignore")
        except:
            return data.decode("latin-1", errors="ignore")
    else:
        return path.read_text(encoding="utf-8", errors="ignore")

def unified_diff_text(old_text: str, new_text: str, fromfile: str, tofile: str) -> str:
    old_lines = old_text.splitlines(keepends=True)
    new_lines = new_text.splitlines(keepends=True)
    return "".join(difflib.unified_diff(old_lines, new_lines, fromfile=fromfile, tofile=tofile))

def scan_repo_workflow_json(json_path: Path, root: Path, diffs_dir: Path, patterns: List[re.Pattern]) -> List[Dict[str, Any]]:
    data = json.loads(json_path.read_text(encoding="utf-8"))
    org = data.get("org")
    repo = data.get("repo")
    workflow_path = data.get("workflow_path")
    commits: List[Dict[str, Any]] = data.get("commits", [])
    # Map sha -> index for previous lookup
    results: List[Dict[str, Any]] = []
    for idx, c in enumerate(commits):
        msg = c.get("message", "")
        matches = sorted({pat.pattern for pat in patterns if pat.search(msg)})
        if not matches:
            continue

        sha = c.get("sha")
        date = c.get("date")
        raw_rel = c.get("raw_snapshot_relpath")  # e.g. raw\org\repo\.github\workflows\file.yml\<sha>.yml(.gz)
        # Build absolute path to snapshot
        rel_path = normalize_relpath(raw_rel)
        curr_path = root / rel_path
        if not curr_path.exists():
            # Try .gz variant if not present
            gz_try = curr_path.with_suffix(curr_path.suffix + ".gz")
            if gz_try.exists():
                curr_path = gz_try

        prev_sha = None
        prev_path = None
        if idx + 1 < len(commits):
            prev_sha = commits[idx+1].get("sha")
            prev_rel = commits[idx+1].get("raw_snapshot_relpath")
            prev_rel_path = normalize_relpath(prev_rel)
            prev_path = root / prev_rel_path
            if not prev_path.exists():
                gz_try2 = prev_path.with_suffix(prev_path.suffix + ".gz")
                if gz_try2.exists():
                    prev_path = gz_try2

        # Read texts (handle missing prev gracefully)
        new_text = ""
        old_text = ""
        if curr_path and curr_path.exists():
            new_text = read_text_maybe_gz(curr_path)
        if prev_path and Path(prev_path).exists():
            old_text = read_text_maybe_gz(prev_path)

        # Create diff
        diff_file_rel = None
        if new_text and old_text:
            # put diffs under <org>/<repo>/{workflow-file}/<sha>.patch
            wf_leaf = (workflow_path or "workflow.yml").replace("/", "_").replace("\\", "_")
            dest_dir = diffs_dir / org / repo / wf_leaf
            dest_dir.mkdir(parents=True, exist_ok=True)
            diff_fname = f"{sha}.patch"
            diff_abs = dest_dir / diff_fname
            udiff = unified_diff_text(old_text, new_text,
                                      fromfile=f"{workflow_path}@{prev_sha or 'NA'}",
                                      tofile=f"{workflow_path}@{sha}")
            diff_abs.write_text(udiff, encoding="utf-8")
            diff_file_rel = diff_abs.relative_to(diffs_dir.parent).as_posix()

        entry = {
            "org": org,
            "repo": repo,
            "workflow_path": workflow_path,
            "commit_sha": sha,
            "commit_date": date,
            "matched_keywords": matches,
            "message": msg,
            "snapshot_relpath": rel_path.as_posix(),
            "prev_sha": prev_sha,
            "diff_relpath": diff_file_rel,  # e.g. out/diffs/org/repo/wf@.patch (relative to output root)
        }
        results.append(entry)

    return results

def main():
    ap = argparse.ArgumentParser(description="Scan index JSONs for security-ish commit messages and produce diffs.")
    ap.add_argument("--root", default="data", help="Dataset root containing index/ and raw/ (default: data)")
    ap.add_argument("--out", default="out_security_scan", help="Output directory (default: out_security_scan)")
    ap.add_argument("--keywords-file", default=None, help="Optional file with one regex per line to extend keyword list")
    ap.add_argument("--org", default=None, help="Filter to specific org")
    ap.add_argument("--repo", default=None, help="Filter to specific repo")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    index_root = root / "index"
    raw_root = root / "raw"
    outdir = Path(args.out).resolve()
    outdir.mkdir(parents=True, exist_ok=True)
    diffs_dir = outdir / "diffs"
    diffs_dir.mkdir(parents=True, exist_ok=True)

    patterns = load_keywords(Path(args.keywords_file) if args.keywords_file else None)

    hits: List[Dict[str, Any]] = []
    # Walk: data/index/<org>/<repo>/workflows/*.json
    for org_dir in sorted(index_root.glob("*")):
        if not org_dir.is_dir():
            continue
        org = org_dir.name
        if args.org and args.org != org:
            continue
        for repo_dir in sorted(org_dir.glob("*")):
            if not repo_dir.is_dir():
                continue
            repo = repo_dir.name
            if args.repo and args.repo != repo:
                continue
            wf_dir = repo_dir / "workflows"
            if not wf_dir.exists():
                continue
            for wf_json in sorted(wf_dir.glob("*.json")):
                try:
                    repo_hits = scan_repo_workflow_json(wf_json, root, diffs_dir, patterns)
                    hits.extend(repo_hits)
                except Exception as e:
                    # Keep going but record an error entry
                    hits.append({
                        "org": org, "repo": repo, "workflow_path": None, "commit_sha": None,
                        "error": f"{wf_json}: {e}"
                    })

    # Write JSONL
    jsonl_path = outdir / "hits.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as f:
        for h in hits:
            f.write(json.dumps(h, ensure_ascii=False) + "\n")

    # Write CSV summary
    csv_path = outdir / "hits.csv"
    fields = ["org","repo","workflow_path","commit_sha","commit_date","matched_keywords","snapshot_relpath","prev_sha","diff_relpath","message"]
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for h in hits:
            row = h.copy()
            # make keywords concise
            if isinstance(row.get("matched_keywords"), list):
                row["matched_keywords"] = "|".join(row["matched_keywords"])
            w.writerow(row)

    # Write a tiny README
    readme = outdir / "README.md"
    readme.write_text(
        "# Security-ish commit scan\n\n"
        f"- Generated: {datetime.utcnow().isoformat()}Z\n"
        f"- Root: `{root}`\n"
        f"- Total hits: **{len([h for h in hits if not h.get('error')])}**\n\n"
        "## Files\n"
        f"- `hits.jsonl` — structured entries, one per hit\n"
        f"- `hits.csv` — spreadsheet-friendly summary\n"
        f"- `diffs/` — unified patches relative to previous snapshot in the same workflow\n\n"
        "## Entry schema\n"
        "```json\n"
        "{\n"
        '  "org": "flutter",\n'
        '  "repo": "tools_metadata",\n'
        '  "workflow_path": ".github/workflows/build.yaml",\n'
        '  "commit_sha": "abc123...",\n'
        '  "commit_date": "2024-06-17T22:30:16Z",\n'
        '  "matched_keywords": ["\\\\bfix(?:es|ed)?\\\\b", "\\\\bsecurity\\\\b"],\n'
        '  "message": "Commit message ...",\n'
        '  "snapshot_relpath": "raw/flutter/tools_metadata/.github/workflows/build.yaml/<sha>.yml",\n'
        '  "prev_sha": "def456...",\n'
        '  "diff_relpath": "out_security_scan/diffs/flutter/tools_metadata/.github_workflows_build.yaml/abc123.patch"\n'
        "}\n"
        "```\n",
        encoding="utf-8"
    )

    print(f"Wrote:\n- {jsonl_path}\n- {csv_path}\n- {diffs_dir}/*", flush=True)

if __name__ == '__main__':
    main()
