#!/usr/bin/env bash
# Prints JaCoCo coverage totals as a Markdown table (used for the GitHub Actions job summary).
# Usage: scripts/coverage-summary.sh [path/to/jacoco.csv]
set -euo pipefail

csv="${1:-target/site/jacoco/jacoco.csv}"

# jacoco.csv columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,
# BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
awk -F, '
  NR > 1 { im += $4; ic += $5; bm += $6; bc += $7; lm += $8; lc += $9; mm += $12; mc += $13 }
  function row(name, covered, missed) {
    printf "| %s | %.1f%% | %d / %d |\n", name, (covered + missed ? 100 * covered / (covered + missed) : 100), covered, covered + missed
  }
  END {
    print "### Test coverage (JaCoCo)"
    print ""
    print "| Counter | Coverage | Covered / total |"
    print "|---|---|---|"
    row("Lines", lc, lm)
    row("Branches", bc, bm)
    row("Methods", mc, mm)
    row("Instructions", ic, im)
  }
' "$csv"
