# esp32_espnow_peer

Two-way [ESP-NOW](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c3/api-reference/network/esp_now.html)
link between two ESP32-C3 boards. No Wi-Fi router or IP setup needed — boards
talk directly using each other's MAC address.

## Steps

1. **Get each board's MAC.** Flash `../esp32_espnow_mac` to each board and read
   the STA MAC from the serial monitor (115200 baud). Note both, e.g.
   - Board A: `AA:BB:CC:11:22:33`
   - Board B: `AA:BB:CC:44:55:66`

2. **Set `BOARD_A` / `BOARD_B`.** In `esp32_espnow_peer.ino`, fill in both
   MACs. At boot each board reads its own MAC and picks the other as its peer,
   so you flash the *same* sketch to both — no per-board edits.

3. **Flash both boards** with this sketch and open both serial monitors. Each
   sends a counter packet every 2s and prints what it receives.

## OLED status display

If a board has an SH1106 128x64 OLED on I2C (SDA=5, SCL=6), it shows the link
status: `LINK UP` / `NO LINK`, peer's last two MAC bytes, RSSI, TX ok/total
counts, and the last received id/value. Boards without a display run headless.

## Troubleshooting `send -> FAIL`

ESP-NOW unicast reports OK only when the peer ACKs at the MAC layer, so:

- **Both boards must be powered and flashed.** A single board talking to a
  missing peer fails every send.
- **Same channel.** This sketch pins both radios to `WIFI_CHANNEL` (1) via
  `esp_wifi_set_channel`.
- **Modem sleep.** STA mode sleeps the radio by default, so the receiver may
  miss the packet and never ACK. The sketch disables it with
  `WiFi.setSleep(false)` / `esp_wifi_set_ps(WIFI_PS_NONE)`.
- **Range / power.** Place the boards close together while testing.

## Notes

- Board: select **ESP32C3 Dev Module** in Arduino IDE.
- The `struct_message` layout must be identical on both boards.
- Built for ESP32 Arduino core **v3.x** (v3.3+ callback signatures
  `wifi_tx_info_t` / `esp_now_recv_info_t`).
- For a secure link, set `peer.encrypt = true` and configure a PMK/LMK.
