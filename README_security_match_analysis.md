# Security Match Analysis Notebook

This folder contains a Jupyter notebook and summary outputs that document the distribution of security-related matches in commit messages and workflow changes.

## Contents

- `security_match_analysis.ipynb` — notebook to analyze matches
- `summary_by_project.csv` — counts of matches per project (`org/repo`)
- `summary_by_regex.csv` — counts of matches per regex pattern
- `summary_cross_projects_x_regex.csv` — cross-tab of projects × regex patterns
- `figs/` — generated figures (PNG bar charts)

## Usage

1. Ensure you have the CSV file produced by the scanning script, usually at `out_security_scan/hits.csv`.
2. Open the notebook in Jupyter or VSCode and run all cells.
   - If your CSV is stored elsewhere, adjust the `CSV_PATH` variable in the first code cell.
3. The notebook will:
   - Report the **total number of matches**
   - Show the **distribution across projects**
   - Show the **distribution across regex patterns**
   - Save bar charts into the `figs/` folder
   - Save summary tables as CSV files

## Example Outputs

- Top 20 projects by matches: `figs/matches_by_project_top20.png`
- Top 20 regex patterns by matches: `figs/matches_by_regex_top20.png`

## Contribution

Push the notebook and generated summaries to the repository to document the experiment and share reproducible analysis.
