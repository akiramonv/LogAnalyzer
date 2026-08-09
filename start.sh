#!/usr/bin/env sh
# ---------------------------------------------------------------
#  Единая точка входа: запускает проект одной командой.
#  Собирает его при необходимости и открывает веб-интерфейс.
#      ./start.sh              — интерфейс на порту по умолчанию
#      ./start.sh --port 9000  — любые ключи команды ui
# ---------------------------------------------------------------
set -e

cd "$(dirname "$0")"
JAR="cli/build/libs/log-analyzer.jar"

# В PATH может лежать старая Java, поэтому сначала пробуем JAVA_HOME.
JAVA="java"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi

if ! "$JAVA" -version >/dev/null 2>&1; then
    echo "[!] Java не найдена. Установите JDK 21+ или задайте JAVA_HOME."
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "Собираю проект, это займёт около минуты..."
    ./gradlew -q :cli:fatJar
    echo "Сборка завершена."
    echo
fi

echo "Запускаю интерфейс анализатора логов..."
echo "Браузер откроется на http://localhost:8321"
echo "Остановить: Ctrl+C"
echo

exec "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "$JAR" ui "$@"
