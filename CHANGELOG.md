# Changelog

## v004 — VFO/MEM, hold scanning, and volume

- Converted the center VFO button into a dual-state `VFO` / `MEM` control.
- Preserved the already working VFO arrow tuning behavior.
- Added press-and-hold scanning to every frequency digit arrow; release stops
  scanning.
- Used one shared 1.5-second dwell constant for VFO scanning and the future
  memory scanner.
- Removed the explanatory direct-frequency label beneath the digit wheels.
- Added a horizontal 0–63 ATS volume slider beneath the arrow controls.
- Kept MEM mode visible but inert until the personal-memory slice lands.

## v003 — frequency-driven ATS mode selection

- Removed the impossible FM button from the low-band mode row.
- Hid the mode row entirely in the ATS broadcast-FM range.
- Made frequency select the receiver path automatically: low band restores the
  last LSB/USB/CW/AM choice, while 64–108 MHz always uses FM.
- Rejected direct entries in the ATS Mini's untunable 30–64 MHz gap before any
  BLE command is sent.
- Made digit and VFO controls jump across that gap to the next valid edge.
- Preserved logical CW as a low-band MemPuck mode mapped to ATS USB.

## v002 — radio control and MemPuck phone UI

- Replaced the generic Android setup screen with the canonical MemPuck portrait
  layout.
- Added dark and light MemPuck themes.
- Added ATS live-status parsing and display.
- Added direct `Z` frequency/mode tuning and confirmation handling.
- Added digit controls, direct entry, modes, and step controls.
- Added logical CW-to-USB translation.
- Kept LIST and SOURCE visible but intentionally inactive until the memory and
  library slices.
