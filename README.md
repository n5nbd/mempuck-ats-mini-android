# MemPuck for ATS Mini — frequency-driven radio control v003

This checkpoint keeps the proven Pixel 6 BLE and absolute-tuning foundation,
while making the controls follow the ATS Mini's actual receiver coverage.

## Frequency-driven operation

MemPuck no longer offers impossible mode/frequency combinations:

- **150 kHz through 30 MHz:** show `LSB | USB | CW | AM`.
- **64 through 108 MHz:** hide the mode row and select `FM` automatically.
- **Above 30 MHz and below 64 MHz:** reject direct entry without sending a BLE
  command because the ATS Mini cannot tune that gap.

MemPuck remembers the last low-band mode. Returning from FM restores that mode.
If no valid low-band mode is available, AM is the safe fallback.

Digit and VFO controls tune immediately, so they jump directly between 30 MHz
and 64 MHz when crossing the unsupported gap. Direct text entry remains strict
and reports an error for a frequency in the gap.

## Included foundation

- Native Kotlin and Jetpack Compose application.
- Pixel 6 as the primary hardware target; minSdk 26 for inexpensive dedicated
  Android controllers later.
- BLE scan, connection, Nordic UART notifications, and automatic `Z?`
  capability negotiation.
- ATS Mini 15-field live monitor parsing.
- Direct absolute tuning through `Z<frequency_hz>,<mode>` with confirmation and
  timeout handling.
- Logical MemPuck `CW` mode translated to ATS `USB`.
- Portrait interface copied from the existing MemPuck web layout.
- Dark and light MemPuck themes.
- Digit-by-digit controls, direct entry, and VFO step controls.

## Deliberately not included yet

- Personal memory database.
- Published frequency-library packs.
- Temporary scan queues.
- Stock-firmware band/mode compatibility state machine.
- Automatic reconnect.
- ATS memory-slot management.

## Receiver setup

On the ATS Mini:

```text
Settings → Bluetooth → Ad hoc
```

For development, leave ATS sleep mode set to **Unlocked**. The patched firmware
should answer:

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
