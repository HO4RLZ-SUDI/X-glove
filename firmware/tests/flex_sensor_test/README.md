# Flex Sensor Gesture Test (5 channels)

Reads the glove's five flex sensors on GPIO0..4 (ESP32-C3 ADC1 — the same pins
`esp32_glove_ble` uses), decides per finger whether it is bent, and turns the five
bend states into a 5-bit pattern. Each pattern maps to one Thai phrase from the
gesture chart: hold a finger combo and the firmware prints `SAY:<phrase>` so the
host bridge (`greet_bridge.py`) speaks it. It also prints per-finger
delta-from-baseline to the serial monitor and draws live bar graphs on the SH1106
OLED.

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

On boot it captures a flat-hand baseline (hold the hand straight), then watches
for gestures:

- **Flat hand** → all deltas sit near `0`, no finger bent.
- **Bend a finger past the ON threshold** → it counts as bent (shown as `*` on
  the OLED); its bar also grows by `delta`.
- **Hold a finger combo steady** (~300 ms) → if it matches a gesture the firmware
  prints `SAY:<thai phrase>` on serial and the OLED header shows the English
  label. It fires once; relax to flat (or form a different combo) to fire again.
- **Release** → fingers return to ~`0` and the gesture re-arms.
- A momentary disconnect (raw dropping toward 0) is rejected as a glitch: the
  value is held and marked `X`, and the finger keeps its last bend state.
- Press **BOOT** (GPIO9) with the hand flat any time to re-capture the baseline.

## Gestures

Finger codes: **1**=thumb (โป้ง), **2**=index (ชี้), **3**=middle (กลาง),
**4**=ring (นาง), **5**=pinky (ก้อย). Bend (งอ) the listed fingers and hold.
Type `g` in the serial monitor for this list at runtime.

### หมวดการใช้ชีวิตประจำวัน — daily life

| Fingers | Phrase | English |
|---|---|---|
| 3 4   | หิวข้าว | hungry |
| 1 3 4 | ดื่มน้ำ | drink water |
| 1 2 3 5 | เข้าห้องน้ำ | bathroom |
| 2     | ง่วงนอน | sleepy |
| 1 5   | หนาว | cold |
| 5     | ร้อน | hot |
| 2 3   | อาบน้ำ | shower |
| 2 3 4 | กลับบ้าน | go home |
| 1     | ไม่สบาย | sick |
| 2 5   | ทำความสะอาด | clean up |

### หมวดการสื่อสารทั่วไป — communication

| Fingers | Phrase | English |
|---|---|---|
| 1 4 5 | สวัสดี | hello |
| 1 2 3 4 | ขอโทษ | sorry |
| 2 3 4 5 | ขอบคุณ | thank you |
| 3 4 5 | ใช่ | yes |
| 1 3 4 5 | ไม่ใช่ | no |
| 4     | รอสักครู่ | wait |
| 3 5   | เข้าใจ | understand ⟳ |
| 1 3   | ไม่เข้าใจ | don't understand |
| 2 3 5 | ลาก่อน | goodbye ⟳ |
| 1 2 3 | ไม่เป็นไร | it's ok |

### หมวดขอความช่วยเหลือ — asking for help

| Fingers | Phrase | English |
|---|---|---|
| 1 2 3 4 5 | ช่วยด้วย | help |
| 2 4   | กลัว | scared |
| 4 5   | หายใจไม่ออก | can't breathe |
| 1 4   | ปวดหัว | headache |
| 1 2   | ปวดท้อง | stomach ache |
| 1 2 4 | เวียนหัว | dizzy ⟳ |
| 1 3 5 | หลงทาง | lost |
| 2 4 5 | ต้องการพัก | need rest |
| 1 2 4 5 | เจ็บ | hurt ⟳ |
| 1 2 5 | ต้องการน้ำ | need water ⟳ |

> **⟳ Reassigned from the printed chart.** With binary bend-detection a glove
> can't tell two phrases apart if they use the same fingers, and it can't detect
> "spread all 5 fingers" (it reads the same as a flat hand). Five phrases were
> moved to free finger combos so all 30 work — update the printed chart to match:
> เข้าใจ (was = ใช่), เวียนหัว (was = ปวดหัว), เจ็บ (was = ต้องการพัก),
> ต้องการน้ำ (was = ไม่เป็นไร), and ลาก่อน (was "กางทั้ง 5 นิ้ว").

## Serial calibration

Type a command + Enter in the serial monitor (115200 baud) to calibrate live —
no re-flash needed. Settings persist until reset.

| Command | Action |
|---------|--------|
| `c` / `cal` | re-capture the flat-hand baseline (same as BOOT) |
| `a <0.01..1.0>` | set EMA alpha — **lower = steadier**, higher = snappier |
| `s <1..64>` | samples averaged per read — **more = steadier**, slower |
| `m <0\|1>` | median filter off/on — `1` gives the best spike rejection |
| `n <50..1400>` | bend-**ON** delta — a finger counts as bent above this |
| `f <30..1400>` | bend-**OFF** delta — a finger counts as straight below this |
| `g` / `gestures` | print the full gesture table |
| `?` / `show` | print current settings |
| `h` / `help` | list commands |

**For the steadiest values:** keep `m 1` (median, default on), raise samples
(`s 24`), and lower alpha (`a 0.15`). If readings feel laggy, nudge alpha back up.

**Tuning bend detection:** watch the per-finger `delta` while bending. Set `n`
(bend-ON) comfortably below a full bend and `f` (bend-OFF) above the resting
noise; the gap between them is hysteresis that stops a finger from chattering.
Defaults are `n 320` / `f 180`.

Static tunables near the top of the sketch: defaults for `emaAlpha`,
`samplesPerRead`, `medianFilter`, `bendOnDelta`, `bendOffDelta`, plus
`GLITCH_FLOOR` (disconnect threshold) and `BEND_FULL_SCALE` (bar sensitivity).

## OLED

Needs the `U8g2` library (`arduino-cli lib install U8g2`). The OLED is on by
default (SDA=GPIO5, SCL=GPIO6, address 0x3C). If the display isn't found the
sketch keeps running on serial only. If your panel is a true SSD1306, swap the
`U8G2_SH1106_...` constructor for `U8G2_SSD1306_...`.
