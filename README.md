# MemPuck for ATS Mini — connect flow, FM display, and release-volume v007

This checkpoint builds on the hardware-tested v006 connected-display/config
baseline and keeps the working tuning, scanning, theme, and sleep behavior.

## Added in v007

- The disconnected status panel now reads `YOU'RE DISCONNECTED. TAP HERE TO
  CONNECT.` and acts as the connection shortcut.
- Tapping that panel opens CONFIG and immediately starts the ATS Mini BLE scan.
  If Android BLE permission has not been granted yet, MemPuck requests it and
  starts the scan as soon as permission is available.
- FM frequency wheels now match the ATS Mini display and stop at the receiver's
  real 10 kHz resolution: for example, `101.70` instead of `101.700.000`.
- HF/AM/SSB wheels continue to display and control the full hertz value.
- The volume control jumps to the touched position immediately, previews locally
  while dragged, and performs one volume transaction only when the finger is
  released.
- The stock ATS protocol has only one-step `V`/`v` controls, so MemPuck batches
  the required delta into immediate BLE-safe chunks instead of slowly issuing
  timed commands. A future direct ATS volume setter could reduce that to one
  protocol command without changing the UI.

## Existing behavior retained

- Connected screen-awake guard and inactivity dimming.
- DARK, LIGHT, and HUE display modes.
- Collapsible DEBUG protocol log.
- Latched VFO scanning stopped by the next touch anywhere.
- Frequency-driven low-band/FM mode selection.
- Absolute `Z` tuning and live status over BLE.

## Receiver setup

```text
Settings → Bluetooth → Ad hoc
Sleep mode → Unlocked (during development)
```

The patched firmware should answer:

```text
Z?  →  OK,Z,1
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
