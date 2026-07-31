# MemPuck for ATS Mini — VFO scan and volume controls v004

This checkpoint builds on the hardware-tested v003 frequency-driven tuning
baseline. It keeps the canonical MemPuck phone layout and adds the next radio
control interactions without introducing the memory database yet.

## Added in v004

- The center control is now dual-state: `VFO` / `MEM`.
- In VFO state, the existing single and double arrow tuning remains unchanged.
- Holding the upper or lower arrow on any frequency digit starts repeated tuning
  in that direction. Releasing the control stops the scan.
- VFO scan steps use a shared 1.5-second dwell constant intended to be reused by
  memory scanning. Only one confirmed `Z` tune is allowed per dwell cycle.
- The explanatory text between the digit wheels and direct-entry field is gone.
- A horizontal ATS volume slider appears below the VFO/MEM arrow row. It maps the
  ATS monitor's 0–63 volume range to the stock `V` / `v` commands.

`MEM` is intentionally inert in this slice because the authoritative local
memory database has not been added yet. Selecting MEM disables VFO edits and
scanning without pretending numbered ATS slots are MemPuck memories.

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
