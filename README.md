# BlazorPack Decoder

Burp Suite extension that decodes/encodes Blazor Server WebSocket frames (BlazorPack = varint32 + MessagePack) into readable/editable JSON directly inside Burp's message editors.

## Quick start

1. **Build**: open a terminal in `blazorpack-decode/` and run:
   ```
   ./gradlew jar
   ```
2. **Load in Burp**: Extender → Extensions → Add → Type: Java → select `build/libs/blazorpack-decode-2.0.jar`
3. **Use**: open a Blazor WebSocket or HTTP message → click the "BlazorPack" tab → edit JSON → Forward/Send (auto re-encoded)

## Pre-built JAR

A pre-built JAR is included at the project root (`blazorpack-decode-2.0.jar`). You can load it directly without building.

## Requirements

- Burp Suite Professional/Community 2023+ (Montoya API)
- JDK 17+ (only needed for building)

## Building from source

The Gradle build produces a fat JAR with all runtime dependencies bundled:

```bash
cd blazorpack-decode
./gradlew jar
```

**On Windows**: use `gradlew.bat jar` instead.

Dependencies (downloaded automatically by Gradle):
- `org.msgpack:msgpack-core:0.9.8` — MessagePack serialization
- `com.google.code.gson:gson:2.10.1` — JSON parsing/formatting

Gradle wrapper files (`gradlew` / `gradlew.bat`) auto-download the correct Gradle version on first run.

## How it works

Blazor Server uses SignalR over WebSocket with a custom MessagePack variant called BlazorPack:

```
[varint32 message_length] [MessagePack payload] [varint32 message_length] [MessagePack payload] ...
```

The extension:
1. Detects BlazorPack frames (varint + msgpack magic bytes or JSON literal)
2. Decodes MessagePack to Java objects, expands embedded JSON strings
3. Displays formatted, editable JSON in a Burp editor tab
4. On forward/send, collapses edited JSON back and re-encodes to binary BlazorPack

## SignalR quirks

Methods like `BeginInvokeDotNetFromJS` have JSON-stringified arguments at specific positions (index 4). The collapse/expand logic preserves these boundaries during round-trip editing.
