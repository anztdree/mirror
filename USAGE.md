# Usage Guide

## Setup

1. Install **sender APK** di HP yang akan di-mirror (HP A)
2. Install **receiver APK** di HP yang akan melihat (HP B)
3. Pastikan **kedua HP terhubung internet** (WiFi atau data seluler)

## Step-by-Step: Pairing & Mirroring

### Di HP A (Sender)

1. Buka app "System Service" (label launcher yang di-disguise)
2. Anda akan lihat layar gelap dengan tombol **START** di tengah
3. Tekan **START**
4. Sistem akan meminta izin:
   - **Camera** → Allow
   - **Microphone** → Allow
   - **Notifications** (Android 13+) → Allow
5. Sistem akan menampilkan dialog **"Start recording or casting with System Service?"**
   - Pilih **START NOW** (klik "Don't show again" optional)
6. Akan muncul **6-digit pairing code** besar di layar (contoh: `482917`)
7. Catat kode tersebut dan berikan ke pengguna HP B
8. Layar HP A sekarang di-capture; notifikasi "Android System" muncul di status bar
9. Tekan tombol Home — app tetap berjalan di background (foreground service)

### Di HP B (Receiver)

1. Buka app "Screen Mirror"
2. Input 6-digit code yang diberikan (contoh: `482917`)
3. Tekan **CONNECT**
4. Tunggu 5-15 detik (handshake + ICE gathering)
5. Layar HP A akan tampil full-screen di HP B
6. Audio dari mic HP A juga akan terdengar di HP B (speaker)

## Controls di Receiver

Bar kontrol di bagian bawah layar:

| Button | Function |
|--------|----------|
| **SCREEN** | Switch view ke layar HP sender |
| **CAMERA** | Switch view ke kamera depan HP sender |
| **MUTE MIC** | Mute/unmute mic HP sender |
| **EXIT** | Disconnect & keluar |

### Saat "CAMERA" ditekan:
- HP sender akan switch dari screen capture → kamera depan
- Anda akan lihat apa yang dilihat kamera depan HP sender
- Mic tetap aktif → audio masih mengalir
- Untuk kembali ke layar: tekan **SCREEN**

### Saat "MUTE MIC" ditekan:
- Mic di HP sender di-mute via AudioDeviceModule
- Tidak ada audio yang diterima di HP B
- Untuk unmute: tekan lagi (label berubah jadi "UNMUTE MIC")

### Saat "EXIT" ditekan:
- Mengirim pesan `disconnect` ke sender
- Membersihkan PeerConnection
- Menghentikan signaling
- Keluar dari activity

## Stealth Behavior (HP Sender)

Setelah Anda menekan START di HP sender:

- ✅ App tidak muncul di "Recent apps" (`excludeFromRecents`)
- ✅ Notifikasi di-disguise: "Android System — System service running"
- ✅ Tidak ada preview aplikasi yang muncul
- ❌ **Indikator kamera (hijau)** muncul di pojok kanan atas saat camera aktif (Android 11+)
- ❌ **Indikator mic (oranye)** muncul di pojok kanan atas saat mic aktif (Android 12+)
- ❌ Notifikasi foreground service tidak bisa dihilangkan (Android 10+ system requirement)

**Untuk stealth total (tanpa indikator apapun):** butuh root + custom ROM yang memodifikasi SystemUI — di luar scope project ini.

## Stop Sender

Untuk menghentikan capture di HP sender:
1. Buka app "System Service" lagi dari launcher
2. Tekan tombol **STOP**
3. Service berhenti, notification hilang, kode pairing tidak valid lagi

## Use Cases yang Recommended

✅ **Find My Lost Phone** — HP hilang di rumah, lihat via kamera depan + dengar mic untuk locating
✅ **Anti-theft** — HP dicuri, lacak perekam visual + audio dari rumah
✅ **Remote assistance** — bantu seseguna setup HP mereka, lihat layarnya
✅ **Presentasi** — mirror HP ke HP lain untuk demo ke teman
✅ **Baby monitor** — letakkan HP sender di kamar bayi, monitor dari HP lain

## Yang TIDAK Recommended

❌ Spy pada seseorang tanpa sepengetahuannya (ilegal, melanggar privasi)
❌ Record stream tanpa consent (illegal di banyak negara)
❌ Distribusikan APK yang dimodifikasi tanpa notice ke user target

## Multi-Receiver (Future)

Versi saat ini hanya support 1-to-1. Untuk multi-receiver:
- Sender harus maintain Map<peerId, PeerConnection>
- Bandwidth pengirim = N x bandwidth receiver
- Pairing code sama untuk semua receiver (broadcast)

Fitur ini tidak diaktifkan di MVP — bisa diaktifkan dengan modifikasi WebRtcManager.

## Battery & Data Usage

- **Sender**: CPU 5-15% (encoder H.264), data 1-3 Mbps upstream, ~70 MB/jam
- **Receiver**: CPU 2-5% (decoder), data 1-3 Mbps downstream
- Tinggalkan sender berjalan berjam-jam butuh power bank / HP terhubung charger

## Troubleshooting Quick Reference

| Gejala | Kemungkinan | Solusi |
|--------|-------------|--------|
| Tidak connect setelah 30 detik | Broker MQTT down / NAT strict | Lihat [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| Video hitam | Camera permission ditolak | Re-request izin, restart app |
| Tidak ada audio | Mic permission ditolak / mute aktif | Cek mute button, restart app |
| Berhenti sendiri setelah 5 menit | Battery optimization kill | Disable battery opt untuk app |
| Crash saat tombol START | Tidak ada permission / Android version < 5.0 | Cek Android version, re-request izin |
