# Arsitektur Screen Mirror

## High-Level Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          INTERNET (WebRTC P2P)                          │
│                        (setelah signaling selesai)                      │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   │
        ┌──────────────────────────┴──────────────────────────┐
        │                                                   │
        ▼                                                   ▼
┌──────────────────────┐                          ┌──────────────────────┐
│   HP Sender (APK 1) │                          │ HP Receiver (APK 2) │
│                      │                          │                      │
│  ┌────────────────┐  │                          │  ┌────────────────┐  │
│  │ SenderActivity │  │                          │  │ MainActivity  │  │
│  │  → gen code    │  │                          │  │  → input code │  │
│  └───────┬────────┘  │                          │  └───────┬────────┘  │
│          │           │                          │          │           │
│          ▼           │                          │          ▼           │
│  ┌────────────────┐  │                          │  ┌────────────────┐  │
│  │ ScreenCapture  │  │                          │  │ ReceiverActi │  │
│  │ Service        │  │                          │  │ vity          │  │
│  │ (foreground,   │  │                          │  │  → SurfaceVi │  │
│  │  stealth)     │  │                          │  │    ewRenderer │  │
│  └───────┬────────┘  │                          │  │  → Controls   │  │
│          │           │                          │  └───────┬────────┘  │
│          ▼           │                          │          │           │
│  ┌────────────────┐  │                          │          ▼           │
│  │ WebRtcManager  │  │                          │  ┌────────────────┐  │
│  │ - ScreenCap    │◄─┼─── SDP offer ───────────►│  │ WebRtcManager  │  │
│  │ - FrontCam     │  │                          │  │ - Renderer    │  │
│  │ - Mic (ADM)    │◄─┼─── ICE candidates ─────►│  │ - AudioOut    │  │
│  │ - PeerConn     │  │                          │  │ - PeerConn    │  │
│  └───────┬────────┘  │                          │  └───────┬────────┘  │
│          │           │                          │          │           │
│          ▼           │                          │          ▼           │
│  ┌────────────────┐  │                          │  ┌────────────────┐  │
│  │ MqttSignaling  │  │                          │  │ MqttSignaling  │  │
│  │ Client         │◄─┼─── via EMQX broker ─────►│  │ Client         │  │
│  └────────────────┘  │                          │  └────────────────┘  │
└──────────────────────┘                          └──────────────────────┘
        │                                                   ▲
        │           ┌───────────────────────┐               │
        └──────────►│  EMQX Public Broker   │◄──────────────┘
                    │  wss://broker.emqx.io  │
                    │       :8084/mqtt       │
                    └───────────────────────┘
```

## Signaling Protocol (MQTT)

Topik MQTT:
- `screenmirror/{6digit}/lobby` — broadcast (receiver announce presence)
- `screenmirror/{6digit}/to/{peerId}` — direct (SDP offer/answer, ICE)

Urutan handshake (1-to-1 case):

```
Sender                             Receiver
  │                                    │
  │  ( Sender starts service,         │
  │    generates 6-digit code,        │
  │    registers at MQTT peerId=X )   │
  │                                    │
  │                                    │  ( Receiver opens app,
  │                                    │    inputs code,
  │                                    │    registers at MQTT peerId=Y )
  │                                    │
  │  ◄── receiver_hello (from=Y) ────  │  ← Receiver broadcasts to lobby
  │                                    │
  │  (Creates PeerConnection,         │
  │   adds video/audio tracks)        │
  │                                    │
  │  ─── sdp_offer (to=Y) ──────────►  │
  │                                    │  (Sets remote SDP)
  │                                    │  (Creates answer)
  │  ◄── sdp_answer (to=X) ──────────  │
  │                                    │
  │  (Sets remote SDP)                │
  │                                    │
  │  ─── ice_candidate (to=Y) ──────►  │  ← Both sides send ICE candidates
  │  ◄── ice_candidate (to=X) ───────  │
  │                                    │
  │  ══════ ICE CONNECTED ═════════════│
  │                                    │
  │  ══════ VIDEO/AUDIO FLOWING ═══════│
  │                                    │
  │  ◄── switch_source (source=camera) │  ← Receiver requests camera
  │  (Switches video source)          │
  │                                    │
  │  ◄── mute_audio (muted=true) ────  │  ← Receiver requests mute
  │  (Mutes mic via ADM)              │
  │                                    │
  │  ◄── disconnect ──────────────────  │  ← Receiver leaves
  │  (Disposes PeerConnection)         │
  │                                    │
```

## Message Format (JSON)

```json
{
  "type": "sdp_offer",
  "from": "uuid-of-sender",
  "to": "uuid-of-receiver",
  "sdp": "v=0\r\no=- 123456...",
  "sdpType": "offer"
}
```

| type            | Fields                                    | Direction             |
|-----------------|-------------------------------------------|-----------------------|
| receiver_hello  | from                                      | receiver → sender     |
| sender_ready    | from                                      | sender → lobby (ack)  |
| sdp_offer       | from, to, sdp, sdpType                    | sender → receiver     |
| sdp_answer      | from, to, sdp, sdpType                    | receiver → sender     |
| ice_candidate   | from, to, candidate, sdpMid, sdpMLineIndex | either                |
| switch_source   | from, to, source (screen/front_camera)    | receiver → sender     |
| mute_audio      | from, to, muted (bool)                    | receiver → sender     |
| disconnect      | from, to                                  | either                |

## WebRTC ICE Servers (Hardcoded)

```kotlin
listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
        .setUsername("openrelayproject")
        .setPassword("openrelayproject")
        .createIceServer(),
    PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
        .setUsername("openrelayproject")
        .setPassword("openrelayproject")
        .createIceServer()
)
```

## Sender Service Lifecycle

```
App Launch → SenderActivity
    │
    ├─ User presses "START"
    │    ├─ Request runtime permissions (CAMERA, RECORD_AUDIO, POST_NOTIF on Android 13+)
    │    ├─ Request MediaProjection consent (system dialog)
    │    └─ Generate 6-digit code, start ScreenCaptureService (foreground)
    │
    ▼
ScreenCaptureService (foreground service)
    │
    ├─ Show stealth notification ("Android System")
    ├─ Create EglBase context
    ├─ Connect MqttSignalingClient → EMQX broker
    │    ├─ Subscribe to screenmirror/{code}/lobby
    │    └─ Subscribe to screenmirror/{code}/to/{myPeerId}
    │
    ├─ Initialize WebRtcManager
    │    ├─ PeerConnectionFactory
    │    ├─ AudioDeviceModule (mic)
    │    ├─ VideoSource (shared between screen & camera)
    │    ├─ VideoTrack
    │    └─ Start ScreenCapturerAndroid (default source)
    │
    ├─ Wait for incoming messages...
    │
    ├─ On receiver_hello:
    │    ├─ Create PeerConnection
    │    ├─ Add local tracks (video, audio)
    │    ├─ Create SDP offer
    │    ├─ Set as local description
    │    └─ Send offer to receiver via MQTT
    │
    ├─ On sdp_answer:
    │    └─ Set as remote description
    │
    ├─ On ice_candidate (incoming):
    │    └─ Add to PeerConnection
    │
    ├─ (PeerConnection generates ICE candidates → publish to MQTT)
    │
    ├─ On switch_source:
    │    ├─ Stop current capturer
    │    └─ Start new capturer (screen or camera)
    │
    ├─ On mute_audio:
    │    └─ ADM.setMicrophoneMute(muted)
    │
    └─ On onDestroy:
         ├─ Dispose PeerConnection
         ├─ Stop all capturers
         ├─ Disconnect MQTT
         └─ Release EglBase
```

## Multi-Receiver (Future)

For 1-to-N mirroring, the sender would need to:
1. Maintain a `Map<peerId, PeerConnection>` for each connected receiver
2. Each receiver gets its own PeerConnection
3. Switching source affects ALL peer connections simultaneously
4. Bandwidth usage scales linearly with N

Current code scaffolds this but the MVP only handles 1-1 (the single `peerConnection` field). To extend, refactor to a `Map<String, PeerConnection>` keyed by receiver peerId.

## Privacy & Stealth Trade-offs

| Concern | What's done | What's not possible |
|---------|-------------|---------------------|
| App icon visible in launcher | Stealth icon + label "System Service" | Hiding icon needs extra flag (component enabled state) — not enabled by default |
| Notification visible | Disguised as "Android System", low priority, no sound | Cannot remove notification on Android 8+ (Foreground Service requirement) |
| Camera indicator (green dot) | N/A | Cannot hide on Android 11+ (system-level, root required) |
| Mic indicator (orange dot) | N/A | Cannot hide on Android 12+ (system-level, root required) |
| App visible in recents | `android:excludeFromRecents="true"` | ✓ Hidden |
| App visible in battery usage | May show as "System Service" usage | Cannot hide from system battery stats |
| Boot auto-start | BootReceiver registered (no-op on first launch due to Android restriction) | Cannot auto-start on boot without being a system app or device admin |
