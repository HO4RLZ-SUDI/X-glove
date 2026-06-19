# ESP32-C3 ↔ ESP32-C3 communication — every method

Three fallback sketches to get two ESP32-C3 SuperMini boards talking, and to
pinpoint *why* if they don't. Flash the **same** sketch to **both** boards.

On-board LED (GPIO8, active-low) blinks on every packet **received** — so you
can confirm RX without a laptop.

| Sketch | Radio path | Use it to… |
| --- | --- | --- |
| `esp32_espnow_diag` | ESP-NOW broadcast, fixed ch 1 | Normal test + isolate a dead board (TX/RX role switch) |
| `esp32_espnow_scan` | ESP-NOW, hops ch 1–13 | Find the peer's channel; prove RX hardware works |
| `esp32_wifi_link`   | Plain Wi-Fi UDP (SoftAP+STA) | Total fallback when ESP-NOW won't cooperate |

## Decision flow

```
1. Flash esp32_espnow_diag to BOTH boards, power BOTH, open both monitors.
   - Both show "<<< RECV" + LED blinks  -> DONE. Link works.
   - One side never shows RECV          -> go to step 2.

2. Flash esp32_espnow_diag (ROLE_TX) to the "good" board,
   esp32_espnow_scan to the suspect.
   - Scanner prints "HEARD peer on channel N"
        -> channel mismatch was the issue; RX hardware is fine.
   - Scanner shows "NONE" every sweep, other board powered
        -> suspect board's RX is dead. Swap the board.

3. Still nothing? Flash esp32_wifi_link to both.
   - Works  -> radios are fine; the ESP-NOW issue is config; revisit step 1.
   - Fails too -> wiring/power/antenna or a genuinely bad board.
```

## Commands

```bash
# Flash one board (repeat after plugging in the other):
./up.sh --sketch firmware/esp32_espnow_diag
./up.sh --sketch firmware/esp32_espnow_scan
./up.sh --sketch firmware/esp32_wifi_link

# Watch a board (find the port with: arduino-cli board list):
./up.sh --monitor-only --port /dev/ttyACM0
./up.sh --monitor-only --port /dev/ttyACM1
```

## Notes / gotchas

- **`send -> OK` proves nothing** in broadcast mode — no ACK. Only `<<< RECV`
  (or the LED blink) confirms the link.
- **Both boards must be powered at the same time** for any test.
- **Role switch** in `esp32_espnow_diag`: edit `#define ROLE ...` to
  `ROLE_TX` / `ROLE_RX` to test one direction at a time.
- **MACs**: `esp32_wifi_link` picks AP/STA by MAC (BOARD_A→AP, BOARD_B→STA).
  Re-read MACs with `firmware/esp32_espnow_mac` if the defaults aren't yours.
- Cheap ESP32-C3 boards that **transmit fine but never receive** are a known,
  common hardware fault — step 2 is the definitive check for it.
```
