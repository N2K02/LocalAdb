#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="9fa7f288283f81eec4952784c271abd7e0938dee"
if [ ! -s "$JAR" ]; then
  mkdir -p "$(dirname "$JAR")"
  TEMP="$JAR.download.$$"
  trap 'rm -f "$TEMP"' EXIT HUP INT TERM
  curl --fail --location --silent --show-error --retry 3 --output "$TEMP" \
    https://raw.githubusercontent.com/N2K02/MRadio/main/gradle/wrapper/gradle-wrapper.jar
  ACTUAL=$(git hash-object "$TEMP")
  if [ "$ACTUAL" != "$EXPECTED" ]; then echo "Gradle wrapper checksum mismatch" >&2; exit 1; fi
  mv "$TEMP" "$JAR"
fi
[ "$(git hash-object "$JAR")" = "$EXPECTED" ] || { echo "Gradle wrapper checksum mismatch" >&2; exit 1; }
exec java -Dorg.gradle.appname=LocalAdb -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
