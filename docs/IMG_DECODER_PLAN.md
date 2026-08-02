# IMG Decoder Development Plan

Branch: `mempuck/img-decoder-v036`

Baseline: MemPuck for ATS Mini v0.35.0 field test, commit `9fd85ba3260aeb5d6a174137b01365ac1bcde301`.

## Goal

Add one shared `IMG` page for receiving and displaying both SSTV and WEFAX images from audio supplied by the ATS Mini. The Android device provides the display, storage, editing, and sharing surface; no decoder support is required from the ATS Mini firmware.

The primary navigation becomes:

```text
RIG  LST  SRC  NOW  IMG  CFG
```

## Page Model

Use one large live image surface for both decoder families. Decoder selection changes how pixels and lines are produced, not how the page stores or displays the resulting bitmap.

Initial controls:

```text
??   R36   M1   M2   S1   S2   WX
MIC    USB
LISTEN / STOP
```

Image actions:

```text
CLEAR   SAVE   SHARE
```

The status line should remain compact and show the selected input, decoder state, detected mode when available, and errors that require user action.

## Initial Decoder Scope

### SSTV

- Automatic VIS detection for supported Robot 36, Martin M1, Martin M2, Scottie S1, and Scottie S2 modes.
- Compact manual mode controls (`R36`, `M1`, `M2`, `S1`, `S2`, and later `FX1`, `FX2`).
- While LISTEN remains active, selecting a manual mode replaces only the active decoder and starts from the next live PCM block; no replay is performed.
- Render progressively into the shared image surface.
- Show the original reconstructed color frame in the Light theme; retain theme-aware monochrome rendering in Dark and Hue themes.
- Allow completed or partial images with at least one decoded line to be saved in original decoded color.
- Protect a completed or useful partial image across mode changes, LISTEN restarts, and AUTO rearming; replace it only when the newly armed decoder publishes its first image line or when CLEAR is pressed.
- Preserve the detected mode in image metadata when saving.

### WEFAX

- Manual `WX` selection for the first implementation.
- Initial mode: IOC 576 at 120 lines per minute.
- Continuous progressive image area suitable for long transmissions.
- Acquire horizontal line phase and the received line-clock interval from repeated 120 LPM phasing edges when present, with an active-audio-only manual late-entry fallback.
- Treat STOP as the end of the current manual fax and keep partial SAVE available after the first decoded line.
- Later work may add 60/90 LPM, start/stop tone recognition, and manual skew correction.

## Audio Inputs

### Microphone

- Use Android `AudioRecord`.
- Acoustic coupling from the ATS Mini speaker is the minimum supported path.
- Recording starts only after an explicit LISTEN action and stops when leaving the page or pressing STOP.

### USB Audio

- Enumerate Android audio input devices.
- Allow selection of a connected USB sound dongle.
- Request the selected device through the Android audio-routing API and display the device Android actually uses.

Do not make USB audio a prerequisite for the first working SSTV slice.

## Architecture

Do not transplant Robot36's Activity or View hierarchy. Import or adapt only the decoder/DSP engine into an isolated package, retaining all required copyright and license notices.

Suggested structure:

```text
app/src/main/java/com/n5nbd/mempuck/atsmini/img/
    audio/
    decoder/
    model/
    repository/
    ui/
```

Primary components:

- `ImageAudioSource`: microphone and USB `AudioRecord` acquisition.
- `ImageDecoderRepository`: owns audio capture, decoder lifecycle, and bitmap updates.
- `ImageDecoderState`: immutable UI state exposed to Compose.
- `ImageScreen`: shared Compose page for SSTV and WEFAX.
- Decoder package adapted from Robot36 under its permissive license, with attribution preserved.

Keep this subsystem independent from the BLE radio repository. Image decoding must not interfere with tuning, scanning, memories, NOW, or source storage.

## Image Handling

- Maintain a mutable working pixel buffer inside the decoder layer.
- Publish throttled bitmap snapshots to Compose rather than recomposing for every sample or pixel.
- Save completed or partial images through Android MediaStore as PNG.
- Share through a content URI, not a raw filesystem path.
- Preserve source mode, timestamp, and tuned frequency in metadata or the generated filename when practical.

## Development Slices

1. Add the `IMG` tab, empty image surface, decoder state model, and microphone permission flow.
2. Import the Robot36 decoder engine with license and attribution documentation.
3. Decode SSTV automatically from the phone microphone and render progressively.
4. Add manual IOC 576 / 120 LPM WEFAX into the same image surface. **Implemented in dev.33, phase-acquired in dev.33r1, and robustly clock-corrected in dev.33r2.**
5. Add USB audio device selection and routing.
6. Add CLEAR, SAVE, and SHARE.
7. Add lifecycle hardening, diagnostics, and hardware acceptance tests.

Each slice should build and run on the Pixel 6 before proceeding.

## Constraints

- Develop only on `mempuck/img-decoder-v036`; do not modify the published v0.35.0 tag.
- Preserve the current portrait-first interface and maximum content width behavior.
- Avoid hosted services and network dependencies.
- Do not require modified ATS Mini firmware.
- Do not record audio in the background without an active user-initiated decoder session.
- Avoid a second image page or separate SSTV/WEFAX navigation.
- Keep decoder status and controls short enough for the existing phone-width layout.

## First Hardware Acceptance Target

On the Pixel 6 with the ATS Mini speaker audible:

1. Open `IMG`.
2. Select `AUTO` and `MIC`.
3. Press LISTEN.
4. Feed a known supported SSTV transmission.
5. Confirm automatic mode detection and progressive image rendering in Robot 36, Martin M1, Martin M2, Scottie S1, or Scottie S2.
6. Stop capture, save the image, and open the saved PNG outside MemPuck.

Manual IOC 576 / 120 LPM WEFAX is now present in dev.33. USB input remains a later slice.
