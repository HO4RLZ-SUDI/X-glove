# Flex Sensor Test (5 channels)

Sanity check for the glove's five flex sensors before running the main BLE
firmware. Reads GPIO0..4 (ESP32-C3 ADC1 — the same pins `esp32_glove_ble` uses),
prints raw counts / millivolts / a min-max range tracker / delta-from-baseline to
the serial monitor, and optionally draws live bar graphs on the SH1106 OLED.

## Wiring

Each finger is a resistive divider (one fixed resistor per channel):

```
3V3 --- [flex sensor] ---+--- ADC pin (GPIO0..4)
                         |
                       [R_fixed ~22k-47k] --- GND
```

Bending the sensor raises its resistance, pulling the ADC node down. Pick a
fixed resistor near the sensor's flat resistance for the widest swing.

| Finger | ADC pin | |
|--------|---------|---|
| F1 thumb  | GPIO0 | ADC1_0 |
| F2 index  | GPIO1 | ADC1_1 |
| F3 middle | GPIO2 | ADC1_2 ⚠️ strapping |
| F4 ring   | GPIO3 | ADC1_3 |
| F5 pinky  | GPIO4 | ADC1_4 |
| OLED SDA  | GPIO5 | I2C 0x3C |
| OLED SCL  | GPIO6 | |

> **GPIO2 is a C3 strapping pin.** Keep its divider node HIGH at rest (sensor on
> the high side, as drawn) so a bent finger at power-on can't force the board into
> download mode.

## Build & upload (ESP32-C3 SuperMini)

```bash
arduino-cli compile --fqbn esp32:esp32:esp32c3 firmware/tests/flex_sensor_test
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32c3 firmware/tests/flex_sensor_test
arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200
```

Or use the repo helper: `./up.sh --list` then pick this sketch.

## Usage

On boot it captures a flat-hand baseline (hold the hand straight), then shows
each finger as a live bar + `delta` on the OLED and over serial:

- **Flat hand** → all deltas sit near `0`.
- **Bend a finger** → its bar grows and `delta` moves.
- **Release** → returns to ~`0` (the original baseline).
- A momentary disconnect (raw dropping toward 0) is rejected as a glitch: the
  value is held and marked `X` instead of snapping down.
- Press **BOOT** (GPIO9) with the hand flat any time to re-capture the baseline.

Tunables near the top of the sketch: `EMA_ALPHA` (smoothing), `GLITCH_FLOOR`
(disconnect threshold), `BEND_FULL_SCALE` (bar sensitivity).

## OLED

Needs the `U8g2` library (`arduino-cli lib install U8g2`). The OLED is on by
default (SDA=GPIO5, SCL=GPIO6, address 0x3C). If the display isn't found the
sketch keeps running on serial only. If your panel is a true SSD1306, swap the
`U8G2_SH1106_...` constructor for `U8G2_SSD1306_...`.
