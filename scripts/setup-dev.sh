#!/usr/bin/env bash
#
# setup-dev.sh — bootstrap the neo-voxy development environment.
#
# Everything the build needs is resolved by Gradle (Minecraft through
# ModDevGradle, mod dependencies such as Sodium, Iris and Chunky through the
# Modrinth Maven), so a fresh clone only needs a JVM to launch Gradle:
#
#   1. A launcher JVM        (downloads Temurin 21 only if no java is found;
#                             the Java 21 *compile* toolchain is auto-provisioned
#                             by Gradle via the foojay resolver — see settings.gradle)
#   2. ./gradlew build       (prepares Minecraft, compiles, runs unit tests;
#                             use --quick to compile only, --no-build to skip)
#
# Idempotent: safe to re-run. Works under Git Bash on Windows and natively on
# Linux. Windows users can run scripts/setup-dev.ps1 instead.
#
# Usage:
#   scripts/setup-dev.sh [--quick] [--no-build] [--help]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------- logging ----
log()  { printf '\033[1;34m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup:warn]\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31m[setup:err]\033[0m %s\n'  "$*" >&2; }
have() { command -v "$1" >/dev/null 2>&1; }

# --------------------------------------------------------------- args ----
BUILD_TASK="build"
for a in "$@"; do
  case "$a" in
    --quick)        BUILD_TASK="compileJava" ;;
    --no-build)     BUILD_TASK="" ;;
    --help|-h)
      sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) warn "unknown argument: $a" ;;
  esac
done

# --------------------------------------------------------- prerequisites ----
have curl || { err "curl is required but not found on PATH"; exit 1; }

# Read a property from gradle.properties (strip CRLF).
gprop() { sed -n "s/^$1=//p" gradle.properties | tr -d '\r' | head -n1; }
MC_VERSION="$(gprop minecraft_version)"; MC_VERSION="${MC_VERSION:-1.21.1}"

detect_os()   { case "$(uname -s)" in
  Linux*)              echo linux   ;;
  Darwin*)             echo mac     ;;
  MINGW*|MSYS*|CYGWIN*) echo windows ;;
  *)                   echo unknown ;; esac; }
detect_arch() { case "$(uname -m)" in
  x86_64|amd64)  echo x64      ;;
  aarch64|arm64) echo aarch64  ;;
  *)             echo x64      ;; esac; }
OS="$(detect_os)"; ARCH="$(detect_arch)"

dl() { # dl <url> <out>
  log "download → $2"
  curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$2" "$1"
}

# ------------------------------------------------------------------ java ----
ensure_java() {
  if have java; then
    log "java: launcher JVM present — $(java -version 2>&1 | head -n1)"
    return 0
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    log "java: using JAVA_HOME=$JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    return 0
  fi

  log "java: no JVM found — fetching a Temurin 21 JDK for $OS/$ARCH"
  mkdir -p .dev
  local ext="tar.gz"; [ "$OS" = windows ] && ext="zip"
  local arc=".dev/jdk-download.$ext"
  dl "https://api.adoptium.net/v3/binary/latest/21/ga/${OS}/${ARCH}/jdk/hotspot/normal/eclipse?project=jdk" "$arc"

  rm -rf .dev/jdk && mkdir -p .dev/jdk
  if [ "$ext" = zip ]; then unzip -q "$arc" -d .dev/jdk; else tar -xzf "$arc" -C .dev/jdk; fi
  rm -f "$arc"

  local d; d="$(find .dev/jdk -mindepth 1 -maxdepth 1 -type d | head -n1)"
  [ -d "$d/Contents/Home" ] && d="$d/Contents/Home"   # macOS layout
  export JAVA_HOME="$(cd "$d" && pwd)"
  export PATH="$JAVA_HOME/bin:$PATH"
  log "java: installed JDK 21 at $JAVA_HOME"
  warn "this JDK is local to the repo; to reuse it in other shells run:"
  warn "    export JAVA_HOME=\"$JAVA_HOME\""
}

# ----------------------------------------------------------------- build ----
run_build() {
  [ -n "$BUILD_TASK" ] || { log "build: skipped (--no-build)"; return 0; }
  local gw="./gradlew"
  [ -x "$gw" ] || gw="sh ./gradlew"
  log "build: ./gradlew $BUILD_TASK (first run prepares Minecraft — can take a few minutes)"
  $gw $BUILD_TASK
}

# ------------------------------------------------------------------ main ----
log "neo-voxy dev bootstrap — MC $MC_VERSION, $OS/$ARCH"
ensure_java
run_build
log "done. You can now build with:  ./gradlew build   (or: rtk ./gradlew build)"
