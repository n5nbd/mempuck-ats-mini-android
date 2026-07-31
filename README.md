# MemPuck for ATS Mini — latched scan and FM controls v005

This checkpoint builds on the hardware-tested v004 VFO/MEM and volume baseline.
It corrects the FM digit behavior and makes VFO scanning work as a hands-off
listening tool.

## Added in v005

- Holding an upper or lower digit arrow starts VFO scanning in that direction.
- Releasing the digit does **not** stop the scan.
- The next touch anywhere in the MemPuck app stops scanning and is consumed, so
  it does not accidentally activate another control.
- In FM, the full frequency remains visible, including trailing zeros.
- The four least-significant FM digits have no up/down arrows because the ATS
  Mini FM backend tunes in 10 kHz units.
- FM targets are normalized to the receiver's 10 kHz grid before `Z` is sent,
  preventing the target display from fighting the frequency reported by the
  radio.

## ATS Mini FM-resolution note

The ATS Mini's FM receiver tunes in 10 kHz increments. MemPuck therefore shows
all frequency digits but offers FM digit controls only down to the 10 kHz
position. This is an ATS Mini receiver limitation, not a MemPuck list-size or UI
restriction. The entire 64–108 MHz ATS FM range remains available, including the
spectrum below the conventional broadcast band.

## Frequency-driven operation retained

- **150 kHz through 30 MHz:** `LSB | USB | CW | AM`.
- **64 through 108 MHz:** mode row hidden; FM selected automatically.
- **30–64 MHz gap:** rejected before any BLE tuning command is sent.
- Logical CW remains mapped to ATS USB.

## Receiver setup

```text
Settings → Bluetooth → Ad hoc
Sleep mode → Unlocked (during development)
```

The patched firmware should answer:

```text
Z?\r  →  OK,Z,1
```

## Build and install

```bash
./build.sh
./install_pixel6.sh
```

Or:

```bash
./run_pixel6.sh
```
