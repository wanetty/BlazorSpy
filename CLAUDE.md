# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

**BlazorSpy** — Burp Suite extension that decodes/encodes Blazor Server WebSocket frames (BlazorPack = varint32 + MessagePack) into readable/editable JSON.

Java (`blazorpack-decode/`) — Gradle project, Montoya API (`BurpExtension`). Works in Burp 2023+ with full HTTP + WebSocket message editor tabs.

## Versioning

Follow [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- **MAJOR**: breaking changes to the extension behavior or Burp API compatibility.
- **MINOR**: new features, new UI controls, expanded protocol support.
- **PATCH**: bug fixes, docs-only changes, refactors with no behavior change.

### How to release

1. Bump `version` in `build.gradle` (the Gradle default, NOT the `-Pversion=` override).
2. Update the version badge in `README.md`.
3. Commit on `main`: `git commit -m "chore: bump to vX.Y.Z"`
4. Tag on `main` ONLY: `git tag -a vX.Y.Z -m "vX.Y.Z — <summary>"`
5. Push: `git push origin main --tags`

The CI workflow (`build.yml`) runs tests + builds the JAR on every push. The **release** job only fires on tag pushes (`refs/tags/v*`) and **verifies the tag is reachable from `main`** — tags on `dev` are rejected.

### Current version

- `build.gradle` default: `1.0.1`
- Latest tag: `v1.0.1`

## Build & Deploy

```bash
cd blazorpack-decode
./gradlew jar
```

Output: `build/libs/BlazorSpy-1.0.1.jar` (fat JAR — includes msgpack-core + gson).

To deploy: load the `.jar` in Burp (Extender → Extensions → Add → Type: Java).

## Architecture

- **Main Entry Point**: `src/main/java/Extension.java` - implements `BurpExtension` interface
- **Build System**: Gradle with Kotlin DSL, Java 21 compatibility
- **Dependencies**: Montoya API 2025.5 (compile-only), no runtime dependencies
- **Extension Pattern**: Single-class extension that initializes through `initialize(MontoyaApi montoyaApi)` method

## Key Development Commands

```bash
./gradlew build    # Build and test the extension
./gradlew jar      # Create the extension JAR file
./gradlew clean    # Clean build artifacts
```

The built JAR file will be in `build/libs/` and can be loaded directly into Burp Suite.

## Extension Loading in Burp

1. Build the JAR using `./gradlew jar`
2. In Burp: Extensions > Installed > Add > Select the JAR file
3. For quick reloading during development: Ctrl/⌘ + click the Loaded checkbox

## Documentation Structure

- See @docs/bapp-store-requirements.md for BApp Store submission requirements
- See @docs/montoya-api-examples.md for code patterns and extension structure  
- See @docs/development-best-practices.md for development guidelines
- See @docs/resources.md for external documentation and links
