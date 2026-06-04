# BlazorSpy

**Burp Suite extension to inspect, decode, and tamper with Blazor Server WebSocket frames.**

[![Version](https://img.shields.io/badge/version-1.0.1-blue)](https://github.com/Wanetty/BlazorSpy/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Burp](https://img.shields.io/badge/Burp-2023%2B-orange)](https://portswigger.net/burp)
[![Java](https://img.shields.io/badge/Java-17%2B-red)](https://adoptium.net)

BlazorSpy lets you peer inside **BlazorPack frames** — the custom `varint32 + MessagePack` wire format used by **Blazor Server SignalR** WebSocket connections — and presents them as clean, editable JSON right inside Burp's message editor tabs. Decode with a click, edit what you need, and forward re-encoded frames without ever leaving Burp.

## Quick Start

1. **Build** (or grab the [pre-built JAR](https://github.com/Wanetty/BlazorSpy/releases)):
   ```bash
   cd blazorpack-decode
   ./gradlew jar
   ```
2. **Load in Burp**: `Extender → Extensions → Add → Type: Java` → select `build/libs/BlazorSpy-1.0.1.jar`
3. **Use**: Open a Blazor WebSocket or HTTP message → click the **BlazorPack** tab → edit JSON → Forward / Send (re-encoded automatically)

## What It Does

Blazor Server apps stream UI updates and method calls over a persistent WebSocket using Microsoft's **BlazorPack** protocol:

```
[varint32 length] [MessagePack payload] [varint32 length] [MessagePack payload] ...
```

BlazorSpy hooks into Burp's editor pipeline and:

| Step | What happens |
|---|---|
| **Detect** | Identifies BlazorPack frames by varint header + MessagePack magic bytes, or inline JSON |
| **Decode** | Unpacks MessagePack to Java objects and expands embedded JSON strings at SignalR argument positions |
| **Display** | Pretty-prints the decoded structure as indented, syntax-highlighted JSON in a Burp editor tab |
| **Re-encode** | Collapses expanded JSON back and repacks to binary BlazorPack when you forward or send |
| **Truncated frames** | Automatically reassembles split WebSocket frames (2048-byte SignalR chunks) before decoding |

Works on **HTTP requests & responses** and **WebSocket messages** — both directions.

## Why BlazorSpy?

Blazor's WebSocket traffic is opaque binary by default. Without a decoder, you see raw bytes — not useful for pentesting, bug bounty hunting, or debugging. BlazorSpy gives you:

- **Full visibility** into Blazor Server's SignalR hub invocations, event dispatches, and JS interop calls
- **Safe tampering** — the round-trip encode/decode preserves SignalR argument boundaries so the server won't drop your connection after editing
- **Zero-config** — drop the JAR into Burp and it works on any Blazor Server target

## Requirements

- **Burp Suite** Professional or Community Edition **2023+** (Montoya API)
- **JDK 17+** (only needed to build from source)
- Any platform Burp runs on (Windows, macOS, Linux)

## Building from Source

```bash
cd blazorpack-decode
./gradlew jar
# Windows: gradlew.bat jar
```

The Gradle build produces a **fat JAR** with all runtime dependencies bundled:

| Dependency | Version | Purpose |
|---|---|---|
| [msgpack-core](https://github.com/msgpack/msgpack-java) | 0.9.8 | MessagePack deserialization |
| [gson](https://github.com/google/gson) | 2.10.1 | JSON parse / pretty-print |
| Montoya API | 2024.7 | Burp extension interface *(compileOnly — provided by Burp at runtime)* |

Gradle wrapper scripts (`gradlew` / `gradlew.bat`) auto-download the correct Gradle version on first run.

## SignalR Round-Trip Editing

Some SignalR methods transmit their arguments as a JSON string at position index 4 of the MessagePack array. BlazorSpy handles these with **symmetric expand/collapse**:

| Method | Index 4 content |
|---|---|
| `BeginInvokeDotNetFromJS` | JSON-stringified .NET invoke args |
| `DispatchEventAsync` | JSON-stringified event payload |
| `EndInvokeJSFromDotNet` | JSON-stringified JS return value |

Only these three methods, and only at index 4, get expanded. The encoder collapses them back exactly as the server expects — so your edited WebSocket frames survive the round-trip.

## Editor Features

- **Expand embedded JSON** checkbox — toggle the SignalR index-4 expansion on/off
- **Status bar** — shows frame count, TruncatedFrame detection, and last decode result
- **Comment lines** (`// ...`) — preserved in the editor and stripped before re-encoding (use them for notes)
- **HTTP header stripping** — HTTP editors automatically strip headers before decode and re-inject them on re-encode, updating `Content-Length`

## Project Structure

```
blazorpack-decode/
├── build.gradle                    # Gradle config (fat JAR, version, deps)
├── gradlew / gradlew.bat           # Gradle wrapper scripts
├── gradle/wrapper/                 # Wrapper JAR + properties
└── src/
    ├── main/java/blazorpack/
    │   ├── BlazorPack.java                    # BurpExtension entry point
    │   ├── BlazorPackFrame.java               # Varint32, detection, HTTP headers
    │   ├── BlazorPackDecoder.java             # MessagePack → JSON (expand, pretty-print)
    │   ├── BlazorPackEncoder.java             # JSON → MessagePack (collapse, pack)
    │   ├── BlazorPackHttpEditor.java           # HTTP request/response editor tab
    │   ├── BlazorPackHttpEditorProvider.java   # HTTP editor factory
    │   ├── BlazorPackWsEditor.java             # WebSocket message editor tab
    │   └── BlazorPackWsEditorProvider.java     # WebSocket editor factory
    └── test/java/blazorpack/
        ├── BlazorPackDecoderTest.java          # Decoder unit tests
        └── BlazorPackEncoderTest.java          # Encoder + round-trip unit tests
```

## Keywords

*Blazor Pack decoder, Blazor WebSocket inspector, Burp Suite extension Blazor, SignalR MessagePack decoder, .NET Blazor traffic analysis, BlazorPack MessagePack Burp, inspect Blazor Server WebSocket, tamper Blazor SignalR frames, Blazor pentesting tool, Blazor binary decoder Burp, varint32 MessagePack decoder, Blazor hub invocation inspector, Blazor JS interop decoder*

## License

MIT © [Wanetty](https://github.com/Wanetty)

---

<p align="center">
  <sub>Not affiliated with Microsoft or PortSwigger. Blazor is a trademark of Microsoft.</sub>
</p>
