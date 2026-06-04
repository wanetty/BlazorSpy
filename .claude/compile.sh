#!/bin/bash
# BlazorSpy — compile + package using Gradle wrapper
# Requires JDK (uses Burp Suite's bundled JDK on macOS by default)
set -e

cd "$(dirname "$0")/.."

BURP_JAVA="/Applications/Burp Suite Community Edition.app/Contents/Resources/jre.bundle/Contents/Home"

# Extract version from build.gradle (fallback to 1.0.0)
VERSION=$(grep -E "^version\s*=" blazorpack-decode/build.gradle | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | head -1)
VERSION=${VERSION:-1.0.0}
JAR_NAME="BlazorSpy-${VERSION}.jar"

echo "=== BlazorSpy — Compile & Package ==="
echo ""

# Detect Java: prefer Burp's bundled JDK, fall back to JAVA_HOME / PATH
if [ -x "$BURP_JAVA/bin/java" ]; then
    export JAVA_HOME="$BURP_JAVA"
    echo "Using Burp JDK: $JAVA_HOME"
elif [ -n "$JAVA_HOME" ]; then
    echo "Using JAVA_HOME: $JAVA_HOME"
else
    echo "Using system Java"
fi

# Build with Gradle wrapper
echo "[1/2] Compiling..."
cd blazorpack-decode
./gradlew jar --no-daemon 2>&1 | tail -5
cd ..

echo "  ✓ Compilation successful"

# Copy JAR to project root
echo "[2/2] Copying JAR..."
cp "blazorpack-decode/build/libs/$JAR_NAME" "./$JAR_NAME"

ls -lh "./$JAR_NAME"
echo ""
echo "=== Done! Load $JAR_NAME in Burp ==="
