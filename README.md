# MemPuck for ATS Mini — Android radio-control slice v002

This checkpoint keeps the proven Android BLE foundation and adds the first
usable MemPuck VFO screen on real ATS Mini hardware.

## Included

- Native Kotlin and Jetpack Compose application.
- Pixel 6 as the primary hardware target; minSdk 26 for later inexpensive
  dedicated Android controllers.
- BLE scan, connection, Nordic UART notifications, and automatic `Z?`
  capability negotiation.
- ATS Mini 15-field live monitor parsing for frequency, mode, band, step,
  bandwidth, volume, RSSI, SNR, voltage, firmware version, and sequence.
- Automatic status-monitor startup without blindly toggling off an already
  active stream.
- Direct absolute tuning through `Z<frequency_hz>,<mode>` with confirmation and
  timeout handling.
- Logical MemPuck `CW` mode translated to ATS `USB`. The future ATS firmware
  CW/filter enhancement remains a separate build.
- Portrait interface copied from the existing MemPuck web layout rather than
  redesigned for Android.
- Canonical dark and light MemPuck themes, selectable in CONFIG and persisted.
- Digit-by-digit frequency controls, direct frequency entry, mode row, and VFO
  step controls.
- BLE setup, capability state, device selection, and protocol log moved into
  the MemPuck-style CONFIG tab.

## Deliberately not included yet

- Personal memory database.
- Published frequency-library packs.
- Temporary scan queues.
- Stock-firmware band/mode compatibility state machine.
- Automatic reconnect.
- ATS memory-slot management.

Those remain separate hardware-testable checkpoints.

## Receiver setup

On the ATS Mini:

```text
Settings → Bluetooth → Ad hoc
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

Or build, install, and launch in one command:

```bash
./run_pixel6.sh
```

Open CONFIG, scan for the ATS Mini, connect, then return to RADIO.
