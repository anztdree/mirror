# Screen Mirror (Android → Android)

P2P screen mirroring antar HP Android, mendukung:
- Mirror layar HP sender → HP receiver (real-time)
- Switch source: Screen ↔ Front Camera
- Audio streaming (mic HP sender)
- Jarak jauh (beda jaringan), tanpa VPS / Firebase
- Pairing via 6-digit code
- Sender berjalan stealth (foreground service, notifikasi disguised)

## Struktur Project

```
screen_miror/
├── senderr/      ← Source code APK Sender (capture + stream)
├── receiver/     ← Source code APK Receiver (display + control)
├── ARCHITECTURE.md
├── BUILD.md
├── USAGE.md
└── TROUBLESHOOTING.md
```

## Cara Pakai Cepat

1. Build 2 APK terpisah (lihat [BUILD.md](BUILD.md)) di Android Studio
2. Install APK **sender** di HP yang akan di-mirror
3. Install APK **receiver** di HP yang akan melihat
4. Buka app **sender** → tekan **START** → muncul 6-digit code
5. Buka app **receiver** → input 6-digit code → **CONNECT**
6. Layar HP sender akan tampil di HP receiver
7. Gunakan tombol di bawah layar receiver untuk: switch ke kamera depan, mute mic, atau exit

## Komponen Teknis

| Komponen | Library / Service | Notes |
|----------|---------------------|-------|
| Video call protocol | WebRTC (`io.getstream:stream-webrtc-android:1.0.5`) | Google WebRTC fork |
| Signaling broker | MQTT publik `wss://broker.emqx.io:8084/mqtt` | Free, no account, hardcoded |
| NAT traversal (STUN) | Google `stun.l.google.com:19302` | Free |
| NAT traversal fallback (TURN) | OpenRelay `openrelay.metered.ca:80/443` | Free |
| Screen capture | `MediaProjection` API | Built-in Android 5.0+ |
| Camera capture | `Camera2` API (via WebRTC enumerator) | Front-facing camera |
| Audio capture | `JavaAudioDeviceModule` (WebRTC) | Hardware echo cancel + NS |
| Foreground service | `ScreenCaptureService` (sender) | Stealth notif, START_STICKY |

## Minimum Android Version

- **Android 5.0 (API 21)** - karena `MediaProjection` baru tersedia mulai API 21
- Untuk Android 11+ indikator kamera (green dot) tidak bisa dihilangkan - limitation OS
- Untuk Android 14+ foreground service type wajib dideklarasikan (sudah ditangani)

## Stealth Mode Limitation

Sender notification di-disguise sebagai "Android System" dengan icon transparan.
Tapi limitation:
- Android 10+: notifikasi foreground service wajib muncul
- Android 11+: kamera indicator (green dot) muncul saat kamera aktif
- Android 12+: mic indicator (orange dot) muncul saat mic aktif

Untuk stealth total (no notification, no indicator) butuh root - tidak dicakup project ini.

## Lisensi & Etika

- Source code bebas dipakai untuk pengembangan
- **Gunakan untuk perangkat milik sendiri** (find my phone, anti-theft, presentasi)
- Jangan dipakai untuk spy tanpa sepengetahuan target — melanggar privasi
- Publish ke Play Store dengan fitur stealth hampir pasti ditolak (policy violation)

## Dokumentasi Lengkap

- [ARCHITECTURE.md](ARCHITECTURE.md) — Diagram alur, komponen, signaling protocol
- [BUILD.md](BUILD.md) — Cara build kedua APK di Android Studio
- [USAGE.md](USAGE.md) — Cara pakai step-by-step + kontrol
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — Masalah umum & solusi
