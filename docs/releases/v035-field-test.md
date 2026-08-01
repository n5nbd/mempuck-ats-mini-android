# MemPuck for ATS Mini v0.35.0 Field Test

This is the final public testing release planned before v1.0.

## Highlights

- Supports both official ATS Mini firmware v2.34+ and the optional MemPuck `Z` extension.
- Shows `ONLINE:STOCK` for official firmware and `ONLINE:FAST` for atomic `Z` tuning.
- Lets existing ATS Mini owners use MemPuck without a mandatory firmware update.
- Starts VFO and memory scan dwell only after the receiver confirms the requested tune, preserving the full listener decision window even during slower AM-to-SSB stock-firmware transitions.
- Adds the NOW source, which downloads and caches EiBi data and generates currently active shortwave broadcast memories.
- Provides the tagged local memory library, curated frequency packs, `USER.json` overrides, favorites, scan skip, filtering, VFO/MEM operation, direct entry, scanning, and volume control.
- Finalizes the compact `MemPuck / Memory Manager` and `ATS Mini / ONLINE:STOCK|FAST` header and the `/MemPuck` source-path presentation.

## Hardware validation

Validated on:

- Google Pixel 6
- ATS Mini V4
- Official ATS Mini firmware 2.35 using stock `B`/`M`/`F` control
- MemPuck ATS Mini firmware extension using `Z` protocol v1

Both protocol paths passed tuning, band and mode transitions, fresh-launch reconnect, memory recall, VFO scanning, memory scanning, and one-second confirmed dwell testing.

## Receiver setup

Enable:

```text
Settings -> Bluetooth -> Ad hoc
Sleep mode -> Unlocked
```

The app probes `Z?` automatically. A receiver returning `OK,Z,1` uses FAST tuning; compatible official firmware falls back automatically to STOCK tuning.

## Status

This remains a field-test release. Report reproducible defects with the phone model, ATS Mini hardware version, firmware version, protocol state shown in the header, and relevant DEBUG output.
