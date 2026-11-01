#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLIN_COMPILER=${KOTLIN_COMPILER:-/opt/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc}
JUNIT_JAR=${JUNIT_JAR:-/opt/android-studio/lib/junit4.jar}
HAMCREST_JAR=${HAMCREST_JAR:-/opt/android-studio/lib/testFramework.jar}
GSON_JAR=${GSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0" -name '*.jar' -print -quit)}
SQLITE_JAR=$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc/3.41.2.2" -g "*.jar" | head -n 1)
SLF4J_JAR=$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.30" -g "*.jar" | head -n 1)
OUT_DIR=$(mktemp -d /tmp/fitprogress-tests.XXXXXX)
trap 'rm -rf "$OUT_DIR"' EXIT
"$KOTLIN_COMPILER" app/src/main/java/ru/fitprogress/ai/Domain.kt app/src/main/java/ru/fitprogress/ai/SessionLogic.kt app/src/main/java/ru/fitprogress/ai/TrainingPlans.kt app/src/main/java/ru/fitprogress/ai/SchemaMigrations.kt app/src/main/java/ru/fitprogress/ai/PersonalData.kt app/src/main/java/ru/fitprogress/ai/AdaptiveCoach.kt app/src/main/java/ru/fitprogress/ai/BackupCodec.kt app/src/test/java/ru/fitprogress/ai/*.kt tools/OfflineTestRunner.kt -cp "$GSON_JAR:$JUNIT_JAR:$SQLITE_JAR:$SLF4J_JAR" -include-runtime -d "$OUT_DIR/tests.jar"
java -cp "$OUT_DIR/tests.jar:$GSON_JAR:$JUNIT_JAR:$HAMCREST_JAR:$SQLITE_JAR:$SLF4J_JAR" org.junit.runner.JUnitCore ru.fitprogress.ai.DomainTest ru.fitprogress.ai.PersonalTest ru.fitprogress.ai.MigrationSqlTest
