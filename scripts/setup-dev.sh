#!/usr/bin/env bash
#
# setup-dev.sh — bootstrap the neo-voxy development environment.
#
# Provisions everything a fresh clone needs but does NOT carry in git
# (the whole `libs/` directory is .gitignored), plus a JDK if the machine
# has none:
#
#   1. A launcher JVM        (downloads Temurin 21 only if no java is found;
#                             the Java 21 *compile* toolchain is auto-provisioned
#                             by Gradle via the foojay resolver — see settings.gradle)
#   2. libs/chunky-nf/*.jar  (Chunky NeoForge build — required to compile the
#                             Chunky integration mixin; the Modrinth Maven only
#                             serves the Forge variant, so we pull it from the API)
#   3. libs/*.jar            (Sodium's bundled JarJars — needed by runClient;
#                             best-effort, extracted from the Sodium artifact)
#   4. ./gradlew genSources  (decompiled MC sources for IDE navigation;
#                             use --quick to compile only, --no-build to skip)
#
# Idempotent: safe to re-run. Works under Git Bash on Windows and natively on
# Linux/macOS. Windows users can run scripts/setup-dev.ps1 instead.
#
# Usage:
#   scripts/setup-dev.sh [--quick] [--no-build] [--force-chunky] [--help]
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
BUILD_TASK="genSources"   # default: full decompile for IDE
FORCE_CHUNKY=0
for a in "$@"; do
  case "$a" in
    --quick)        BUILD_TASK="compileJava" ;;
    --no-build)     BUILD_TASK="" ;;
    --force-chunky) FORCE_CHUNKY=1 ;;
    --help|-h)
      sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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

# ---------------------------------------------------------------- chunky ----
ensure_chunky() {
  mkdir -p libs/chunky-nf
  if [ "$FORCE_CHUNKY" = 0 ] && ls libs/chunky-nf/*.jar >/dev/null 2>&1; then
    log "chunky: already present ($(ls libs/chunky-nf/*.jar | xargs -n1 basename | paste -sd, -))"
    return 0
  fi

  log "chunky: resolving NeoForge build for MC $MC_VERSION via Modrinth API"
  # Filter to neoforge builds for our MC version; the API returns them newest
  # first, so the first non-sources jar URL is the build we want. (Plain grep is
  # used deliberately: jq isn't always installed and the Windows `python3` is
  # often the Microsoft Store stub that just errors out.)
  local api="https://api.modrinth.com/v2/project/chunky/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%22${MC_VERSION}%22%5D"
  local json; json="$(curl -fsSL "$api")"

  local url filename
  url="$(printf '%s' "$json" \
        | grep -oE 'https://cdn\.modrinth\.com/[^"]+\.jar' \
        | grep -viE 'sources|javadoc' \
        | head -n1)"
  [ -n "$url" ] || { err "chunky: no NeoForge build found for MC $MC_VERSION (Modrinth API)"; return 1; }
  filename="$(basename "$url")"
  dl "$url" "libs/chunky-nf/$filename"
  log "chunky: installed libs/chunky-nf/$filename"
}

# --------------------------------------------------------- sodium jarjars ----
ensure_sodium_jarjars() {
  # build.gradle adds libs/*.jar as runtimeOnly/forgeRuntimeLibrary: these are
  # the nested jars Sodium ships via NeoForge JarJar (service + Fabric API
  # renderer modules) that Loom's dev runtime does not auto-extract. Only needed
  # for runClient, so failures here are non-fatal.
  if ls libs/*.jar >/dev/null 2>&1; then
    log "sodium jarjars: libs/*.jar already present, skipping"
    return 0
  fi
  local sv; sv="$(sed -nE 's/.*maven\.modrinth:sodium:([^"]+)".*/\1/p' build.gradle | head -n1)"
  sv="${sv:-mc1.21.1-0.6.13-neoforge}"
  log "sodium jarjars: extracting from sodium $sv"

  local jar; jar="$(mktemp).jar"
  local url="https://api.modrinth.com/maven/maven/modrinth/sodium/${sv}/sodium-${sv}.jar"
  if ! dl "$url" "$jar"; then
    warn "sodium: download failed — runClient may be missing JarJars ($url)"
    rm -f "$jar"; return 0
  fi
  local tmp; tmp="$(mktemp -d)"
  if unzip -o -q "$jar" 'META-INF/jarjar/*.jar' -d "$tmp" 2>/dev/null \
       && ls "$tmp"/META-INF/jarjar/*.jar >/dev/null 2>&1; then
    cp "$tmp"/META-INF/jarjar/*.jar libs/
    log "sodium jarjars: copied $(ls "$tmp"/META-INF/jarjar/*.jar | wc -l | tr -d ' ') jar(s) into libs/"
  else
    warn "sodium: no META-INF/jarjar entries found — skipping (runClient may still work)"
  fi
  rm -rf "$tmp" "$jar"
}

# ----------------------------------------------------------------- build ----
run_build() {
  [ -n "$BUILD_TASK" ] || { log "build: skipped (--no-build)"; return 0; }
  local gw="./gradlew"
  [ -x "$gw" ] || gw="sh ./gradlew"
  if [ "$BUILD_TASK" = genSources ]; then
    log "build: ./gradlew genSources (decompiling MC — can take a few minutes)"
  else
    log "build: ./gradlew $BUILD_TASK"
  fi
  $gw $BUILD_TASK
}

# ------------------------------------------------------------------ main ----
log "neo-voxy dev bootstrap — MC $MC_VERSION, $OS/$ARCH"
ensure_java
ensure_chunky
ensure_sodium_jarjars
run_build
log "done. You can now build with:  ./gradlew build   (or: rtk ./gradlew build)"
