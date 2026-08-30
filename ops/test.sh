#!/usr/bin/env bash
# Unit tests only. Usage: ops/test.sh [extra mvn args, e.g. -Dtest=SomeTest]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

resolve_java
cd "$REPO_ROOT"
sh ./mvnw -B test "$@"
