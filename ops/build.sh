#!/usr/bin/env bash
# Full Maven build incl. tests (Java 21 via SDKMAN). Usage: ops/build.sh [--skip-tests]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

resolve_java
cd "$REPO_ROOT"

if [[ "${1:-}" == "--skip-tests" ]]; then
  log "Building without tests..."
  sh ./mvnw -B clean package -DskipTests
else
  log "Building with tests..."
  sh ./mvnw -B clean package
fi
log "Build done. Artifact: target/Cosmic.jar"
