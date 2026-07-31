# MemPuck for ATS Mini — connected display and compact config v006

This checkpoint builds on the hardware-tested v005 FM scan-control baseline. It
keeps the working radio controls unchanged and concentrates on the Pixel 6 as a
stable dedicated controller.

## Added in v006

- While the ATS Mini BLE link is ready, MemPuck keeps the Android display awake.
- After 30 seconds without a touch, the app dims the display to 6% brightness.
- Any touch restores the normal Android brightness and restarts the dim timer.
- Disconnecting or closing the controller restores Android's normal brightness
  and screen-sleep behavior.
- The CONFIG disconnect button is now `DISCO`, keeping the control row balanced.
- The protocol log is hidden behind a `DEBUG` toggle. When DEBUG is off, the
  panel shrinks to the toggle alone. When enabled, it opens a 220 dp scrolling
  log showing the latest 80 protocol lines.
- Display choices are now `DARK`, `LIGHT`, and `HUE`.
- DARK is white on black; LIGHT is black on white.
- HUE reveals a slider and uses the selected hue on black throughout MemPuck.
  Theme and hue selection are saved locally.

## Existing v005 behavior retained

- Frequency-driven low-band/FM mode selection.
- Full FM frequency display with controls only down to the ATS Mini's real
  10 kHz FM tuning resolution.
- Latched VFO scanning stopped by the next touch anywhere.
- VFO/MEM selector and horizontal ATS volume control.
- Absolute `Z` tuning and live status over BLE.

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
