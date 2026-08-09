#!/bin/bash
# Headless unit test-generation campaign for imfid — one claude run per class, one
# commit per package. Model: imvaluation's run-unit-campaign.sh, hardened.
#
# JUDGE RULE (doctrine) — the campaign never trusts claude's prose; it judges the
# git state, and it judges on TWO BRANCHES:
#   1. the expected commit landed as HEAD  (new tests were produced and committed), OR
#   2. the working tree is clean over the package's test perimeter  (nothing left to
#      commit — every class already had complete tests, so there was nothing to add).
# Either branch counts as SUCCESS for the package. Any other state is a FAILURE.
#
# On failure the campaign does NOT freeze: it is NOTE-AND-CONTINUE. Each failing
# package is appended to FAILED, the remaining packages still run, and a final
# summary reports what passed and what failed (non-zero exit only if FAILED is
# non-empty). A single stuck package can never block the rest of the campaign.
set -eo pipefail

PKGS="${@:?usage: run-unit-campaign.sh <pkg> [pkg...] e.g. domain rule.appliers earn}"
ROOT="com.intermarche.fidelity"
SRC_BASE="src/main/java/com/intermarche/fidelity"
TEST_BASE="src/test/java/com/intermarche/fidelity"
LOG="campaign-unit.log"
FAILED=""

for pkg in $PKGS; do
  dir="$SRC_BASE/${pkg//.//}"
  testdir="$TEST_BASE/${pkg//.//}"
  echo "########## package $ROOT.$pkg ##########" | tee -a "$LOG"
  # Per-class generation. A class whose <Class>Test.java already exists is skipped
  # here (gen-tests would only re-open it); the package-level verify below still
  # covers it.
  for f in "$dir"/*.java; do
    [ -f "$f" ] || continue
    c=$(basename "$f" .java)
    fqcn="$ROOT.${pkg}.${c}"
    [ -f "$testdir/${c}Test.java" ] && { echo "--- skip (test exists): $fqcn" | tee -a "$LOG"; continue; }
    echo "=== $fqcn ===" | tee -a "$LOG"
    claude -p "/gen-tests $fqcn" 2>&1 | tee -a "$LOG" || echo "WARN: gen-tests returned non-zero for $fqcn — the package judge decides." | tee -a "$LOG"
  done

  # End of package: full verify, then commit only this package's test perimeter.
  expected="test: full branch coverage for $ROOT.$pkg"
  claude -p "Lance mvn -q verify -DskipITs complet. Si tout est vert : donne la couverture de branches JaCoCo de chaque classe du package $ROOT.$pkg au format « classe : n/n (%) », signale toute classe sans test, puis stage uniquement les classes de test de ce package (plus CLAUDE.md et .claude/ s'ils ont changé) et commite avec EXACTEMENT ce message : $expected — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a "$LOG" || echo "WARN: package-verify run for $pkg returned non-zero — the judge decides." | tee -a "$LOG"

  # JUDGE — two branches (see doctrine above).
  actual="$(git log -1 --format=%s)"
  perimeter_dirty="$(git status --porcelain -- "$testdir" 2>/dev/null)"
  if [ "$actual" = "$expected" ]; then
    echo "PASS: $pkg — expected commit is HEAD." | tee -a "$LOG"
  elif [ -z "$perimeter_dirty" ]; then
    echo "PASS: $pkg — clean test perimeter, nothing left to commit." | tee -a "$LOG"
  else
    echo "FAILED: $pkg — HEAD is '$actual' and $testdir still has uncommitted changes." | tee -a "$LOG"
    FAILED="$FAILED $pkg"
  fi
done

echo "================ campaign summary ================" | tee -a "$LOG"
if [ -n "$FAILED" ]; then
  echo "FAILED packages:$FAILED" | tee -a "$LOG"
  echo "Campaign finished WITH failures." | tee -a "$LOG"
  exit 1
fi
echo "All packages passed. Campaign done." | tee -a "$LOG"
