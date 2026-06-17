# ESP32 OLED Network Status

Default wiring for a common ESP32 DevKit:

- OLED VCC -> 3V3
- OLED GND -> GND
- OLED SDA -> GPIO 21
- OLED SCL -> GPIO 22

Install libraries:

```bash
arduino-cli lib install U8g2
```

Compile for a generic ESP32 Dev Module from the repo root:

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/tests/oled_i2c_test
```

Upload:

```bash
arduino-cli upload -p /dev/ttyACM0 --fqbn esp32:esp32:esp32 firmware/tests/oled_i2c_test
```

Open serial monitor:

```bash
arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200
```

If the OLED is 128x32, change `SCREEN_HEIGHT` in `oled_i2c_test.ino` from `64` to `32`.
If your board uses different I2C pins, change `I2C_SDA` and `I2C_SCL`.

Set WiFi credentials near the top of `oled_i2c_test.ino`:

```cpp
#define WIFI_SSID "your-wifi-name"
#define WIFI_PASSWORD "your-wifi-password"
```

If `WIFI_SSID` is empty, the screen shows WiFi scan status instead of connecting.

This sketch currently uses the `SH1106 128x64` U8g2 driver because many 1.3" blue I2C OLED modules use SH1106 even when sold as "SSD1306".

If your display is definitely SSD1306, replace this line:

```cpp
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
```

with:

```cpp
U8G2_SSD1306_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
```
