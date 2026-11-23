#!/usr/bin/env bash
# Source this file to use the project-local Android toolchain.
task_project="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$task_project/.downloads/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$task_project/.downloads/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$task_project/.downloads/gradle-home}"
export CODEX_ANDROID_KEYSTORE="${CODEX_ANDROID_KEYSTORE:-$task_project/.private/android/debug.keystore}"
export CODEX_GOOGLE_CDN="${CODEX_GOOGLE_CDN:-1}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
