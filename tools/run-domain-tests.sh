#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLIN_COMPILER=${KOTLIN_COMPILER:-/opt/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc}
JUNIT_JAR=${JUNIT_JAR:-/opt/android-studio/lib/junit4.jar}
HAMCREST_JAR=${HAMCREST_JAR:-/opt/android-studio/lib/testFramework.jar}
GSON_JAR=${GSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0" -name '*.jar' -print -quit)}
OUT_DIR=$(mktemp -d /tmp/fitprogress-tests.XXXXXX)
trap 'rm -rf "$OUT_DIR"' EXIT
"$KOTLIN_COMPILER" app/src/main/java/ru/fitprogress/ai/Domain.kt app/src/main/java/ru/fitprogress/ai/SessionLogic.kt app/src/main/java/ru/fitprogress/ai/TrainingPlans.kt app/src/main/java/ru/fitprogress/ai/BackupCodec.kt app/src/test/java/ru/fitprogress/ai/DomainTest.kt tools/OfflineTestRunner.kt -cp "$GSON_JAR:$JUNIT_JAR" -include-runtime -d "$OUT_DIR/tests.jar"
java -cp "$OUT_DIR/tests.jar:$GSON_JAR:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore ru.fitprogress.ai.DomainTest
