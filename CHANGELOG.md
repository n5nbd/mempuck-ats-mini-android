# Changelog

## v007 — connect shortcut, compact FM display, and release-volume

- Turned the disconnected RADIO status panel into a connection shortcut that
  opens CONFIG and starts BLE discovery.
- Added permission-aware deferred scanning so the same shortcut continues after
  Android grants BLE access.
- Changed the FM frequency wheels to the ATS Mini-style 10 kHz display, such as
  `101.70`, instead of showing four meaningless trailing zero digits.
- Kept all low-band hertz digits and controls unchanged.
- Replaced the Material volume slider interaction with an immediate touch/drag
  control that previews locally and commits only when the finger is released.
- Replaced the old timed volume stepping with one immediate release transaction,
  batched into BLE-safe chunks because current ATS firmware exposes only `V` and
  `v` one-step volume commands.

## v006 — connected display and compact configuration

- Kept the Pixel display awake while the ATS Mini BLE link is ready so Android
  screen sleep does not tear down the active controller session.
- Added a 30-second inactivity dimmer that reduces the connected controller to
  6% brightness; any touch restores the normal Android brightness immediately.
- Replaced the oversized `DISCONNECT` button label with the five-character
  `DISCO` label.
- Collapsed the protocol log behind a `DEBUG` toggle. With DEBUG off, the panel
  contains only the toggle; with DEBUG on, it opens a fixed-height scrolling log.
- Replaced the old theme selector with `DARK`, `LIGHT`, and `HUE`. The hue mode
  uses the selected color on black and persists the selected hue locally.
- Matched Android system bars to the selected dark/light background.

## v005 — latched scanning and FM-resolution controls

- Changed VFO hold scanning to latch after the long press; releasing the digit
  control no longer stops it.
- Added a full-screen touch catcher while scanning so the next touch anywhere
  in MemPuck stops the scan without activating another control.
- Kept the complete FM frequency display but removed arrows from the four
  least-significant digits, leaving 10 kHz as the smallest interactive FM step.
- Normalized FM commands to the ATS Mini's 10 kHz receiver grid to eliminate
  returned-status flutter.
- Documented the 10 kHz FM resolution as an ATS Mini limitation.

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
