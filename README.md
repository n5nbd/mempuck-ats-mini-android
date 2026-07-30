# MemPuck for ATS Mini — Android BLE capability slice

This is the first hardware-testable Android checkpoint for MemPuck.

## Included

- Native Kotlin and Jetpack Compose application.
- Pixel 6 as the primary hardware target; minSdk 26 for later inexpensive Android devices.
- BLE scan filtered by the ATS Mini's advertised Nordic UART service.
- Connect/disconnect through Android's framework BLE APIs; no third-party BLE library.
- Notification subscription before sending any command.
- Automatic `Z?\r` capability negotiation after the UART link becomes ready.
- Raw protocol log and explicit `OK,Z,<version>` parsing.
- BLE transport isolated from ATS protocol parsing and from future memory/library domain code.

## Deliberately not included yet

- Frequency/mode controls.
- Status-stream parsing.
- Automatic reconnect and command verification.
- Stock-firmware compatibility state machine.
- Room memory database and frequency-library packs.

Those are separate hardware-testable checkpoints.

## Receiver setup

On the ATS Mini:

```text
Settings → Bluetooth → Ad hoc
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
