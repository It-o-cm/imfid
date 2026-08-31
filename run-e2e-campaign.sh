#!/bin/bash
# Headless e2e-generation campaign — one run per group letter, one commit per group.
#
# JUDGE RULE — a group is judged on the TREE, not on claude's prose. Two
# branches count as success:
#   (1) the expected commit "test: e2e scenarios group $g" is at HEAD, OR
#   (2) the e2e perimeter is CLEAN — nothing to commit: an empty-handed
#       success (all [P]/@Disabled, or already committed) is still a success.
# Anything else is a failure. Failures are NOTED (appended to FAILED) and the
# campaign CONTINUES — a red group never freezes the run. At the end the FAILED
# list is printed and the script exits 1 iff it is non-empty.
set -eo pipefail
GRPS="${@:?usage: run-e2e-campaign.sh <group> [group...] e.g. A B F}"
PERIMETER="src/test/java/com/intermarche/fidelity/e2e"
# 1) Validate arguments BEFORE invoking claude: each group must be a unique
#    valid letter (A-O or Q — P is the prod-like tier, not a generated group).
#    Any offender stops the script immediately with a message.
seen=""
for g in $GRPS; do
  case "$g" in
    [A-O]|Q) ;;
    *) echo "ABORT: invalid group '$g' — expected one of A B C D E F G H I J K L M N O Q."; exit 1 ;;
  esac
  case " $seen " in
    *" $g "*) echo "ABORT: duplicate group '$g' — each letter must appear once."; exit 1 ;;
  esac
  seen="$seen $g"
done
FAILED=""
for g in $GRPS; do
  echo "=== Group $g ==="
  # Generate/complete the group's *IT class and loop it to green.
  claude -p "/gen-e2e-group $g" 2>&1 | tee -a campaign-e2e.log || { echo "NOTE: Group $g — generation call errored, continuing to judge."; }
  # Verify + commit. claude runs the campaign command and, only if green,
  # commits with the exact expected message; if red it commits nothing.
  claude -p "Lance mvn -q verify -DskipUTs=true -Dit.test=Group${g}IT -DskipITs=false. Si tout est vert : signale les scénarios couverts et le résidu justifié ([P] @Disabled), puis stage uniquement le périmètre $PERIMETER (plus CLAUDE.md, .claude/ et reports/ s'ils ont changé) et commite avec exactement ce message : test: e2e scenarios group $g — jamais git push. Si quelque chose est rouge : ne commite rien et explique." 2>&1 | tee -a campaign-e2e.log || true
  # JUDGE — two branches (see header). Branch 1: expected commit at HEAD.
  expected="test: e2e scenarios group $g"
  actual="$(git log -1 --format=%s)"
  if [ "$actual" = "$expected" ]; then
    echo "OK: Group $g — expected commit landed at HEAD."
    continue
  fi
  # Branch 2: clean perimeter = empty-handed success (nothing to commit).
  if [ -z "$(git status --porcelain -- "$PERIMETER")" ]; then
    echo "OK: Group $g — perimeter clean, nothing to commit (empty-handed success)."
    continue
  fi
  # Neither branch held: NOTE and CONTINUE — never freeze the campaign.
  echo "FAILED: Group $g — no '$expected' at HEAD and perimeter '$PERIMETER' is dirty."
  FAILED="$FAILED $g"
done
# 2) Final tally: note-and-continue means the verdict is delivered here.
if [ -n "$FAILED" ]; then
  echo "Campaign done with FAILURES:$FAILED"
  exit 1
fi
echo "Campaign done — all groups green."
