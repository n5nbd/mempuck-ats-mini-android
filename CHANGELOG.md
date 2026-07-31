# Changelog

## 0.31.0-dev31 — practical scan dwell and CFG cosmetics (2026-07-31)

- Replaced the scan dwell choices with 1, 2, 5, and 10 seconds.
- Made 2 seconds the default and migration fallback for removed dwell choices.
- Moved the seconds unit into `VFO AND MEMORY DWELL (SECONDS)` and kept all
  four dwell controls on one row.
- Tightened the spacing between CFG accordion windows from 2.5 dp to 1 dp.
- Expanded the ABOUT credit into separate `Mark Zimmerman, N5NBD` and
  `Polyform Noncommercial Licence` lines.
- Updated application metadata to version `0.31.0-dev31`, version code `31`.

## 0.30.0-dev30 — scan dwell, compact CFG, and reconnect splash (2026-07-31)

- Added a persistent shared scan-dwell setting for both VFO and memory scans,
  with 0.5, 1, 1.5, 2, and 3 second choices.
- Added a dedicated `SCAN` accordion section to CFG.
- Reduced the vertical gap between collapsed CFG windows by approximately 75%.
- Added an `ABOUT` accordion section with a theme-aware MemPuck silhouette,
  application version, product description, callsign, and license summary.
- Reused the ABOUT presentation as a startup screen while the app looks for,
  connects to, and verifies the remembered ATS Mini.
- Updated application metadata to version `0.30.0-dev30`, version code `30`.

## 0.29.0-dev29 — memory-editor consistency and delete confirmation (2026-07-31)

- Simplified the memory editor toggles to the same `FAV` and `SKIP` labels used
  throughout the LIST interface; selected-state colors indicate whether each
  flag is applied.
- Added a confirmation dialog showing the memory name and frequency before any
  memory deletion.
- Updated application metadata to version `0.29.0-dev29`, version code `29`.

## 0.28.0-dev28 — remembered-radio auto-connect (2026-07-31)

- Remember the BLE address only after an ATS Mini answers the `Z?` capability
  probe successfully.
- On a fresh app launch, scan briefly for that saved receiver and connect to it
  automatically.
- If the saved receiver is absent, fails to connect, or does not pass the
  capability probe, fall back to the normal receiver scan.
- Let any newly selected and verified ATS Mini replace the previously saved
  address automatically.
- Keep `DISCO` manual for the current app session; automatic connection is tried
  again only after a fresh launch.
- Updated application metadata to version `0.28.0-dev28`, version code `28`.

## 0.27.0-dev27 — field-test checkpoint (2026-07-31)

### Radio and display

- Added a blank direct-entry modal in place of the always-visible frequency field.
- Reserved a permanent black Android status bar and kept page content below it.
- Added compact VFO and memory-specific information panels.
- Added persistent HF small, HF large, and VHF VFO tuning-step controls.
- Kept logical CW visible in MemPuck while translating it to ATS USB.
- Preserved ATS-style FM display and 10 kHz tuning resolution.

### Memory library

- Added the authoritative local memory model and editor with frequency, mode,
  name, tags, notes, favorite, and skip fields.
- Added compact expanding LIST rows, tag-cloud filtering, favorite subset
  filtering, and AND/OR tag matching.
- Added inline favorite and skip controls.
- Added manual MEM stepping through all matching records, including skipped
  records.
- Added directional MEM scanning through matching records after excluding skip.
- Preserved active LIST filters when moving between LIST and RADIO.
- Added memory name, filtered position/total, tags, flags, and description to the
  RADIO information panel in MEM mode.

### Frequency sources

- Added a user-selected Android Files frequency directory.
- Added JSON pack validation, import, export, deletion, refresh, and template
  generation.
- Added `USER.json` as the first-loaded override layer. Pack files remain
  unchanged; user edits and deletion markers are written to `USER.json`.
- Added uppercase canonical `#TAG` normalization.

### Configuration and UI

- Added one-open-at-a-time CFG accordion sections with uniform window-style
  title bars.
- Added RADIO LINK, DISPLAY, TUNING STEPS, and DEBUG sections.
- Added DARK, LIGHT, and HUE presentation with persistent hue selection.
- Added the V4 launcher icon and retained the connected wake/dim behavior.
- Updated application metadata to version `0.27.0-dev27`, version code `27`.
- Added the PolyForm Noncommercial 1.0.0 license and required MemPuck notice for public source publication.

### Hardware status

- BLE discovery, Nordic UART communication, capability detection, tuning, FM,
  scanning, volume, memories, filters, and source-directory behavior have been
  exercised on a Google Pixel 6 with an ATS Mini V4.

## 0.7.1-dev8

- Added a dedicated V4 rising-sun launcher icon.
- Prepared a local end-of-night Android checkpoint without pushing.

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
