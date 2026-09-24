#!/bin/sh
set -eu
BUILD_DIR="${TMPDIR:-/tmp}/creator-onboarding-classes"
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" $(find src/main/java -name '*.java' | sort)
java -cp "$BUILD_DIR" dev.infrai.creator.CreatorOnboardingCommand \
  signup-1042 Chenhua chenhua@changba.com - email \
  "Launch checklist" "https://downloads.example/launch-1042"
