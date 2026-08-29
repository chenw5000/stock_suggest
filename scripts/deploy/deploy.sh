#!/usr/bin/env bash
# Build and redeploy StockSugg WAR to Apache Tomcat 10.1.x
# Usage: ./scripts/deploy/deploy.sh [TOMCAT_HOME] [--skip-build]
#   TOMCAT_HOME defaults to $CATALINA_HOME

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TOMCAT_HOME="${1:-${CATALINA_HOME:-}}"
PORT="${STOCKSUGG_PORT:-7070}"
SKIP_BUILD=false

if [[ "${2:-}" == "--skip-build" ]] || [[ "${1:-}" == "--skip-build" ]]; then
  SKIP_BUILD=true
  if [[ "${1:-}" == "--skip-build" ]]; then
    TOMCAT_HOME="${CATALINA_HOME:-}"
  fi
fi

if [[ -z "$TOMCAT_HOME" ]] || [[ ! -d "$TOMCAT_HOME" ]]; then
  echo "Error: Tomcat home not found. Pass path or set CATALINA_HOME." >&2
  exit 1
fi

export CATALINA_HOME="$TOMCAT_HOME"
export CATALINA_BASE="$TOMCAT_HOME"

cd "$REPO_ROOT"
echo "Repo:   $REPO_ROOT"
echo "Tomcat: $TOMCAT_HOME"
echo "Port:   $PORT"

if [[ "$SKIP_BUILD" == false ]]; then
  echo "Building WAR..."
  mvn -DskipTests package
fi

WAR="$REPO_ROOT/target/stocksugg.war"
if [[ ! -f "$WAR" ]]; then
  echo "Error: missing $WAR — run mvn -DskipTests package first." >&2
  exit 1
fi

echo "Stopping Tomcat..."
"$TOMCAT_HOME/bin/shutdown.sh" 2>/dev/null || true
sleep 6

if command -v lsof >/dev/null 2>&1; then
  PIDS=$(lsof -ti ":$PORT" 2>/dev/null || true)
  if [[ -n "$PIDS" ]]; then
    echo "Killing process(es) on port $PORT: $PIDS"
    kill $PIDS 2>/dev/null || true
    sleep 2
  fi
fi

WEBAPPS="$TOMCAT_HOME/webapps"
rm -rf "$WEBAPPS/stocksugg" "$WEBAPPS/stocksugg.war" "$WEBAPPS/stocksugg.war.failed" 2>/dev/null || true

echo "Copying WAR..."
cp -f "$WAR" "$WEBAPPS/stocksugg.war"

echo "Starting Tomcat..."
"$TOMCAT_HOME/bin/startup.sh"

HEALTH_URL="http://localhost:$PORT/stocksugg/health"
echo "Waiting for $HEALTH_URL ..."
ok=false
for i in $(seq 1 40); do
  sleep 2
  if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
    curl -s "$HEALTH_URL"
    echo
    ok=true
    break
  fi
  if (( i % 5 == 0 )); then echo "  wait $i..."; fi
done

if [[ "$ok" != true ]]; then
  echo "Error: health check failed at $HEALTH_URL" >&2
  exit 1
fi
echo "Deploy complete."
