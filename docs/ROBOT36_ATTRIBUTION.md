# Robot36 decoder attribution

The MemPuck for ATS Mini IMG decoder uses and adapts permissively licensed signal-processing and SSTV decoder code from:

- Project: Robot36
- Author: Ahmet Inan
- Upstream: https://github.com/xdsopl/robot36
- Upstream release: v2.16
- Pinned revision: `75146a5342bf27a165f8790bcb33b56a6d96a2f8`
- License: 0BSD; the complete notice is retained at `app/src/main/assets/licenses/robot36-0BSD.txt`.

## Slice 02 import boundary

This hardware-test slice imports or adapts only the minimum DSP and decoder pieces needed to recognize a valid Robot 36 VIS header and progressively decode Robot 36 color scan lines from 44.1 kHz mono PCM:

- `ColorConverter`
- `Complex`
- `ComplexConvolution`
- `Delay`
- `Demodulator`
- `ExponentialMovingAverage`
- `Filter`
- `FrequencyModulation`
- `Kaiser`
- `Phasor`
- `PixelBuffer`
- `SchmittTrigger`
- `SimpleMovingAverage`
- `SimpleMovingSum`
- Robot 36 mode timing/color conversion adapted from `Robot_36_Color`
- VIS/header, synchronization, and progressive scan-line flow adapted from `Decoder`

The Java package was renamed for MemPuck. The broad Robot36 decoder was deliberately reduced to Robot 36 for the first hardware slice, and callbacks were added for a Compose-native repository/state layer. The original Robot36 `Activity`, Android `View`, rendering UI, preferences, and application structure were not copied.

Later SSTV modes and HF Fax/WEFAX work must retain this notice and document any additional imported source files.

## Martin mode additions

Later IMG decoder slices also adapt the upstream Martin RGB timing and channel-order model while retaining the same 0BSD notice:

- Martin M1: 320 × 256, VIS 44.
- Martin M2: 320 × 256, VIS 40.

MemPuck keeps separate live decoder classes for the hardware-test slices so the proven Robot 36 and Martin M1 implementations remain unchanged while each additional mode is validated independently.
