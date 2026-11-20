#!/bin/sh
set -eu
app_dir=$(CDPATH='' cd -P "$(dirname "$0")" && pwd)
java_cmd=java
if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
fi
exec "$java_cmd" -classpath "$app_dir/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
