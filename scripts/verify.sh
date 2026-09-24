#!/bin/sh
set -eu
BUILD_DIR="${TMPDIR:-/tmp}/creator-onboarding-classes"
mkdir -p "$BUILD_DIR"
find "$BUILD_DIR" -type f -name '*.class' -delete
javac -d "$BUILD_DIR" $(find src/main/java src/test/java -name '*.java' | sort)
java -cp "$BUILD_DIR" dev.infrai.creator.CreatorOnboardingServiceTest
