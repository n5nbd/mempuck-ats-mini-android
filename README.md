# MemPuck for ATS Mini

MemPuck for ATS Mini is a BLE controller and authoritative VFO/memory system for the ATS Mini receiver. It keeps the working MemPuck interface and adds a local, tagged frequency library without making ATS numbered memories part of the normal workflow.

This repository contains the **v0.35.0 field-test release**, the final public testing checkpoint planned before v1.0. It combines the NOW EiBi source, dual stock/FAST firmware support, confirmed scan dwell, remembered-radio reconnect, the tagged memory library, and the final header/source-page cleanup. Both firmware paths have been exercised on a Pixel 6 and ATS Mini V4.

## What it does

- Discovers and connects to an ATS Mini in Bluetooth LE Ad hoc mode.
- Remembers the last capability-verified receiver and attempts to reconnect to it on a fresh app launch.
- Shows a MemPuck splash/about screen while the saved receiver is located, connected, and capability-verified.
- Probes for the MemPuck `Z` extension, then falls back automatically to compatible stock ATS Mini firmware.
- Tunes through atomic `Z` commands when available or verified stock `B`/`M`/`F` transactions otherwise.
- Provides direct frequency entry, digit controls, VFO stepping, scanning, and volume control.
- Maintains a local memory library with names, notes, tags, favorite state, and scan-skip state.
- Filters memories with a tag cloud, optional favorite subset, and AND/OR matching.
- Steps manually through all matching memories and scans only matching memories that are not marked `SKIP`.
- Imports, exports, and manages JSON frequency packs in a user-selected Android Files directory.
- Preserves pack files and writes user-created entries, edits, overrides, and deletions to `USER.json`.
- Downloads and caches the EiBi schedule on demand, then generates ordinary tagged memories for broadcasts active at the current UTC time.

ATS numbered memories are not authoritative and are not currently copied or synchronized by the app.

## Hardware and firmware

The hardware-tested development combination remains:

- Google Pixel 6
- ATS Mini V4
- ATS Mini firmware branch `mempuck/absolute-tune-v003`
- Firmware feature tag `ats-mini-cat-v003-r2-absolute-tune`
- Firmware build profile `esp32s3-ospi`

v0.35.0 accepts either of these receiver protocols:

1. **MemPuck absolute tuning:** `Z?` returns `OK,Z,1`; the app uses one atomic `Z<frequency>,<mode>` command.
2. **Official stock firmware v2.34 or newer:** if `Z?` is unanswered or rejected, the app reads the live status stream, selects `ALL` or `VHF` with verified band bumps, selects the hardware mode with verified mode bumps, and sends the stock `F<frequency>` command.

Both tuning paths have passed Pixel 6 / ATS Mini V4 field testing. Stock tuning is slower when it must change band or restore SSB, while the atomic `Z` path remains noticeably faster. Firmware older than v2.34 does not provide the required `F` command.

During development, configure the receiver as follows:

```text
Settings -> Bluetooth -> Ad hoc
Sleep mode -> Unlocked
```

CPU sleep on the receiver disconnects BLE, so `Unlocked` is recommended while testing MemPuck.

## Frequency and mode policy

- **150 kHz–30 MHz:** LSB, USB, CW, and AM are available.
- **30–64 MHz:** rejected because the ATS Mini does not support this gap.
- **64–108 MHz:** FM is forced and the mode row is hidden.
- Logical CW memories are stored and displayed as CW but sent to the current firmware as USB.
- Broadcast FM is normalized to the ATS Mini's 10 kHz resolution and displayed like `101.70`.

The stock ATS protocol exposes relative `V` and `v` volume commands. MemPuck calculates the required delta and sends it in BLE-safe chunks when the slider is released.

## Interface

The portrait interface follows the established MemPuck design rather than a general SDR layout:

- `RADIO` — live receiver control, VFO/MEM operation, direct entry, scanning, and volume
- `LIST` — filtered memories from whichever source is currently loaded
- `NOW` — manual EiBi download/cache controls and the `LOAD NOW` source switch
- `SRC` — curated frequency-directory tools and the `LOAD SRC` source switch
- `CFG` — radio link, display theme, tuning steps, shared scan dwell, debug log, and about information

Themes:

- `DARK` — white on black
- `LIGHT` — black on white
- `HUE` — selected hue on black

The status bar remains black on every page. While connected, the app keeps the display session alive, dims after inactivity, restores brightness on touch, and restores normal Android sleep behavior after disconnect.

The VFO and memory scanners share one persistent dwell setting with 1, 2, 5, and 10 second choices; 2 seconds is the default. The dwell is listener decision time: its clock begins only after the receiver confirms the requested band, mode, and frequency.

## Memory behavior

MemPuck allows one active record per normalized frequency.

- A single tap expands or collapses a LIST row.
- A double tap loads that record on the RADIO page in MEM mode.
- Expanded rows allow immediate `FAV` and `SKIP` changes or open the full editor.
- The editor uses the same `FAV` and `SKIP` labels as the LIST page and requires confirmation before deleting a memory.
- `FAV` narrows the result produced by the selected tags and AND/OR operation.
- `SKIP` does not hide a memory and does not prevent manual stepping; it is consulted only by memory scanning.
- MEM single arrows step through every memory in the active filtered result.
- MEM double arrows scan the same result after removing `SKIP` entries.

When NOW is loaded, LIST contains temporary AM memories generated from currently active EiBi broadcast records. NOW records retain the normal filter, tune, step, scan, and editor behavior, but the one-tap `FAV`, `SKIP`, and delete controls are hidden. Saving through the editor deliberately creates a permanent `USER.json` override. Loading SRC restores the ordinary `USER.json` plus curated-pack view.

See [Frequency packs and USER.json](docs/FREQUENCY_PACKS.md) for storage and file precedence.

## Build and install

Requirements:

- JDK 17
- Android SDK compatible with compile/target SDK 36
- Android device with BLE support
- USB or wireless ADB access for installation

Build unit tests and the debug APK:

```bash
./gradlew test assembleDebug
```

Or use the repository helper:

```bash
./build.sh
```

Build, automatically select the Pixel 6, install, and launch:

```bash
./run_pixel6.sh
```

Install an already-built APK:

```bash
./install_pixel6.sh
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

See [Development workflow](docs/DEVELOPMENT.md) for the repository conventions and field-test packaging command.

## Current limitations and planned work

- EiBi refresh is manual; NOW has no background refresh, alarm, notification, or automatic timed-tuning behavior.
- The curated-pack download flow from GitHub is not implemented yet; packs are imported from local files.
- ATS numbered-memory copy is not implemented and will remain optional.
- Native ATS CW handling is not part of the proven firmware checkpoint; MemPuck maps CW to USB.
- The ATS Mini limits broadcast-FM tuning to 10 kHz resolution.
- Absolute volume is not available in the proven ATS firmware.
- Stock-protocol tuning is status-driven and can take longer than the atomic `Z` path when the receiver starts on an unrelated band.

MemPuck for ATS Mini is intended to remain focused on the ATS Mini rather than grow into a general SDR suite.

## License

MemPuck for ATS Mini is licensed under the
[PolyForm Noncommercial License 1.0.0](LICENSE). It may be used, studied,
modified, and shared for permitted noncommercial purposes. Commercial use,
manufacture, sale, resale, or incorporation into a commercial product requires
a separate written license from the copyright holder.

The ATS Mini firmware fork is a separate repository and retains the upstream
MIT license and notices.
