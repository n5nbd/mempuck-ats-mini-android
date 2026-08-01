# Dual-protocol ATS Mini field-test checklist

This checklist records acceptance of v035 against both supported tuning paths before the formal field-test tag.

## Build and install

```bash
cd /shared/repos/mempuck-ats-mini-android
./run_pixel6.sh
```

Confirm the ABOUT panel reports `0.35.0`. Confirm the header reads `MemPuck / Memory Manager` and `ATS Mini` with one compact receiver-state line.

Configure each receiver before testing:

```text
Settings -> Bluetooth -> Ad hoc
Sleep mode -> Unlocked
```

## MemPuck Z firmware

1. Connect or allow remembered-radio auto-connect.
2. Confirm the header shows `ONLINE:FAST` and CFG reports `Z PROTOCOL V1`.
3. Tune 7.100 MHz LSB, 14.230123 MHz USB, 1.000 MHz AM, and 101.700 MHz FM.
4. Tune a logical CW memory and confirm MemPuck continues to display CW while the receiver reports USB.
5. Exercise VFO stepping, latched VFO scanning, MEM recall, and MEM scanning.
6. Set dwell to 1 second and confirm each new frequency remains audible for the
   full second after the tune confirmation.
7. Disconnect and confirm the header changes to `OFFLINE:FAST`, then restart the app and confirm targeted auto-connect still recognizes the saved receiver.

## Official stock firmware v2.34 or newer

1. Begin on an ordinary named shortwave or amateur band rather than `ALL`.
2. Connect or allow remembered-radio auto-connect.
3. Allow approximately three seconds for the unanswered `Z?` probe to fall back.
4. Confirm the header shows `ONLINE:STOCK` and CFG reports `STOCK B/M/F` with the detected firmware version.
5. Tune 7.100 MHz LSB. Confirm the receiver reaches `ALL`, then LSB, then the exact frequency.
6. Tune 14.230123 MHz USB and verify the SSB BFO digits are preserved.
7. Tune 1.000 MHz AM and verify mode and frequency.
8. Tune 101.700 MHz FM and verify the receiver reaches `VHF` and FM.
9. Tune back to 7.030 MHz logical CW and confirm MemPuck displays CW while the receiver uses USB.
10. Exercise VFO stepping, latched VFO scanning, MEM recall, and MEM scanning.
11. Set dwell to 1 second and scan across AM and USB entries. Confirm the full
    one-second listening window begins only after USB is actually restored.
12. Disconnect and confirm the header changes to `OFFLINE:STOCK`, then restart the app and confirm the stock receiver is remembered and auto-connects.
13. Open DEBUG and confirm the transaction log shows verified `B`/`b`, `M`/`m`, and `F...` commands without repeated double-bumps.

## Source-page presentation

1. Open SRC and confirm the selected directory is presented as a path, such as `/MemPuck`.
2. Confirm the old unexplained prose above the path is absent.

## Failure cases

- Firmware older than v2.34 should remain connected but report that the stock firmware lacks `F` support when selected manually.
- With the status monitor unavailable, capability detection should fail cleanly rather than attempting blind relative commands.
- Disconnecting during a tune should cancel the transaction and return the app to a disconnected state.
- An out-of-range frequency or the 30–64 MHz gap should be rejected before any radio command is sent.

## Acceptance checkpoint

Accepted on 2026-08-01 with a Google Pixel 6 and ATS Mini V4. Both the official stock-firmware path and the MemPuck `Z`/FAST path passed frequency, mode, scanning, one-second confirmed dwell, header-state, source-page, and fresh-launch auto-connect checks.
