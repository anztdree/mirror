# Build Instructions

## Prerequisites

1. **Android Studio** (Hedgehog 2023.1.1 or newer recommended)
2. **JDK 17** (bundled with Android Studio)
3. **Android SDK Platform 34** (will be auto-downloaded on first build)
4. **Android SDK Build-Tools 34.0.0**
5. Internet connection (for Gradle dependencies)

## Step-by-Step: Build Sender APK

1. **Open project in Android Studio**
   - File → Open → select `/screen_miror/senderr/` folder
   - Wait for Gradle sync (downloads dependencies first time, ~5-10 min)

2. **Configure local SDK path** (if needed)
   - If sync fails about SDK location:
     - Create file `screen_miror/senderr/local.properties`
     - Add line: `sdk.dir=/path/to/Android/Sdk` (use your actual SDK path)
       - Windows: `C:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`
       - macOS: `/Users/<user>/Library/Android/sdk`
       - Linux: `/home/<user>/Android/Sdk`

3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
   - Wait for build to complete (output in `app/build/outputs/apk/debug/app-debug.apk`)

4. **Install on test device**
   - Connect HP via USB (debug mode ON) → click "Run" in Android Studio
   - OR: copy the APK file to HP, install manually (enable "Install from unknown sources")

5. **Build release APK** (for distribution)
   - Build → Generate Signed Bundle / APK
   - Create new keystore (or reuse)
   - Build variant: release
   - Output: `app/build/outputs/apk/release/app-release.apk`

## Step-by-Step: Build Receiver APK

Same steps, but open `/screen_miror/receiver/` folder in Android Studio.

## Common Build Errors & Fixes

### 1. "SDK location not found"
Create `local.properties` with `sdk.dir=...` (see above).

### 2. "Could not resolve `io.getstream:stream-webrtc-android:1.0.5`"
Make sure `settings.gradle` has:
```
maven { url 'https://jitpack.io' }
```
Already included — verify your internet connection during first sync.

### 3. "Cannot inline bytecode built with JVM target 17"
File → Settings → Build → Compiler → Kotlin Compiler target bytecode = 17.

### 4. "AndroidX dependency conflict"
Project → Clean, then File → Sync Project with Gradle Files.

### 5. "Foreground service type not declared" (Android 14+)
Already declared in `AndroidManifest.xml`:
```xml
android:foregroundServiceType="mediaProjection|camera|microphone"
```
If still failing, ensure `targetSdk 34` in `app/build.gradle`.

### 6. Build succeeds but APK crashes on launch
- Check logcat for the exception
- Most common cause: missing permission at runtime
- Sender needs: CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS (Android 13+)
- All requested via `PermissionHelper.requestPermissions(activity)`

## Testing on Emulator vs Real Device

| Feature | Emulator | Real Device |
|---------|----------|-------------|
| Screen mirror | ✅ Works (mirrors emulator screen) | ✅ Works |
| Front camera | ⚠️ Emulator camera is fake | ✅ Works |
| Audio (mic) | ⚠️ Use virtual mic | ✅ Works |
| MediaProjection | ⚠️ Some emulators restrict | ✅ Works |

**Recommendation: always test on real device.** Emulator is fine for layout/UI testing only.

## Reducing APK Size (Optional)

Add to `app/build.gradle` under `android { ... }`:
```groovy
buildTypes {
    release {
        minifyEnabled true
        shrinkResources true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
```
Then rebuild release APK. Expected size reduction: ~30-40%.

## Distribution

For sharing APK with someone:
- Sender APK: ~30-40 MB (includes WebRTC native libs)
- Receiver APK: ~30-40 MB
- Or use App Bundle (.aab) for Play Store (smaller per-device download)

Both APKs are independent — they share no library code at runtime.
