# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Burp Suite extension that decodes/encodes Blazor Server WebSocket frames (BlazorPack = varint32 + MessagePack) into readable/editable JSON.

Java (`blazorpack-decode/`, v2.0) — Gradle project, Montoya API (`BurpExtension`). Works in Burp 2023+ with full HTTP + WebSocket message editor tabs.

## Build & Deploy

```bash
cd blazorpack-decode
./gradlew jar
```

Output: `build/libs/blazorpack-decode-2.0.jar` (fat JAR — includes msgpack-core + gson).

To deploy: load the `.jar` in Burp (Extender → Extensions → Add → Type: Java).

## Architecture

8 source files under `blazorpack-decode/src/main/java/blazorpack/`:

- **`BlazorPack.java`** — Entry point. Implements `BurpExtension.initialize(MontoyaApi)`. Registers HTTP request/response editors + WebSocket message editor provider.
- **`BlazorPackFrame.java`** — Static utilities: varint32 read/write, `isBlazorPackData()` detection heuristic, HTTP header stripping.
- **`BlazorPackDecoder.java`** — `decode(byte[])` → `List<Object>`. Uses `msgpack-core` `MessageUnpacker`. Handles varint framing loop, JSON fallback, binary-to-string conversion. `prettyPrint()` formats decoded messages as indented JSON with optional embedded JSON expansion.
- **`BlazorPackEncoder.java`** — `encode(String json)` → `byte[]`. Parses with Gson, packs with `MessageBufferPacker` using binary type headers (`packBinaryHeader`), prepends varint framing. `collapseEmbeddedJson()` reverses JSON expansion, preserving SignalR method argument boundaries.
- **`BlazorPackHttpEditor.java`** + **`BlazorPackHttpEditorProvider.java`** — Montoya HTTP request/response editor tabs. Swing UI: JTextArea + "Expandir JSON embebido" checkbox + status bar. Strips/reinjects HTTP headers, updates Content-Length on re-encode.
- **`BlazorPackWsEditor.java`** + **`BlazorPackWsEditorProvider.java`** — Montoya WebSocket editor tabs. Same UI pattern. `setMessage(WebSocketMessage)` extracts bytes via `message.payload().getBytes()`. Implements `isEnabledFor()` detection.

### Dependencies (baked into fat JAR)

- `org.msgpack:msgpack-core:0.9.8` — MessagePack serialization
- `com.google.code.gson:gson:2.10.1` — JSON parsing/formatting
- `net.portswigger.burp.extensions:montoya-api:2024.7` (compileOnly — provided by Burp at runtime)

## Key protocol details

- **BlazorPack framing**: `[varint32 message_length][MessagePack payload]...` repeating
- **MessagePack uses binary type** (0xc4–0xc6) for strings: `packBinaryHeader()` + `writePayload()`, matching Blazor's `use_bin_type=True`
- **SignalR-specific**: methods like `BeginInvokeDotNetFromJS` have JSON-stringified args at specific positions (index 4). The collapse/expand logic preserves these boundaries for valid re-encoding.
- **Pure JSON frames** (starting with `{` or `[`) are passed through as-is.
