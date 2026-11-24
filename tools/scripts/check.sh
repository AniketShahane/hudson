#!/bin/bash
# Fast local check for an Android project when the Android SDK cannot be installed:
# compiles the Android-free Kotlin (core, models, domain logic) with plain kotlinc and runs
# its JUnit tests. Seconds instead of a CI round trip.
#
# Set these for the project (defaults are the Wingtip layout):
#   ROOT      the app module                       (default: ./app)
#   PKG_DIR   the main source root of the package  (default: $ROOT/src/main/java/app/aloft)
#   SOURCES   files and folders that import nothing from android.* (space separated, relative to PKG_DIR)
#   KOTLINC   an unpacked kotlinc matching the project's Kotlin version
#             (https://github.com/JetBrains/kotlin/releases -> kotlin-compiler-<version>.zip)
#   JARS      folder holding junit-4.x, hamcrest-core, kotlinx-coroutines-core-jvm and
#             kotlinx-serialization-{core,json}-jvm jars (a Gradle distribution's lib/ folder has them)
#   STUBS     folder of stand-in annotations (see scripts/stubs) for libraries the models are annotated with
set -e
ROOT=$(cd "${ROOT:-./app}" && pwd)
PKG_DIR=${PKG_DIR:-$ROOT/src/main/java/app/aloft}
SOURCES=${SOURCES:-"core data/model domain"}
KOTLINC=${KOTLINC:-$HOME/kt/kotlinc}
JARS=${JARS:-/opt/gradle/lib}
STUBS=${STUBS:-$(cd "$(dirname "$0")" && pwd)/stubs}
OUT=${OUT:-/tmp/kt-check-out}

CP=$(ls "$JARS"/kotlinx-serialization-core-jvm-*.jar "$JARS"/kotlinx-serialization-json-jvm-*.jar \
        "$JARS"/kotlinx-coroutines-core-jvm-*.jar "$JARS"/junit-4*.jar "$JARS"/hamcrest-core-*.jar 2>/dev/null | tr '\n' ':')
CP="$CP$KOTLINC/lib/kotlin-test.jar:$KOTLINC/lib/kotlin-test-junit.jar"
FILES=""
for s in $SOURCES; do FILES="$FILES $PKG_DIR/$s"; done
FILES="$FILES $STUBS $ROOT/src/test/java"

rm -rf "$OUT" && mkdir -p "$OUT"
JAVA_TOOL_OPTIONS= "$KOTLINC/bin/kotlinc" \
  -Xplugin="$KOTLINC/lib/kotlin-serialization-compiler-plugin.jar" \
  -opt-in=kotlinx.serialization.ExperimentalSerializationApi \
  -jvm-target 17 -nowarn -cp "$CP" -d "$OUT" $FILES 2>&1 | grep -v "^warning:" || true
[ -n "$(find "$OUT" -name '*.class' -print -quit)" ] || { echo "COMPILE FAILED"; exit 1; }

# Every top-level *Test class under the test source root.
TESTS=$(cd "$OUT" && find . -name '*Test.class' ! -name '*$*' | sed 's#^\./##; s/\.class$//; s#/#.#g' | sort | tr '\n' ' ')
# Tests read the app's bundled files by relative path, so run them from the module.
cd "$ROOT"
JAVA_TOOL_OPTIONS= java -cp "$OUT:$CP:$KOTLINC/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $TESTS 2>&1 \
  | grep -v "^\s*at org.junit\|^\s*at java.base\|^\s*at jdk.internal\|^$" | head -${LINES_MAX:-80}
