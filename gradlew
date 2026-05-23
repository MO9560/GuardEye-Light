#!/bin/sh
DIRNAME=$(dirname "$0")
exec java -Xmx2048m -Dfile.encoding=UTF-8 -classpath "$DIRNAME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
