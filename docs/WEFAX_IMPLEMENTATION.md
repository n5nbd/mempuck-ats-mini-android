# WEFAX IOC 576 / 120 LPM Implementation

The dev.33r2 decoder is an original MemPuck implementation of the WMO analogue
radiofacsimile parameters. No third-party WEFAX decoder source was imported.

Initial fixed mode:

- IOC 576
- 120 lines per minute (one nominal line every 500 ms)
- 1809 display pixels per complete scanning line
- 1900 Hz center frequency
- 1500 Hz black
- 2300 Hz white

The decoder quadrature-demodulates the audio around 1900 Hz, smooths the
instantaneous frequency, maps 1500–2300 Hz into 8-bit grayscale, and resamples
each scan line into 1809 pixels.

## Phase and clock acquisition

Acquisition remains idle through silence, room noise, and the out-of-band IOC
selection signal. The late-entry timeout starts only after sustained audio is
present in the WEFAX picture-tone band.

For a normal start, the decoder recognizes repeated 120-LPM phasing cycles. It
requires a sustained black interval, a stable leading white edge, and five
consecutive half-second intervals before locking. In accordance with WMO-No.
386 section 5.2.3.4, the leading white edge is treated as entry into the dead
sector and therefore as the line origin.

Every accepted phasing edge is retained and fitted as one linearly spaced
sequence. The selected samples-per-line value is the median pairwise slope of
that sequence, with a median intercept and residual, rather than a simple
first/last interval. This makes the result resistant to individual noisy edge
positions at either end of the train. MemPuck continues refining the robust fit
while phasing remains present, then free-runs with the calibrated line clock
through the image. This compensates for small differences between the
transmitter audio clock and Android microphone clock that otherwise appear as
cumulative diagonal skew.

If reception begins after phasing, four seconds of sustained fax-band audio
selects nominal 120-LPM timing as a manual late-entry fallback. Silence before a
transmission does not consume this timeout. After enough image rows exist, the
late-entry path compares horizontal displacement in averaged vertical-edge
profiles. It requires three agreeing estimates, strong correlation, a distinct
correlation peak, and at least three separate vertical references. A successful
estimate updates samples per line once and circularly shears the already
received rows around the newest row, preserving the arbitrary late-entry line
origin while removing accumulated slant. Featureless or ambiguous images remain
at nominal timing rather than being guessed at.

Diagnostics include the acquisition source, calibrated samples per line,
estimated clock error in parts per million, robust-fit residual, late-entry
clock candidates and rejections, and any accepted mid-image correction.

## Progressive image storage

The pixel store grows from 256 lines to a maximum of 4096 lines. Normal receive
updates are grow-only. The sole decoder-side revision of completed rows is the
one-time, high-confidence late-entry clock correction described above; it uses
circular horizontal row shifts and retains every pixel. UI publication occurs
after the first line and then every four lines; STOP publishes the final retained
rows and marks the manual capture complete.

Reference specification:

- WMO-No. 386, Manual on the Global Telecommunication System, Part III,
  sections 5.1–5.5.
- NOAA/NWS mirror: https://www.weather.gov/media/marine/WMO_386_Vol_I_2009_en.pdf
