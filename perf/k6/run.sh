#!/usr/bin/env bash
# FreightScaler k6 load test runner.
#
# SAFETY: This script is designed to run against STAGING ONLY.
# It refuses to run if BASE_URL contains "prod" or the production domain.
#
# Prerequisites:
#   - k6 installed (https://grafana.com/docs/k6/latest/set-up/install-k6/)
#   - A deployed staging environment
#
# Usage:
#   ./perf/k6/run.sh <scenario> [extra k6 args]
#
# Examples:
#   ./perf/k6/run.sh route_planning
#   ./perf/k6/run.sh mixed -e MAX_VUS=30
#   ./perf/k6/run.sh ai_chat -e DURATION=1m
#   ./perf/k6/run.sh auth_flow -e AUTH_USERNAME=test@example.com -e AUTH_PASSWORD=secret

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="${1:-mixed}"
shift 2>/dev/null || true

# --- Safety checks -----------------------------------------------------------

BASE_URL="${BASE_URL:-}"
if [ -z "$BASE_URL" ]; then
  # Try to extract from k6 args (-e BASE_URL=...)
  for arg in "$@"; do
    case "$arg" in
      BASE_URL=*) BASE_URL="${arg#BASE_URL=}" ;;
    esac
  done
fi

if [ -z "$BASE_URL" ]; then
  echo "ERROR: Set BASE_URL to your staging deployment."
  echo "  export BASE_URL=https://staging.freightscaler.com"
  echo "  or: ./perf/k6/run.sh $SCENARIO -e BASE_URL=https://your-staging-url"
  exit 1
fi

# Production guard: refuse to run against anything that looks like prod.
case "$BASE_URL" in
  *prod*|*production*|*freightscaler.com)
    if [ "$BASE_URL" != "https://staging.freightscaler.com" ]; then
      echo "ERROR: BASE_URL looks like production ($BASE_URL)."
      echo "Load tests must ONLY target staging. Aborting."
      exit 1
    fi
    ;;
esac

# --- Resolve scenario script --------------------------------------------------

SCENARIO_FILE="${SCRIPT_DIR}/scenarios/${SCENARIO}.js"
if [ ! -f "$SCENARIO_FILE" ]; then
  echo "ERROR: Unknown scenario '$SCENARIO'."
  echo "Available scenarios:"
  ls "${SCRIPT_DIR}/scenarios/"*.js 2>/dev/null | xargs -I{} basename {} .js | sed 's/^/  - /'
  exit 1
fi

# --- Run ----------------------------------------------------------------------

echo "=== FreightScaler k6 Load Test ==="
echo "Scenario: ${SCENARIO}"
echo "Target:   ${BASE_URL}"
echo "Extra:    $*"
echo ""

k6 run "$SCENARIO_FILE" -e BASE_URL="$BASE_URL" "$@"
