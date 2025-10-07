# Regex List and Methodology

## 1. Regex List Used

*(All patterns are case-insensitive; `\b` denotes word boundaries.)*

```
# Core seeds (from supervisor's suggestion)
\bfix(?:es|ed)?\b
\bvuln(?:erability|erable|erabilities)?\b
\bpin(?:ned|ning)?\b
\binject(?:ion|ions|ed|s)?\b

# General security verbs/nouns
\bsecurity\b
\bsecure\b
\bharden(?:ed|ing)?\b
\bmitigat(?:e|ion|ed|ing)\b
\bprotect(?:ion|ed|ing)?\b
\bpatch(?:ed|ing)?\b
\bhotfix\b

# Secrets and credentials
\bsecret(?:s)?\b
\bcredential(?:s)?\b
\btoken(?:s)?\b
\brotate(?:d|ion)?\b
\brevoke(?:d|s|ing)?\b
\bexpos(?:e|ed|ure)\b
\b(leak|leaked|leaking|leaks)\b

# Vulnerability classes / shorthand
\bxss\b
\bcsrf\b
\bsqli?\b
\brce\b
\bssrf\b
\bpath traversal\b

# Advisories and identifiers
\bCVE[- ]?\d{4}-\d{3,7}\b
\bGHSA[-\w]+\b
\bsecurity[- ]?advisory\b

# GitHub Actions / CI-CD specifics
\bpull_request_target\b
\bpermissions:\b
\bGITHUB_TOKEN\b
\bcontents:\s*(read|write|none)\b
\bid-token:\s*(read|write)\b
\bworkflow_call\b
\bworkflow_run\b
\bset-output\b
\bsave-state\b
\buses:\b
@v\d+(?:\.\d+){0,2}
@[0-9a-fA-F]{40}
\bpin(?: to| to sha)\b
\bunpin(?:ned|ning)?\b

# Dependency bumps often tied to security
\bdependabot\b
\bupgrade\b
\bbump(?:s|ed|ing)?\b
\bpkg(?:-)?update\b
\bchore\(deps\)\b
\bsecurity update\b

# Sanitization and validation
\bsanitiz(?:e|ed|ing|ation)\b
\bescape(?:d|s|ing)?\b
\bvalidate(?:d|s|ing|ion)\b
\binput validation\b

# AuthZ/AuthN and permissions
\bauth(?:n|z|entication|orization)?\b
\bpermission(?:s)?\b
\brole(?:s)?\b
\bscope(?:s)?\b
\bpolicy\b

# Tightening verbs (often security-motivated)
\brestrict(?:ed|s|ion|ing)?\b
\blimit(?:ed|s|ing)?\b
\bdrop(?:ped)?\b
\bdeny(?:list|listed|ing)?\b
\ballow(?:list|listed|ing)?\b

# Maintenance patterns that may hide security content
\bbackport(?:ed|ing)?\b
\bcherry[- ]?pick(?:ed|ing)?\b
\brevert(?:ed|ing)?\b
```

---

## 2. How the List Was Built

1. **Initial seeds** — started from the terms suggested by Prod. Benoit Baudry (*fix*, *vulnerability*, *pinning*, *injection*).  
2. **Expanded coverage** — added:
   - Common vulnerability classes (*XSS, CSRF, SQLi, RCE, SSRF*).  
   - Identifiers like *CVE*, *GHSA*.  
   - CI/CD–specific markers (`pull_request_target`, `permissions:`, `GITHUB_TOKEN`, `id-token`).  
   - Secret-handling terms (*rotate*, *revoke*, *leak*).  
   - Dependency update markers (*dependabot*, *bump*, *upgrade*).  
3. **Morphological variants** — used regex stems and inflections (e.g., `sanitiz(e|ed|ing)`).  
4. **Boundaries for precision** — applied `\b` to avoid partial matches.  

---

## 3. Soundness and Completeness

### Soundness (Precision)
- **Context-aware patterns:** Most terms are strongly tied to security or workflow configuration (e.g., `pull_request_target`, `CVE`).
- **Scoped tokens and boundaries:** Regex boundaries and explicit tokens (`@[0-9a-fA-F]{40}`, `@v\d+`) prevent noise.
- **Diff corroboration:** Each regex match is coupled with the corresponding *workflow diff*—pinning, permission, or `run:` changes—providing concrete evidence and reducing false positives.

### Completeness (Recall)
- **Taxonomic breadth:** Covers general security terms, vulnerability identifiers, workflow risks, secret management, dependency updates.
- **Morphological coverage:** Captures common inflections and plural forms.
- **Empirical validation:** Periodic review of non-matching commits from workflow-heavy repos ensured few obvious misses.
- **Heuristic completeness:** While linguistic completeness isn’t provable, the design aims for high recall with manageable noise.

---

## 4. Validation Approach

- **Manual sampling:** Labeled random subsets of matches and non-matches to estimate precision and recall.  
- **Error analysis:**  
  - False positives (e.g., “fix typo”) → potential future allowlist like `fix (typo|docs|format)`.  
  - False negatives (e.g., nonstandard phrasing) → iterative addition if beneficial.  
- **Reproducibility:** Regex list is versioned as `security_keywords_recommended.txt`.

---

## 5. Repository Locations

- **Regex list:** [`security_keywords_recommended.txt`](/security_keywords_recommended.txt)  
- **Scan results:** [`out_security_scan/hits.csv`](/out_security_scan/hits.csv)  
- **Notebook & plots:** [`security_match_analysis.ipynb`](/security_match_analysis.ipynb)  
- **Summaries:** [`summary_by_project.csv`](out_security_scan/summary_by_project.csv), [`summary_by_regex.csv`](out_security_scan/summary_by_regex.csv), [`summary_cross_projects_x_regex.csv`](out_security_scan/summary_cross_projects_x_regex.csv)

---

**In summary**, the regex list is a versioned, empirically refined heuristic designed for *high recall* of security-related commits while maintaining *soundness* through context-specific patterns and workflow diff validation.
