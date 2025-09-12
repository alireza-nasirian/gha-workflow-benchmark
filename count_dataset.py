# save as: count_dataset.py  (run: python count_dataset.py)
import os, json

INDEX_ROOT = "data/index"
RAW_ROOT   = "data/raw"

workflow_files = 0
repos_with_wf = set()
total_snapshots = 0

for org in os.listdir(INDEX_ROOT):
    org_path = os.path.join(INDEX_ROOT, org)
    if not os.path.isdir(org_path): 
        continue
    for repo in os.listdir(org_path):
        wf_dir = os.path.join(org_path, repo, "workflows")
        if not os.path.isdir(wf_dir):
            continue
        any_wf = False
        for f in os.listdir(wf_dir):
            if not f.endswith(".json"):
                continue
            workflow_files += 1
            any_wf = True
            with open(os.path.join(wf_dir, f), "r", encoding="utf-8") as fh:
                idx = json.load(fh)
                commits = idx.get("commits", [])
                total_snapshots += len(commits)
        if any_wf:
            repos_with_wf.add((org, repo))

print(f"Repositories with workflows: {len(repos_with_wf)}")
print(f"Workflow files: {workflow_files}")
print(f"Total workflow snapshots (across all commits): {total_snapshots}")
