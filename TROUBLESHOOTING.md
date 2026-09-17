# Troubleshooting

## Common Issues

### 1. MQTT Broker Connection Failed

**Gejala:** "Gagal koneksi broker: ..." di HP receiver

**Kemungkinan penyebab:**
- HP tidak terhubung internet
- Firewall jaringan blokir port 8084 (WSS) — coba ganti jaringan
- EMQX public broker down (rare) — cek di https://status.emqx.io

**Solusi alternatif:**
Ganti broker ke public HiveMQ:
- File `MqttSignalingClient.kt`
- Edit `BROKER_HOST = "broker.hivemq.com"`, `BROKER_PORT = 8884`, `BROKER_PATH = "/mqtt"`
- Rebuild

### 2. Video Black Screen on Receiver

**Cek di HP sender:**
1. Izin CAMERA sudah diberikan? (Settings → Apps → System Service → Permissions)
2. Layar HP sender terkunci? — saat screen lock, MediaProjection tidak mengirim frame
3. Notification foreground service ada? — kalau tidak ada, service mati

**Cek di HP receiver:**
1. SurfaceViewRenderer sudah init? — cek logcat "WebRtcManager initialized"
2. Video track terkirim? — cek logcat "Remote video track added"

### 3. No Audio from Sender

1. Mic permission di sender belum granted
2. Tombol MUTE MIC sedang aktif (label "UNMUTE MIC" berarti saat ini muted)
3. AudioDeviceModule gagal init — cek logcat `WebRtcManager` error
4. Sender HP on call (telepon aktif) — telepon mengunci mic

### 4. Latency Tinggi (>500ms)

Penyebab umum:
- **TURN fallback aktif** — koneksi P2P gagal, pakai TURN relay. Cek logcat `ICE state: CONNECTED` dan tipe kandidat yang dipakai. Untuk konfirmasi:
  ```bash
  adb logcat | grep -i "ice\|candidate"
  ```
  Look for "typ" in candidate SDP — `host` = direct, `srflx` = STUN, `relay` = TURN

- **WiFi congested** — coba ganti jaringan (mobile data)
- **HP sender doing heavy task** — tutup app lain
- **Encoder lag** — turunkan resolusi di `WebRtcManager.kt` (edit `VIDEO_WIDTH=854`, `VIDEO_HEIGHT=480`)

### 5. Sender Service Killed by System

**Penyebab:** Android battery optimization / app killer

**Solusi:**
1. Settings → Apps → System Service → Battery → Unrestricted
2. Settings → Battery → Battery optimization → All apps → System Service → Don't optimize
3. Disable manufacturer-specific battery savers:
   - MIUI (Xiaomi): Settings → Apps → System Service → Battery saver → No restrictions
   - EMUI (Huawei): Settings → Battery → App launch → System Service → Manage manually → enable all
   - One UI (Samsung): Settings → Battery → Background limits → remove
   - ColorOS (Oppo): Settings → Battery → Background app management → System Service → enable
   - Vivo Funtouch: Settings → Battery → Background management → System Service → allow

### 6. Sender Stops When Screen Locks

Android 14+ dapat kill foreground service saat device sleep.

**Solusi:**
- Tambahkan WAKE_LOCK di sender service (sudah ada di manifest)
- Pastikan HP terhubung charger
- Disable "Adaptive Battery" di Settings → Battery

### 7. "Cannot connect to peer" — P2P failed

Cek di logcat:
```
onIceConnectionChange: FAILED
```

**Kemungkinan:**
- Symmetric NAT di salah satu sisi (umum: WiFi kantor, mobile carrier tertentu)
- TURN server juga down

**Solusi:**
- Ganti jaringan (coba mobile data vs WiFi)
- Tunggu 30 detik — kadang ICE gathering lambat
- Ganti TURN server (lihat section "Alternative TURN servers" di bawah)

### 8. APK Crashes on Launch (Sender)

Penyebab umum:
- Android < 5.0 (API 21) — gak support MediaProjection
- `Theme.MaterialComponents.NoActionBar` butuh `material` dependency — sudah ada, verify tidak ada typo di build.gradle
- Permission denied at startup — reinstall APK dan accept all permissions

Cek logcat:
```bash
adb logcat | grep -i "AndroidRuntime\|ScreenMirror\|SystemService"
```

### 9. APK Crashes on Launch (Receiver)

Penyebab umum:
- Tidak ada permission Internet (jarang terjadi, otomatis di manifest)
- EGL context init gagal — cek GPU support
- WebRTC native lib load fail — cek arsitektur (apk harus include arm64-v8a, armeabi-v7a, x86_64 — otomatis via WebRTC lib)

### 10. Pairing Code Sama Berulang

`CodeGenerator` pakai `SecureRandom` jadi harusnya unique. Kalau berulang:
- Restart HP, clear app data
- Rare Android bug — `SecureRandom` dapat reuse seed pada beberapa custom ROM

## Alternative ICE / TURN Servers (Free)

Kalau OpenRelay down, ganti ke salah satu:

| Provider | STUN | TURN | Username / Pass | Notes |
|----------|------|------|-----------------|-------|
| OpenRelay (default) | `stun:openrelay.metered.ca:80` | `turn:openrelay.metered.ca:80` | `openrelayproject` | Free, no account |
| Google public | `stun:stun.l.google.com:19302` | (no TURN) | — | STUN only |
| Twilio free trial | (varies) | (varies) | (after signup) | Need account |
| self-hosted coturn | (your server) | (your server) | (custom) | Need VPS |

**Edit location:** `WebRtcManager.kt` → `ICE_SERVERS` list, di kedua project (sender & receiver).

## Alternative MQTT Brokers (Free)

| Broker | URL | Notes |
|--------|-----|-------|
| EMQX public (default) | `wss://broker.emqx.io:8084/mqtt` | Most reliable |
| HiveMQ public | `wss://broker.hivemq.com:8884/mqtt` | Backup |
| Mosquitto test | `ws://test.mosquitto.org:8081/mqtt` (TLS variant on 8081) | Not reliable, test only |
| Public broker could disallow large msgs | Keep messages < 5KB | SDP can be 4-10KB |

## Debugging Tips

### Enable verbose logging
Tambahkan di `MainActivity.onCreate`:
```kotlin
android.util.Log.println(Log.VERBOSE, "ScreenMirror", "Debug mode enabled")
```

atau enable via adb:
```bash
adb shell setprop log.tag.WebRtcManager VERBOSE
adb shell setprop log.tag.MqttSignaling VERBOSE
adb shell setprop log.tag.ScreenCaptureService VERBOSE
```

### Verify P2P connection
Cek logcat untuk:
- `ICE state: CONNECTED` → koneksi sukses
- `ICE state: FAILED` → TURN needed atau NAT terlalu ketat

### Verify audio track received
Cek logcat receiver:
- `Remote video track added` → video track ok
- (Audio tracks tidak otomatis di-log, perlu tambahan observer)

### Verify MQTT messages flowing
Tambahkan di `MqttSignalingClient.kt` `onMessageReceived`:
```kotlin
Log.d(TAG, "RECV: $payload")
```
Sudah ada di current code.

## Reporting Issues

Kalau nemu bug yang bukan di list di atas, kumpulkan info berikut untuk debugging:
1. Logcat saat error (filter: `WebRtc`, `Mqtt`, `Screen`, `System` service)
2. Android version + device model (kedua HP)
3. Network type (WiFi/mobile data, carrier)
4. Step-by-step untuk reproduce
5. Pairing code yang dipakai (tidak rahasia — code expired after use)

## Known Limitations

1. **No recording on receiver** — bisa ditambah dengan `MediaRecorder` + decoded frames, tapi tidak di MVP
2. **No multi-receiver** — code scaffolds Map<peerId, PeerConnection> tapi belum diaktifkan
3. **No auto-reconnect** — kalau connection drop, user harus re-input code manual
4. **No encryption above WebRTC** — WebRTC sudah DTLS-SRTP encrypted, tapi tidak ada end-to-end key exchange selain pairing code (yang visible di MQTT). Untuk security enhancement, gunakan pairing code sebagai pre-shared key untuk encrypt SDP messages.
5. **No background restart** — sender service tidak auto-restart setelah reboot HP (perlu system app / device admin)
