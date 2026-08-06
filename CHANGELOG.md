## 1.0.1-dev.05 — phase-stable WEFAX white-reference AFC

- Preserve the proven full-start and arbitrary-phase mid-image acquisition paths from dev.04; AFC refinement begins only after reception is already running.
- Replace support-edge averaging with a rolling phase profile that finds the protocol's recurring 25 ms white reference pulse at any horizontal phase.
- Estimate offset from uncorrected raw frequency, require repeated cross-line agreement, and reject candidates too far from the current plausible white tone.
- Apply bounded correction changes only between complete lines as a scalar frequency subtraction; never retune or reset the FM detector during the page.
- Prevent picture content, fades, or the current correction from feeding back into the estimator.
- Add regressions for arbitrary-phase late join across 0, ±250, ±500, and ±750 Hz, attenuated positive-offset white references, zero-offset no-runaway behavior, and acquisition non-restart.
- Leave fixed 120 LPM timing, 1809-pixel raster geometry, raw PCM, and every SSTV path unchanged.
- Update About/build metadata to `1.0.1-dev.05`, version code 106.

## 1.0.1-dev.04 — bounded WEFAX support-edge AFC

- Reverted the rejected dev.03 rolling minimum-window estimator that could chase picture content and run away.
- Kept the proven wide full-start and mid-join WEFAX acquisition behavior.
- Defined the displayed value as the correction applied to the audio frequency, so a +500 Hz receiver offset reports approximately -500 Hz correction.
- Added a 32-line raw-frequency histogram for late-join refinement.
- Estimates black and white support edges independently and updates only when both imply the same offset and the observed tone span is wide enough.
- Requires three agreeing rolling windows and applies bounded steps; narrow or one-sided image content cannot move the correction.
- Keeps the estimator independent of corrected output and never retunes the demodulator during tracking, preventing positive feedback.
- Left fixed 120 LPM timing, 1809-pixel raster geometry, raw PCM, and SSTV unchanged.

## 1.0.1-dev.02 — WEFAX true AFC grayscale normalization (2026-08-06)

- Preserve the dev.01 wide WEFAX acquisition range and successful full-start/mid-join locking through the tested ±750 Hz offsets.
- Freeze one page-level WEFAX frequency correction at acquisition instead of allowing picture content or fades to change the tone map.
- Retune the WEFAX FM detector around the shifted 1900 Hz subcarrier after lock so large offsets are demodulated near baseband center.
- Subtract the locked correction from every raster sample, then map the fixed 1500 Hz black and 2300 Hz white tones across the standard 800 Hz span.
- Use the same correction path for normal phasing acquisition and mid-image late join.
- Preserve fixed 120 LPM timing, 1809-pixel raster construction, raw PCM, brightness/contrast editing, and all SSTV paths unchanged.
- Add full-start and late-join grayscale-invariance regressions across 0, ±250, ±500, and ±750 Hz; all 11 standalone WEFAX regressions pass.
- Update About/build metadata to `1.0.1-dev.02`, version code 103.

## 1.0.1-dev.01 — WEFAX wide-range AFC (2026-08-06)

- Expand WEFAX-only audio AFC from fixed 1900 Hz acquisition assumptions to a bounded ±1000 Hz correction range.
- Add a decimated coarse tone histogram that identifies the shifted black/white pair by its expected WEFAX tone separation before phasing lock.
- Widen only the WEFAX FM-demodulator baseband so both 1500/2300 Hz tones remain measurable at large common offsets.
- Normalize late-join WEFAX pages from their observed tone range instead of forcing nominal 1500/2300 Hz mapping.
- Preserve raw PCM, fixed 120 LPM / IOC 576 timing, 1809-pixel sync-to-sync raster construction, page-lock behavior, and every SSTV path unchanged.
- Add synthetic regressions for phasing acquisition at ±850 Hz and late-join normalization at ±400 Hz.
- Update About/build metadata to `1.0.1-dev.01`, version code 102.

## 0.36.0-dev.32 — Scottie S2 live decoder (2026-08-02)

- Add Scottie S2 SSTV decoding at 320 × 256 with VIS code 56, 88.064 ms RGB channels, and 277.692 ms line timing.
- Add compact `S2` manual selection beside `AUTO`, `R36`, `M1`, `M2`, and `S1`.
- Include Scottie S2 as a fifth AUTO acquisition candidate without changing the proven Robot 36, Martin M1, Martin M2, or Scottie S1 decoder and mode files.
- Support live hot-switching and manual late-entry recovery from the next complete Scottie S2 line without restarting the microphone or replaying buffered audio.
- Preserve completed-image protection, Light-theme source-color preview, Dark/Hue monochrome preview, partial color SAVE, and AUTO rearming.
- Add Scottie S2 VIS, callback-frame alias, full-frame completion, manual mid-stream, wrong-VIS rejection, RGB order, and neutral-gray regression coverage.
- Updated About/build metadata to `0.36.0-dev.32`, version code 71.

## 0.36.0-dev.31 — Scottie S1 live decoder (2026-08-02)

- Add Scottie S1 SSTV decoding at 320 × 256 with VIS code 60 and the standard 428.22 ms line timing.
- Add compact `S1` manual selection beside `AUTO`, `R36`, `M1`, and `M2`.
- Include Scottie S1 as a fourth AUTO acquisition candidate without changing the proven Robot 36, Martin M1, or Martin M2 decoder and mode files.
- Support live hot-switching and manual late-entry recovery from the next complete Scottie line without restarting the microphone or replaying buffered audio.
- Preserve dev.30 completed-image protection: the existing image remains visible and SAVE/SHARE-capable until Scottie S1 publishes its first actual image line.
- Preserve original-color Light-theme preview and source-color SAVE, with theme-aware monochrome preview in Dark and Hue themes.
- Add Scottie S1 VIS, callback-frame alias, full-frame completion, manual mid-stream, wrong-VIS rejection, RGB order, and neutral-gray regression coverage.
- Updated About/build metadata to `0.36.0-dev.31`, version code 70.

## 0.36.0-dev.30 — Completed-image protection (2026-08-02)

- Preserve any completed or useful partial IMG frame when `AUTO`, `R36`, `M1`, or `M2` is selected accidentally or intentionally.
- Keep the protected frame visible and SAVE/SHARE-capable while the replacement decoder is only armed, waiting for VIS, or reporting acquisition metadata.
- Replace the protected frame only after the newly selected decoder publishes its first actual image line.
- Apply the same protection when starting a new LISTEN session and when AUTO rearms after a completed transmission.
- Keep CLEAR as the immediate, explicit way to discard the displayed image.
- Leave Robot 36, Martin M1, Martin M2, source-color SAVE, and theme-preview rendering unchanged.
- Updated About/build metadata to `0.36.0-dev.30`, version code 69.

## 0.36.0-dev.29 — Light-theme source-color IMG preview (2026-08-02)

- Render the decoder's original reconstructed color frame directly on the IMG surface when the app is using the Light theme.
- Preserve the established theme-aware monochrome preview in Dark and Hue themes.
- Keep incomplete rows visually integrated with the active panel background while an image is still decoding or after a partial live-mode recovery.
- Leave SAVE unchanged as the original decoded color PNG with black unrecovered rows, and leave SHARE unchanged as the theme-aware monochrome rendition.
- Make the preview policy explicit from `ThemeChoice.Light` rather than inferring it from arbitrary palette colors.
- Leave all Robot 36, Martin M1, and Martin M2 decoder and mode implementations unchanged.
- Updated About/build metadata to `0.36.0-dev.29`, version code 68.

## 0.36.0-dev.28 — Martin M2 live decoder (2026-08-02)

- Add Martin M2 SSTV decoding at 320 × 256 with VIS code 40 and the standard 73.216 ms G/B/R channel timing.
- Add compact `M2` manual selection beside `AUTO`, `R36`, and `M1`.
- Include Martin M2 as a third AUTO acquisition candidate while preserving the proven Robot 36 and Martin M1 decoder files byte-for-byte.
- Support live hot-switching to and from M2 without restarting the microphone; partial M2 images use the existing color SAVE path.
- Rearm AUTO with R36, M1, and M2 candidates after a completed image so consecutive transmissions can change mode without CLEAR.
- Add Martin M2 VIS, callback-boundary, progressive color, full-frame completion, manual mid-stream, and wrong-VIS rejection regression coverage.
- Updated About/build metadata to `0.36.0-dev.28`, version code 67.

## 0.36.0-dev.27 — Live manual SSTV mode switching (2026-08-02)

- Rename the manual Robot 36 control from `SSTV` to the compact `R36` label.
- Allow `AUTO`, `R36`, and `M1` to be selected while LISTEN remains active without stopping or restarting the microphone session.
- Replace only the active decoder at a live mode change and begin manual recovery with the next PCM block; no buffered replay or catch-up worker is used.
- Clear the old preview on a live mode change so the newly selected mode can be judged immediately from its progressive partial image.
- Allow SAVE after at least one decoded line, preserving original decoded color and filling the unrecovered remainder of a partial frame with black.
- Keep WEFAX unavailable during an active LISTEN session and preserve the proven AUTO reacquisition behavior between complete Robot 36 and Martin M1 images.
- Updated About/build metadata to `0.36.0-dev.27`, version code 66.

## 0.36.0-dev.26-r3 — Protect live audio from diagnostic trace I/O (2026-08-01)

- Preserve the phone-tested AUTO switching behavior from dev.26-r2 and leave both Robot 36 and Martin M1 production decoder implementations unchanged.
- Stop rendering and writing Robot 36 line-trace CSV/PNG files synchronously from the AudioRecord callback.
- Retain compact line-probe metrics, the complete debug log, timeline, and raw grayscale frame diagnostics.
- Exclude historical line-trace files from new diagnostic ZIPs so stale traces cannot be mistaken for the current pass.
- Correct the observed line-120 microphone overrun that removed about 1,680 samples and forced the remaining Robot 36 frame into timeout-only reconstruction.
- Updated About/build metadata to `0.36.0-dev.26-r3`; version code remains 65.

## 0.36.0-dev.26-r2 — AUTO rearm after completed SSTV image (2026-08-01)

- Preserve the completed image on the IMG surface and keep SAVE available after an AUTO-decoded transmission.
- Release AUTO's selected decoder only after the completed-frame callback returns, then recreate both Robot 36 and Martin M1 acquisition candidates.
- Allow the next transmission to select either VIS mode without CLEAR, leaving the IMG page, or restarting LISTEN.
- Keep explicit `SSTV` and `M1` selections manually locked and leave both production decoder implementations untouched.
- Updated About/build metadata to `0.36.0-dev.26-r2`; version code remains 65.

## 0.36.0-dev.26-r1 — Martin M1 real-audio VIS acquisition correction (2026-08-01)

- Preserve the phone-tested manual Martin M1 image path and the proven Robot 36 production files unchanged.
- Keep the first 1900 Hz leader window as a broad plausibility gate instead of rejecting a real header when microphone/demodulator startup distorts that early measurement.
- Validate the stable post-break leader with a bounded 150 Hz tolerance, then retain the existing leader-offset calibration, VIS tone checks, parity check, and exact VIS 44 requirement.
- Add regression coverage for a degraded first leader window plus a shifted but internally valid Martin M1 header.
- Updated About/build metadata to `0.36.0-dev.26-r1`; version code remains 65.

## 0.36.0-dev.26 — 2026-08-01

- Added an isolated Martin M1 SSTV decoder for VIS code 44 at 320 × 256.
- Added `M1` as an explicit manual/raw decoder choice while preserving the existing `SSTV` manual Robot 36 path.
- `AUTO` now runs Robot 36 and Martin M1 acquisition candidates in parallel until a valid VIS header selects one decoder.
- Extended the temporary in-memory PCM recovery buffer to 130 seconds so a complete Martin M1 transmission can be retained locally.
- Kept the live image theme-aware monochrome and the saved PNG in the decoder's original color.
- Added Martin M1 timing, callback-boundary, progressive-frame, and RGB channel-order tests.
- Left the proven Robot 36 decoder and mode production files byte-for-byte unchanged.

## 0.36.0-dev.25-r9 — Robot 36 chroma phase correction (2026-08-01)

- Preserve the proven r7/r8 physical-sync, sliding-buffer, line-completion, pair-calibration, themed-preview, and camera-roll paths unchanged.
- Correct Robot 36 chroma phase to match the mode definition and pinned upstream decoder: the low-separator line carries V (red difference) and the following high-separator line carries U (blue difference).
- Pass calibrated U and V to `YUV2RGB` in their proper order so red source content decodes red and blue source content decodes blue.
- Add explicit synthetic regression coverage for both positive V (`R > B`) and positive U (`B > R`).
- Updated About/build metadata to `0.36.0-dev.25-r9`, version code 64.

## 0.36.0-dev.25-r8 — Robot 36 pair-calibrated chroma (2026-08-01)

- Preserve the r7 physical-sync, callback-alias, monotonic-anchor, completion, themed-preview, and camera-roll paths unchanged.
- Calibrate each complete Robot 36 U/V pair from its measured low and high separator tones, removing the common chroma DC offset and gain error that produced the strong green cast.
- Reconstruct both rows directly from retained luminance plus calibrated U and V values instead of repacking temporary color channels.
- Sample chrominance from a centered, guarded inner window and prime both filter passes from interior averages so porch/end transients do not contaminate the outer color pixels.
- Extend line probes with chroma calibration mode, pair offset, pair gain, and post-calibration Y/U/V/R/G/B means.
- Add synthetic regression coverage proving offset/gain-distorted neutral chroma remains neutral and positive V still decodes red rather than blue.
- Updated About/build metadata to `0.36.0-dev.25-r8`, version code 63.

## 0.36.0-dev.25-r7 — Robot 36 callback-frame sync alias correction (2026-08-01)

- Correct a one-AudioRecord-callback (882-sample) ambiguity in physical sync positions when the demodulator reports a line pulse across a 20 ms callback boundary.
- Select the nearest expected sync phase from the unshifted, previous-frame, or next-frame representation before rejecting a pulse.
- Preserve the frame-alias adjustment while applying the remaining small demodulator-bias correction.
- Keep physical sync authoritative after line 120 instead of falling into timeout-only recovery with every line sampled about 20 ms late.
- Preserve the r6 color path, themed monochrome preview, and `DCIM/MemPuck` camera-roll save behavior.
- Add direct regression coverage for positive and negative callback-frame aliases and for unrelated false pulses.

## 0.36.0-dev.25-r6 — Robot 36 physical-sync recovery (2026-08-01)

- Preserve the uncommitted dev.25-r5 source-color reconstruction, U/V correction, themed monochrome preview, and confirmed `DCIM/MemPuck` camera-roll save path.
- Keep the VIS-confirmed first sync anchor in the rolling sample buffer instead of discarding the first 132 post-sync samples before that line can be decoded.
- Make accepted physical 9 ms sync pulses authoritative again; timeout prediction is now limited to genuinely missed syncs.
- Stage candidate sync history and frequency-offset updates in temporary arrays so rejected pulses cannot poison the accepted line clock.
- Add explicit accepted/rejected sync diagnostics and regression checks requiring normal Robot 36 to decode from physical sync anchors through rolling-buffer rollover.
- Leave right-edge sampling, final color-balance tuning, and Martin support unchanged in this correction.
- Updated About/build metadata to `0.36.0-dev.25-r6`, version code 61.

## 0.36.0-dev.25-r5 — Robot 36 absolute stream anchors (2026-08-01)

- Preserve the uncommitted dev.25-r4 source-color reconstruction, U/V phase correction, live monochrome preview, and `DCIM/MemPuck` camera-roll save path.
- Track Robot 36 sync and decoded-line ownership with absolute 64-bit stream sample positions independent of the seven-second rolling sample buffer.
- Convert an absolute line anchor to the current rolling-buffer offset only when pixel reconstruction reads that line.
- Refuse non-monotonic or expired anchors instead of reconstructing repeated rows from a saturated local buffer index.
- Rebase an expired predicted anchor to the first still-buffered scan-line position without fabricating replacement rows.
- Add dropped-sync, false-pulse, and rolling-buffer rollover regression coverage proving anchors continue beyond the buffer capacity.
- Leave right-edge sampling, color balance tuning, and Martin support unchanged in this correction.
- Updated About/build metadata to `0.36.0-dev.25-r5`, version code 60.

## 0.36.0-dev.25-r4 — Robot 36 monotonic timeout recovery (2026-08-01)

- Preserve the dev.25-r3 U/V phase correction, chroma filtering, source-color frame, and confirmed `DCIM/MemPuck` camera-roll save path.
- Give every Robot 36 scan-line sample anchor single ownership so the same buffered line can never be reconstructed twice.
- Reject false 9/20 ms sync candidates before they can mutate accepted timing history.
- After a missed sync, consume one complete pending scan line and advance the predicted anchor by exactly one nominal 6,615-sample Robot 36 line.
- Reconcile the next physical sync against that predicted clock instead of inserting repeated horizontal bands.
- Add anchor/source diagnostics and a dropped-sync plus false-pulse regression test.
- Leave right-edge sampling and Martin support unchanged in this correction.
- Updated About/build metadata to `0.36.0-dev.25-r4`, version code 59.

## 0.36.0-dev.25-r3 — Robot 36 U/V phase correction (2026-08-01)

- Keep the committed dev.23 acquisition, sync, complete-line luminance, concealment, and completion paths unchanged.
- Correct the empirically reversed Robot 36 chroma phase: the low-separator line supplies U and the following high-separator line supplies V, so known red content no longer decodes blue.
- Filter only the 44 ms chrominance window with edge-primed forward/backward smoothing, preventing separator and reset transients from tinting the frame green.
- Add pair diagnostics for separator decisions and mean Y/U/V/R/G/B levels at the existing probe lines.
- Retain confirmed camera-roll saving to `DCIM/MemPuck` and leave Martin support out of this slice.
- Updated About/build metadata to `0.36.0-dev.25-r3`, version code 58.

## 0.36.0-dev.25-r2 — Robot 36 chroma recovery and camera-roll finalization (2026-08-01)

- Keep the proven dev.23 complete-line luminance path unchanged.
- Restore the pinned upstream Robot36 forward/backward low-pass path for the 44 ms chrominance channel, fixing the dev.25 green cast and color-phase distortion.
- Save source-color PNG files to `DCIM/MemPuck` with `DATE_TAKEN`, finalize the MediaStore entry explicitly, and report the actual save error when finalization fails.
- Keep the live IMG preview theme-aware monochrome and leave SHARE unchanged.
- Updated About/build metadata to `0.36.0-dev.25-r2`, version code 57.

## 0.36.0-dev.25 — Robot 36 color reconstruction and camera-roll save (2026-08-01)

- Restore Robot 36 Y/U/V pair reconstruction while retaining the proven dev.23 acquisition and complete-line timing path.
- Keep the live IMG surface theme-aware monochrome; decoded source color remains available behind the preview.
- Change SAVE to write the full-color PNG to `Pictures/MemPuck` through Android MediaStore.
- Preserve SHARE as the current theme-aware monochrome rendition.
- Preserve confidence-gated isolated dropout concealment and interpolate repaired color pixels from adjacent rows.
- Add a synthetic even/odd Robot 36 color-pair regression test.
- Updated About/build metadata to `0.36.0-dev.25`, version code 56.

## 0.36.0-dev.23 — confidence-gated dropout concealment (2026-08-01)

- Leave every Robot 36 line scoring 95 or higher completely untouched.
- Attempt isolated dropout repair only when one suspect line scores below 95 and both adjacent lines score at least 95.
- Preserve the existing conservative 3–80 pixel run limits and neighbor interpolation.
- Keep all AUTO acquisition, completion, replay-buffer, SAVE, SHARE, and IMG page behavior unchanged.
- Updated About/build metadata to `0.36.0-dev.23`, version code 54.

## 0.36.0-dev.08 — Robot 36 clean luminance demodulation (2026-08-01)

- Restrict filtering to the Robot 36 luminance window so separator/chroma cannot contaminate Y.
- Reject FM discriminator phase-wrap spikes with a trimmed per-pixel mean and clamp mapped luminance to 0..1.
- Preserve linear grayscale internally instead of applying premature square-root compression.
- Replace fixed Bayer ordered dithering with serpentine Floyd-Steinberg error diffusion for cleaner theme-aware monochrome photographs.
- Extend line probes to report post-demodulation mapped luminance values.
- Updated About/build metadata to `0.36.0-dev.08` and IMG diagnostics to slice 07.

## 0.36.0-dev.07 — Robot 36 line sampling probe (2026-08-01)

- Changed luminance reconstruction from one demodulated point per pixel to an average across each pixel's complete sample span.
- Added deterministic Robot 36 line probes at lines 1, 2, 60, 120, 180, and 240, including luminance range and sixteen horizontal buckets.
- Clarified diagnostics that the three-millisecond first-pixel offset is measured after the detected end of the nine-millisecond sync pulse.
- Updated IMG diagnostics to slice 06 and application metadata to `0.36.0-dev.07`.

## 0.36.0-dev.06

- Fix Robot 36 luminance-first decoding crash caused by writing a second chroma-paired row into a one-row live pixel buffer.
- Keep live reconstruction strictly luminance-only while preserving progressive output.
- Remove the Kotlin 2.5 expression-body return warning from IMG diagnostic audio logging.

# Changelog

## 0.36.0-dev.05 — Robot 36 luminance-first reconstruction (2026-08-01)

- Changed live Robot 36 reconstruction to emit one complete luminance row for every received scan line.
- Removed chroma-pair dependence from the first-pass display so separator mistakes cannot collapse the frame into vertical texture.
- Preserved adaptive acquisition, VIS confirmation, frequency correction, progressive rendering, and the local PCM recovery buffer.
- Added Robot 36 geometry diagnostics after VIS lock.
- Cleaned the Kotlin expression-body warning in `ImageDiagnosticLogger.begin`.
- Updated application metadata to version `0.36.0-dev.05`; retained development version code `36`.

## 0.36.0-dev.04 — adaptive progressive Robot 36 decode (2026-08-01)

- Begin a provisional Robot 36 raw decode after two plausible line-sync pulses instead of requiring a valid VIS header before showing pixels.
- Broaden initial sync acquisition so translated SSTV audio can lock several hundred hertz away from nominal.
- Estimate and smooth a global tone-frequency correction from repeated sync pulses, and apply it to subsequent pixel decoding.
- Keep VIS detection active so a valid Robot 36 header can promote the provisional decode to a confirmed mode.
- Show the live correction and confidence on the IMG status line and add adaptive-lock details to the diagnostic log.
- Preserve the existing 60-second local PCM capture for later replay and early-line recovery.
- Updated application metadata to version `0.36.0-dev.04`, version code `36`.

## 0.35.0 — final pre-1.0 field-test release (2026-08-01)

- Changed the header subtitle from `ATS Mini Radio Controller` to `Memory Manager`.
- Combined connection and firmware capability into one compact line: `ONLINE:STOCK`, `ONLINE:FAST`, `OFFLINE:STOCK`, or `OFFLINE:FAST`.
- Retained the last verified capability after disconnect so the offline header remains informative, and aligned the main offline prompt with the same wording.
- Removed the unexplained source-directory prose and rendered the selected directory as a path-like value such as `/MemPuck`.
- Promoted the hardware-tested tree to the final public field-test checkpoint before v1.0.
- Updated application metadata to version `0.35.0`, version code `35`.

## 0.34.0-dev34 — confirmed scan dwell and NOW wording (2026-08-01)

- Changed VFO and memory scanning so the configured dwell begins only after the
  receiver confirms the requested band, mode, and frequency. Slow stock-firmware
  transitions no longer consume the listener's decision window.
- Kept the atomic `Z` path fast while allowing stock `B/M/F` scans to take the
  extra transition time they actually require.
- Removed `ORDINARY` from the NOW-page explanation.
- Renamed the NOW cache timestamp label from `DOWNLOADED` to `REFRESHED`.
- Updated application metadata to version `0.34.0-dev34`, version code `34`.

## 0.33.0-dev33 — dual Z and stock-firmware tuning (2026-08-01)

- Kept the proven atomic `Z` tuning path for receivers running the MemPuck firmware extension.
- Added automatic fallback when `Z?` is unanswered or rejected: official ATS Mini firmware v2.34 or newer is recognized from its live status record.
- Added a status-verified stock tuning transaction that selects `ALL` or `VHF`, selects AM/LSB/USB as required, and then sends the stock `F<frequency>` command.
- Preserved logical CW as a MemPuck mode while using USB as the ATS hardware mode on both protocol paths.
- Added command retry limits, delayed-status protection, full tune timeout handling, and startup auto-connect acceptance for compatible stock receivers.
- Added protocol-state labels so RADIO and CFG identify `Z` versus `STOCK` tuning.
- Added unit coverage for HF, FM, CW, relative band/mode selection, delayed monitor records, and stock `F` command formatting.
- Updated application metadata to version `0.33.0-dev33`, version code `33`.

## 0.32.0-dev32 — NOW dynamic EiBi source (2026-07-31)

- Added the top-level `NOW` source page and a persisted application source state
  that switches LIST between curated memories and generated live broadcasts.
- Added manual HTTP download, private caching, validation, and local parsing of
  the EiBi frequency schedule. A failed update preserves the previous cache.
- Generated ordinary AM `MemoryEntry` records for broadcasts active at the
  current UTC time, including weekday and overnight schedule handling.
- Limited NOW to the shortwave portion of EiBi and excluded recognizable
  utility, jammer, and digital-service records that are not listenable AM programming.
- Added compact band tags, three-letter country tags, and distinct `$LANGUAGE`
  tags from available EiBi metadata. Target and site details remain in notes.
  Multiple simultaneous stations on one frequency are combined into one
  frequency-keyed memory.
- Reused LIST expansion, filtering, tuning, MEM stepping, scanning, and editing
  for NOW records.
- Hid one-tap `FAV`, `SKIP`, and delete controls while NOW is loaded. An explicit
  editor save writes a permanent override to `USER.json`.
- Added `LOAD NOW` and `LOAD SRC` controls without modifying either underlying
  source, and regenerate a persisted NOW state from cache on fresh launch.
- Centered the portrait-first interface at a maximum content width on wider
  displays.
- Updated application metadata to version `0.32.0-dev32`, version code `32`.

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
