# ESP32 BLE Glove Firmware (v2.1.0)

Arduino sketch for the Android app in this repo.

## BLE contract

- Service UUID: `0000FFE0-0000-1000-8000-00805F9B34FB`
- Characteristic UUID: `0000FFE1-0000-1000-8000-00805F9B34FB`
- Properties: Read, Write, Notify
- Negotiated MTU: 185 (the app requests the same) so a full packet fits one
  notification.
- Notify payload: `DATA:f1=...,f5=...,ax=...,ay=...,az=...,gx=...,gy=...,gz=...,b=...,n=...`
  - `f1..f5` = flex channels (always present, sent first)
  - `ax,ay,az` / `gx,gy,gz` = MPU6050 accel/gyro as **raw int16 counts**
    (default ranges: accel ±2 g = 16384 LSB/g, gyro ±250 °/s = 131 LSB/°/s).
    Omitted entirely when no IMU is present, so the app treats them as optional.
  - `b` = battery millivolts (omitted when battery monitoring is off)
  - `n` = packet sequence number
- Commands written to `FFE1`:
  - `CAL`: stores the current flex readings as baseline.
  - `CAL:CLEAR` (or `RESETCAL`): clears the stored baseline.
  - `START`: starts live notifications.
  - `STOP`: stops live notifications.
  - `RATE:<ms>`: sets the telemetry interval (20-1000 ms, persisted).
  - `STATUS`: replies `OK:STATUS:s=<stream>,r=<rate>,b=<mv>,u=<uptime s>,p=<packets>`
    (plus `,i=<imu present 0/1>` when the IMU is enabled and
    `,l=<oled brightness 0-100>` when the OLED is enabled).
  - `VER`: replies `OK:VER:<firmware version>`.
  - `PING`: replies `OK:PONG`.
  - `OLED:DASH`, `OLED:TELEM`, `OLED:SYS`, `OLED:HAND`, `OLED:WAVE`: switches
    the OLED display page.
  - `OLED:NEXT`: cycles to the next OLED display page.
  - `BRIGHT:<0-100>`: sets the OLED brightness/contrast (persisted).
  - `NAME:<name>` (up to 20 chars): saves the BLE device name. This is the only
    way to rename the glove — any other unrecognized write replies
    `ERR:UNKNOWN_COMMAND` (a typo'd command can no longer silently rename it).

Commands received over BLE are queued and executed from `loop()`, so slow work
(calibration animation, NVS writes) never blocks the BLE stack thread. If a
command arrives while another is still queued, the firmware replies `ERR:BUSY`.

## Signal quality

- Flex readings are smoothed with an exponential moving average
  (`FLEX_EMA_ALPHA`, default 0.45) on top of 8-sample averaging.
- Battery voltage uses `analogReadMilliVolts` (factory ADC calibration) and is
  cached for 2 seconds to keep the telemetry loop fast.

The app updates the connected name immediately after `OK:<name>`. The ESP32
advertised name uses the saved value after the board restarts.

## Default Pins (ESP32-C3 SuperMini)

- Flex sensors: GPIO `0`, `1`, `2`, `3`, `4` (ADC1 — the only ADC channels on C3)
- Battery ADC: disabled (`-1`); GPIO0..4 are all used by the flex sensors
- Shared I2C bus (OLED + MPU6050): SDA GPIO `5`, SCL GPIO `6`

Edit these constants at the top of `esp32_glove_ble.ino` to match your wiring:

```cpp
static const int FLEX_PINS[] = {0, 1, 2, 3, 4};
static const int BATTERY_PIN = -1;
static const float BATTERY_DIVIDER_RATIO = 2.0f;
static const int I2C_SDA_PIN = 5;
static const int I2C_SCL_PIN = 6;
```

Set `BATTERY_PIN` to a free ADC pin to enable battery monitoring.

> **GPIO2 warning:** GPIO2 is a boot strapping pin on the ESP32-C3 and must not
> be held LOW at power-on, or the board may fail to boot / enter download mode.
> Wire that flex channel so the pin rests HIGH at boot. Fully removing the risk
> means dropping to 4 flex channels or moving the I2C bus to free a
> non-strapping ADC pin — both are wiring decisions, not firmware ones.

## IMU (MPU6050)

An MPU6050 accel/gyro shares the I2C bus with the OLED (same SDA/SCL). The
firmware auto-detects it at `0x68`/`0x69`, wakes it, and streams raw accel/gyro
counts in every `DATA:` packet. It is enabled by default; disable with:

```cpp
#define GLOVE_ENABLE_IMU 0
```

No extra library is needed — the sketch talks to the MPU6050 over `Wire`
directly. If the IMU is absent or fails to init, BLE keeps running and the IMU
fields are simply omitted from the packet. On long/thin glove wiring, if IMU
reads are flaky, lower `I2C_CLOCK_HZ` (the MPU6050 tops out at 400 kHz).

## OLED display

The firmware supports a 128x64 I2C DST-013 / SH1106 OLED display. It shows a
short boot animation, then one of five app-selectable pages:

- `Dashboard`: saved BLE name, BLE icon, streaming icon, battery icon, and flex
  sensor bar graphs.
- `Telemetry`: large flex spectrum bars and battery percentage.
- `System`: device name, BLE state, streaming state + rate, packet count,
  uptime, battery voltage, and firmware version.
- `Hand`: a hand drawing whose fingers shorten as each flex sensor bends.
- `Waveform`: scrolling history graph for all five flex channels.

The display refreshes at 250 ms while streaming to a connected app and 500 ms
otherwise. When the battery drops below 15%, a blinking `LOW BATT` banner is
drawn over every page. After 25 s without a connection the screensaver starts.

Install these Arduino libraries before compiling with OLED enabled:

- `U8g2`

OLED support is enabled by default. To disable it, change this macro near the
top of `esp32_glove_ble.ino`:

```cpp
#define GLOVE_ENABLE_OLED 0
```

The OLED shares the I2C pins above (`I2C_SDA_PIN` / `I2C_SCL_PIN`). If your
display uses a different address, edit this constant:

```cpp
static const uint8_t OLED_I2C_ADDRESS = 0x3C;
```

For a different controller or screen size, change the U8g2 driver class in the
sketch. The DST-013 is configured as `U8G2_SH1106_128X64_NONAME_F_HW_I2C`.

If no OLED is connected or initialization fails, the firmware continues running
BLE normally.

## Upload

The easiest way is the repo's `up.sh` helper (auto-detects the port, this
firmware is choice 1 / the Enter default):

```bash
./up.sh                 # pick firmware, compile, upload
./up.sh --monitor       # same, then open the serial monitor
./up.sh --monitor-only  # just watch serial output
```

In Arduino IDE, install/select an ESP32 board package, open
`esp32_glove_ble.ino`, choose your ESP32 board and port, then Upload.

With `arduino-cli`, the command usually looks like:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/esp32_glove_ble
arduino-cli upload -p /dev/ttyUSB0 --fqbn esp32:esp32:esp32 firmware/esp32_glove_ble
```
