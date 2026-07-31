# Development workflow

## Repository

Current field-test branch:

```text
mempuck/memory-info-src-cosmetics-v027
```

Application identity:

```text
Package: com.n5nbd.mempuck.atsmini
Version: 0.27.0-dev27
Version code: 27
Minimum Android SDK: 26
Compile/target SDK: 36
JVM toolchain: 17
```

## Build

The standard verification command is:

```bash
./gradlew test assembleDebug
```

The helper script also locates or prepares the configured Android SDK:

```bash
./build.sh
```

Generated APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Pixel 6 deployment

Wireless ADB is preferred when available. The repository scripts automatically locate a connected Pixel 6 and do not require manual serial selection unless detection fails.

Build, install, and launch:

```bash
./run_pixel6.sh
```

Install an existing build:

```bash
./install_pixel6.sh
```

## Field-test APK

After a successful build:

```bash
cp app/build/outputs/apk/debug/app-debug.apk \
  ~/Downloads/mempuck-ats-mini-android-v027-field-test.apk

sha256sum ~/Downloads/mempuck-ats-mini-android-v027-field-test.apk
```

## Receiver setup

Use the patched ATS Mini firmware and keep the receiver awake during development:

```text
Settings -> Bluetooth -> Ad hoc
Sleep mode -> Unlocked
```

The app requires the Nordic UART service and confirms patched-firmware support with:

```text
Z?
OK,Z,1
```

## Development boundaries

- Preserve the canonical MemPuck UI language.
- Keep the app ATS Mini-specific.
- Treat MemPuck memories and VFO state as authoritative.
- Do not expose ATS numbered-memory channels as the normal user model.
- Store logical CW as CW and translate it to USB at the ATS adapter boundary.
- Keep source packs read-only; write user changes to `USER.json`.
- `SKIP` affects scan execution only.
- Do not push commits or tags until the checkpoint has been reviewed on hardware.

## Licensing boundary

- The Android application is licensed under PolyForm Noncommercial 1.0.0.
- Keep the required copyright and noncommercial notice in `LICENSE`.
- Do not copy the Android license into the ATS Mini firmware fork. The firmware
  repository retains its upstream MIT license and attribution.
- Commercial licensing for MemPuck requires a separate written agreement from
  the copyright holder.
