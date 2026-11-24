#!/bin/bash
# Compiles core/, data/feed/ and domain/ with plain kotlinc and runs their JUnit tests (seconds).
cd "$(dirname "$0")/.." && mkdir -p /tmp/nostubs
ROOT=app PKG_DIR=app/src/main/java/app/hudson SOURCES="core data/feed domain" \
  KOTLINC=${KOTLINC:-$HOME/kt/kotlinc} JARS=${JARS:-/opt/gradle/lib} STUBS=/tmp/nostubs \
  bash tools/scripts/check.sh
