// ============================================================================
// ESP32 BLE Glove Firmware
// 5x flex sensors + MPU6050 (accel/gyro) + battery monitor + SH1106 OLED,
// exposed over a single BLE characteristic for the Android app in this repo.
// ============================================================================

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>

#define FW_VERSION "2.1.0"

#ifndef GLOVE_ENABLE_OLED
#define GLOVE_ENABLE_OLED 1
#endif

#ifndef GLOVE_ENABLE_IMU
#define GLOVE_ENABLE_IMU 1
#endif

// The OLED and the MPU6050 share the same I2C bus, so Wire is needed whenever
// either one is enabled.
#if GLOVE_ENABLE_OLED || GLOVE_ENABLE_IMU
#include <Wire.h>
// ESP32-C3 SuperMini: GPIO5/6 are free (avoid 8/9 = LED/BOOT, 20/21 = serial).
static const int I2C_SDA_PIN = 5;
static const int I2C_SCL_PIN = 6;
static const uint32_t I2C_CLOCK_HZ = 400000;
#endif

#if GLOVE_ENABLE_OLED
#include <U8g2lib.h>
#endif

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// Android app contract.
static const char* SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb";
static const char* CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb";

// Change these to match your glove wiring.
// ESP32-C3 SuperMini: only GPIO0..4 expose ADC1, so the 5 flex sensors use them
// all. WARNING: GPIO2 is a boot strapping pin on the C3 and must NOT be held LOW
// at power-on, or the chip can drop into download mode / fail to boot. Wire the
// flex divider on this channel so the pin floats HIGH at rest (e.g. put the flex
// resistor on the high side), or accept that a fully-bent finger at boot may
// hang the device. Truly removing the risk means dropping to 4 channels or
// moving the I2C bus to free a non-strapping ADC pin -- both are wiring choices.
static const int FLEX_PINS[] = {0, 1, 2, 3, 4};
static const int FLEX_COUNT = sizeof(FLEX_PINS) / sizeof(FLEX_PINS[0]);
static const int BATTERY_PIN = -1; // No ADC channel left on C3 (0..4 used by flex). Set to a pin to re-enable.
static const float BATTERY_DIVIDER_RATIO = 2.0f; // 100k/100k divider = 2.0.

static const char* DEFAULT_DEVICE_NAME = "Glove-ESP32";
static const size_t MAX_DEVICE_NAME_CHARS = 20;

// Telemetry rate is runtime-adjustable via the RATE:<ms> command.
static const uint16_t TELEMETRY_DEFAULT_INTERVAL_MS = 100;
static const uint16_t TELEMETRY_MIN_INTERVAL_MS = 20;
static const uint16_t TELEMETRY_MAX_INTERVAL_MS = 1000;

// Exponential moving average weight for flex readings (0..1, higher = snappier).
static const float FLEX_EMA_ALPHA = 0.45f;
static const uint32_t BATTERY_READ_INTERVAL_MS = 2000;

#if GLOVE_ENABLE_IMU
// MPU6050 on the shared I2C bus (same SDA/SCL as the OLED). AD0 low = 0x68.
static const uint8_t MPU_ADDR_LOW = 0x68;
static const uint8_t MPU_ADDR_HIGH = 0x69;
static const uint8_t MPU_REG_PWR_MGMT_1 = 0x6B;
static const uint8_t MPU_REG_WHO_AM_I = 0x75;
static const uint8_t MPU_REG_ACCEL_XOUT_H = 0x3B;
#endif

#if GLOVE_ENABLE_OLED
static const uint8_t OLED_RESET_PIN = U8X8_PIN_NONE;
static const uint8_t OLED_I2C_ADDRESS = 0x3C;
static const uint32_t OLED_REFRESH_IDLE_MS = 500;
static const uint32_t OLED_REFRESH_STREAMING_MS = 250;
static const uint8_t OLED_BOOT_FRAMES = 34;
static const uint8_t OLED_BOOT_FRAME_DELAY_MS = 28;
static const int OLED_FLEX_DELTA_FULL_SCALE = 1400;
static const int OLED_BATTERY_MIN_MV = 3300;
static const int OLED_BATTERY_MAX_MV = 4200;
static const uint8_t OLED_LOW_BATTERY_PERCENT = 15;
static const uint32_t SCREENSAVER_TIMEOUT_MS = 25000;
static const uint8_t OLED_DEFAULT_BRIGHTNESS = 70; // 0..100, user-adjustable via BRIGHT:.
static const uint8_t CONNECT_ANIM_FRAMES = 18;
static const uint8_t WAVE_SAMPLES = 64;
static const uint8_t RAIN_COLS = 16;

enum OledPage : uint8_t {
    OLED_PAGE_DASHBOARD = 0,
    OLED_PAGE_TELEMETRY = 1,
    OLED_PAGE_SYSTEM = 2,
    OLED_PAGE_HAND = 3,
    OLED_PAGE_WAVE = 4,
    OLED_PAGE_COUNT = 5
};
#endif

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

Preferences prefs;
BLEServer* bleServer = nullptr;
BLECharacteristic* gloveCharacteristic = nullptr;

String deviceName;
bool deviceConnected = false;
bool oldDeviceConnected = false;
bool streaming = true;
uint16_t telemetryIntervalMs = TELEMETRY_DEFAULT_INTERVAL_MS;
uint32_t lastTelemetryMs = 0;
uint32_t telemetryPacketCount = 0;
uint16_t flexBaseline[FLEX_COUNT] = {0};

// Commands arrive on the BLE stack's thread; they are queued here and run from
// loop() so slow work (OLED animations, NVS writes) never blocks the stack.
String pendingCommand;
volatile bool commandPending = false;

// Advertising restart is scheduled instead of blocking loop() with delay().
uint32_t advertisingRestartAtMs = 0;

float flexEma[FLEX_COUNT] = {0};
bool flexEmaReady = false;

int cachedBatteryMv = -1;
uint32_t lastBatteryReadMs = 0;

struct TelemetrySnapshot {
    int flex[FLEX_COUNT] = {0};
    int batteryMv = -1;
#if GLOVE_ENABLE_IMU
    // Raw int16 counts straight from the MPU6050; the app scales them.
    int16_t accel[3] = {0, 0, 0};
    int16_t gyro[3] = {0, 0, 0};
    bool imuValid = false;
#endif
};

TelemetrySnapshot latestTelemetry;
bool latestTelemetryValid = false;

#if GLOVE_ENABLE_IMU
uint8_t mpuAddress = 0; // 0 = not detected
bool imuReady = false;
#endif

#if GLOVE_ENABLE_OLED
U8G2_SH1106_128X64_NONAME_F_HW_I2C oled(U8G2_R0, OLED_RESET_PIN, I2C_SCL_PIN, I2C_SDA_PIN);
bool oledReady = false;
uint8_t oledBrightness = OLED_DEFAULT_BRIGHTNESS; // 0..100
uint32_t lastOledRefreshMs = 0;
OledPage oledPage = OLED_PAGE_DASHBOARD;
uint32_t lastActivityMs = 0;
bool playConnectAnim = false;
uint8_t connectAnimFrame = 0;
static uint8_t waveBuffer[FLEX_COUNT][WAVE_SAMPLES];
static uint8_t waveHead = 0;
static int8_t rainDropY[RAIN_COLS];
static uint8_t rainDropLen[RAIN_COLS];
#endif

// ---------------------------------------------------------------------------
// BLE callbacks
// ---------------------------------------------------------------------------

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override {
        deviceConnected = true;
#if GLOVE_ENABLE_OLED
        lastActivityMs = millis();
        playConnectAnim = true;
        connectAnimFrame = 0;
#endif
    }

    void onDisconnect(BLEServer*) override {
        deviceConnected = false;
#if GLOVE_ENABLE_OLED
        lastActivityMs = millis();
#endif
    }
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static String trimCommand(String value) {
    value.trim();
    value.replace("\r", "");
    value.replace("\n", "");
    return value;
}

static uint16_t readAnalogAverage(int pin, int samples = 8) {
    uint32_t total = 0;
    for (int i = 0; i < samples; i++) {
        total += analogRead(pin);
        delayMicroseconds(250);
    }
    return total / samples;
}

// Uses the factory ADC calibration instead of assuming a linear 3.3V scale.
static int readBatteryMillivoltsNow() {
    if (BATTERY_PIN < 0) return -1;
    uint32_t total = 0;
    for (int i = 0; i < 8; i++) {
        total += analogReadMilliVolts(BATTERY_PIN);
        delayMicroseconds(250);
    }
    return static_cast<int>((total / 8) * BATTERY_DIVIDER_RATIO);
}

static int batteryMillivolts() {
    if (BATTERY_PIN < 0) return -1;
    uint32_t now = millis();
    if (cachedBatteryMv < 0 || now - lastBatteryReadMs >= BATTERY_READ_INTERVAL_MS) {
        cachedBatteryMv = readBatteryMillivoltsNow();
        lastBatteryReadMs = now;
    }
    return cachedBatteryMv;
}

#if GLOVE_ENABLE_IMU
static void mpuWriteReg(uint8_t reg, uint8_t value) {
    Wire.beginTransmission(mpuAddress);
    Wire.write(reg);
    Wire.write(value);
    Wire.endTransmission();
}

static uint8_t mpuReadReg(uint8_t reg) {
    Wire.beginTransmission(mpuAddress);
    Wire.write(reg);
    Wire.endTransmission(false);
    Wire.requestFrom(mpuAddress, static_cast<uint8_t>(1));
    return Wire.available() ? Wire.read() : 0;
}

// Reads accel XYZ, temp (discarded), gyro XYZ as big-endian int16.
static bool mpuReadMotion(int16_t accel[3], int16_t gyro[3]) {
    Wire.beginTransmission(mpuAddress);
    Wire.write(MPU_REG_ACCEL_XOUT_H);
    if (Wire.endTransmission(false) != 0) return false;
    if (Wire.requestFrom(mpuAddress, static_cast<uint8_t>(14)) != 14) return false;

    // Read high/low into explicit temporaries: the order of two Wire.read()
    // calls inside one expression is unspecified and could swap the bytes.
    for (int i = 0; i < 3; i++) {
        uint8_t hi = Wire.read();
        uint8_t lo = Wire.read();
        accel[i] = static_cast<int16_t>((hi << 8) | lo);
    }
    Wire.read();
    Wire.read(); // skip temperature
    for (int i = 0; i < 3; i++) {
        uint8_t hi = Wire.read();
        uint8_t lo = Wire.read();
        gyro[i] = static_cast<int16_t>((hi << 8) | lo);
    }
    return true;
}
#endif

static TelemetrySnapshot readTelemetrySnapshot() {
    TelemetrySnapshot snapshot;
    for (int i = 0; i < FLEX_COUNT; i++) {
        int raw = readAnalogAverage(FLEX_PINS[i]);
        if (!flexEmaReady) {
            flexEma[i] = raw;
        } else {
            flexEma[i] += FLEX_EMA_ALPHA * (raw - flexEma[i]);
        }
        int filtered = static_cast<int>(lroundf(flexEma[i]));
        snapshot.flex[i] = flexBaseline[i] == 0
            ? filtered
            : filtered - static_cast<int>(flexBaseline[i]);
    }
    flexEmaReady = true;
    snapshot.batteryMv = batteryMillivolts();
#if GLOVE_ENABLE_IMU
    if (imuReady) {
        snapshot.imuValid = mpuReadMotion(snapshot.accel, snapshot.gyro);
    }
#endif
    return snapshot;
}

static void notifyText(const String& text) {
    if (gloveCharacteristic == nullptr) return;
    gloveCharacteristic->setValue(text.c_str());
    if (deviceConnected) {
        gloveCharacteristic->notify();
    }
}

static void saveDeviceName(const String& nextName) {
    deviceName = nextName.substring(0, MAX_DEVICE_NAME_CHARS);
    prefs.putString("name", deviceName);
    notifyText("OK:" + deviceName);
}

static void calibrateFlexBaseline() {
#if GLOVE_ENABLE_OLED
    if (oledReady) {
        for (uint8_t f = 0; f < 24; f++) {
            oled.clearBuffer();
            oled.setFont(u8g2_font_7x14B_tf);
            oled.setCursor(16, 22);
            oled.print("CALIBR.");
            oled.setFont(u8g2_font_5x8_tf);
            oled.setCursor(18, 34);
            oled.print("Keep hand flat");
            uint8_t scanX = map(f, 0, 23, 0, 124);
            oled.drawVLine(scanX, 38, 12);
            oled.drawFrame(14, 52, 100, 6);
            oled.drawBox(16, 54, map(f, 0, 23, 0, 96), 2);
            oled.sendBuffer();
            delay(40);
        }
    }
#endif
    for (int i = 0; i < FLEX_COUNT; i++) {
        flexBaseline[i] = readAnalogAverage(FLEX_PINS[i], 24);
        flexEma[i] = flexBaseline[i];
        prefs.putUShort(("z" + String(i)).c_str(), flexBaseline[i]);
    }
    flexEmaReady = true;
    notifyText("OK:CAL");
}

static void clearFlexBaseline() {
    for (int i = 0; i < FLEX_COUNT; i++) {
        flexBaseline[i] = 0;
        prefs.putUShort(("z" + String(i)).c_str(), 0);
    }
    notifyText("OK:CAL:CLEAR");
}

static String makeTelemetryPacket(const TelemetrySnapshot& snapshot) {
    // Flex first so the most important fields survive even if the negotiated MTU
    // ends up smaller than expected; IMU/battery/counter trail behind.
    String packet = "DATA:";
    for (int i = 0; i < FLEX_COUNT; i++) {
        if (i > 0) packet += ",";
        packet += "f";
        packet += String(i + 1);
        packet += "=";
        packet += String(snapshot.flex[i]);
    }

#if GLOVE_ENABLE_IMU
    if (snapshot.imuValid) {
        static const char* axisKeys[3] = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            packet += ",a";
            packet += axisKeys[i];
            packet += "=";
            packet += String(snapshot.accel[i]);
        }
        for (int i = 0; i < 3; i++) {
            packet += ",g";
            packet += axisKeys[i];
            packet += "=";
            packet += String(snapshot.gyro[i]);
        }
    }
#endif

    if (snapshot.batteryMv >= 0) {
        packet += ",b=";
        packet += String(snapshot.batteryMv);
    }
    packet += ",n=";
    packet += String(telemetryPacketCount);
    return packet;
}

// ---------------------------------------------------------------------------
// OLED rendering
// ---------------------------------------------------------------------------

#if GLOVE_ENABLE_OLED
static int clampInt(int value, int minValue, int maxValue) {
    if (value < minValue) return minValue;
    if (value > maxValue) return maxValue;
    return value;
}

static uint8_t batteryPercent(int batteryMv) {
    if (batteryMv < 0) return 255;
    int clamped = clampInt(batteryMv, OLED_BATTERY_MIN_MV, OLED_BATTERY_MAX_MV);
    return map(clamped, OLED_BATTERY_MIN_MV, OLED_BATTERY_MAX_MV, 0, 100);
}

static uint8_t flexLevelPercent(uint8_t index, int value) {
    int fullScale = (index < FLEX_COUNT && flexBaseline[index] == 0)
        ? 4095
        : OLED_FLEX_DELTA_FULL_SCALE;
    int magnitude = abs(value);
    return map(clampInt(magnitude, 0, fullScale), 0, fullScale, 0, 100);
}

static OledPage sanitizeOledPage(uint8_t rawPage) {
    return rawPage < OLED_PAGE_COUNT ? static_cast<OledPage>(rawPage) : OLED_PAGE_DASHBOARD;
}

// Maps the 0..100 user brightness onto the SH1106 contrast register (0..255).
static void applyOledBrightness() {
    if (!oledReady) return;
    uint8_t contrast = map(clampInt(oledBrightness, 0, 100), 0, 100, 0, 255);
    oled.setContrast(contrast);
}

static const char* oledPageCode(OledPage page) {
    switch (page) {
        case OLED_PAGE_DASHBOARD: return "DASH";
        case OLED_PAGE_TELEMETRY: return "TELEM";
        case OLED_PAGE_SYSTEM:    return "SYS";
        case OLED_PAGE_HAND:      return "HAND";
        case OLED_PAGE_WAVE:      return "WAVE";
        default:                  return "DASH";
    }
}

static const char* oledPageTitle(OledPage page) {
    switch (page) {
        case OLED_PAGE_DASHBOARD: return "DASHBOARD";
        case OLED_PAGE_TELEMETRY: return "TELEMETRY";
        case OLED_PAGE_SYSTEM:    return "SYSTEM";
        case OLED_PAGE_HAND:      return "HAND";
        case OLED_PAGE_WAVE:      return "WAVEFORM";
        default:                  return "DASHBOARD";
    }
}

static void drawBatteryIcon(int16_t x, int16_t y, int batteryMv) {
    uint8_t percent = batteryPercent(batteryMv);
    if (percent != 255 && percent < 20 && (millis() / 400) % 2 == 0) return;
    oled.drawFrame(x, y, 18, 8);
    oled.drawBox(x + 18, y + 2, 2, 4);

    if (percent == 255) {
        oled.drawLine(x + 4, y + 2, x + 13, y + 5);
        oled.drawLine(x + 13, y + 2, x + 4, y + 5);
        return;
    }

    uint8_t fillWidth = map(percent, 0, 100, 0, 14);
    if (fillWidth > 0) {
        oled.drawBox(x + 2, y + 2, fillWidth, 4);
    }
}

static void drawBleIcon(int16_t x, int16_t y) {
    oled.drawDisc(x + 3, y + 4, 2);
    if (deviceConnected) {
        oled.drawCircle(x + 3, y + 4, 5);
        oled.drawCircle(x + 3, y + 4, 8);
    } else {
        uint8_t pulse = (millis() / OLED_REFRESH_IDLE_MS) % 3;
        for (uint8_t i = 0; i <= pulse; i++) {
            oled.drawCircle(x + 3, y + 4, 5 + i * 3);
        }
    }
}

static void drawStreamIcon(int16_t x, int16_t y) {
    if (streaming) {
        oled.drawTriangle(x, y, x, y + 8, x + 7, y + 4);
    } else {
        oled.drawFrame(x, y + 1, 7, 7);
    }
}

static void drawFlexMeter(uint8_t index, int16_t y, const TelemetrySnapshot& snapshot) {
    static const int16_t barX = 18;
    static const int16_t barW = 70;
    static const int16_t barH = 7;

    int value = index < FLEX_COUNT ? snapshot.flex[index] : 0;
    uint8_t level = index < FLEX_COUNT ? flexLevelPercent(index, value) : 0;
    uint8_t fillWidth = map(level, 0, 100, 0, barW - 2);

    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, y + 7);
    oled.print("F");
    oled.print(index + 1);

    oled.drawFrame(barX, y, barW, barH);
    if (fillWidth > 0) {
        oled.drawBox(barX + 1, y + 1, fillWidth, barH - 2);
    }

    if (fillWidth > 5) {
        uint8_t shimmer = (millis() / OLED_REFRESH_IDLE_MS + index) % 3;
        oled.setDrawColor(0);
        oled.drawVLine(barX + fillWidth - 1, y + 2, 1 + shimmer);
        oled.setDrawColor(1);
    }

    oled.setCursor(94, y + 7);
    if (index < FLEX_COUNT) {
        oled.print(value);
    } else {
        oled.print("-");
    }
}

static void drawDashboardHeader(const TelemetrySnapshot& snapshot) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, 8);
    oled.print(deviceName.substring(0, 11));

    drawBleIcon(73, 1);
    drawStreamIcon(91, 1);
    drawBatteryIcon(106, 1, snapshot.batteryMv);

    oled.drawHLine(0, 11, 128);
}

static void renderDashboardPage(const TelemetrySnapshot& snapshot) {
    drawDashboardHeader(snapshot);
    drawFlexMeter(0, 15, snapshot);
    drawFlexMeter(1, 24, snapshot);
    drawFlexMeter(2, 33, snapshot);
    drawFlexMeter(3, 42, snapshot);
    drawFlexMeter(4, 51, snapshot);
}

static void drawTelemetryColumn(uint8_t index, int16_t x, const TelemetrySnapshot& snapshot) {
    static const int16_t barTop = 18;
    static const int16_t barHeight = 32;
    static const int16_t barWidth = 13;

    int value = index < FLEX_COUNT ? snapshot.flex[index] : 0;
    uint8_t level = index < FLEX_COUNT ? flexLevelPercent(index, value) : 0;
    uint8_t fillHeight = map(level, 0, 100, 0, barHeight - 2);

    oled.drawFrame(x, barTop, barWidth, barHeight);
    if (fillHeight > 0) {
        oled.drawBox(x + 1, barTop + barHeight - 1 - fillHeight, barWidth - 2, fillHeight);
    }

    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(x + 1, 60);
    oled.print("F");
    oled.print(index + 1);
}

static void renderTelemetryPage(const TelemetrySnapshot& snapshot) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, 8);
    oled.print("FLEX SPECTRUM");

    uint8_t percent = batteryPercent(snapshot.batteryMv);
    oled.setCursor(92, 8);
    if (percent == 255) {
        oled.print("BAT --");
    } else {
        oled.print("BAT ");
        oled.print(percent);
        oled.print("%");
    }

    oled.drawHLine(0, 11, 128);

    for (uint8_t i = 0; i < 5; i++) {
        drawTelemetryColumn(i, 6 + i * 24, snapshot);
    }

    uint8_t spark = (millis() / OLED_REFRESH_IDLE_MS) % 5;
    oled.drawTriangle(9 + spark * 24, 14, 15 + spark * 24, 14, 12 + spark * 24, 17);
}

static void drawSystemLine(int16_t y, const char* label, const String& value) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, y);
    oled.print(label);
    oled.setCursor(48, y);
    oled.print(value.substring(0, 13));
}

static void renderSystemPage(const TelemetrySnapshot& snapshot) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, 8);
    oled.print("SYSTEM");
    oled.setCursor(92, 8);
    oled.print("v" FW_VERSION);
    oled.drawHLine(0, 11, 128);

    drawSystemLine(22, "Name", deviceName);
    drawSystemLine(32, "BLE", deviceConnected ? "Connected" : "Advertising");
    drawSystemLine(42, "Stream", streaming
        ? (String("On @") + String(telemetryIntervalMs) + "ms")
        : String("Off"));
    drawSystemLine(52, "Packets", String(telemetryPacketCount));

    oled.setCursor(0, 62);
    oled.print("Up ");
    oled.print(millis() / 1000);
    oled.print("s");
    oled.setCursor(72, 62);
    if (snapshot.batteryMv >= 0) {
        oled.print(snapshot.batteryMv);
        oled.print("mV");
    } else {
        oled.print("BAT n/a");
    }
}

static void initRain() {
    for (uint8_t i = 0; i < RAIN_COLS; i++) {
        rainDropY[i] = -(int8_t)((i * 7) % 40);
        rainDropLen[i] = 3 + (i * 3 + 1) % 6;
    }
}

static void renderScreensaver() {
    oled.clearBuffer();
    for (uint8_t i = 0; i < RAIN_COLS; i++) {
        rainDropY[i] += 2;
        if (rainDropY[i] > 64 + rainDropLen[i]) {
            rainDropY[i] = -(int8_t)rainDropLen[i];
        }
        int16_t x = i * 8 + 3;
        for (uint8_t j = 0; j < rainDropLen[i]; j++) {
            int16_t py = (int16_t)rainDropY[i] - j;
            if (py >= 0 && py < 64) {
                oled.drawPixel(x, py);
                if (j < 2) oled.drawPixel(x + 1, py);
            }
        }
    }
    oled.setFont(u8g2_font_5x8_tf);
    int16_t nameW = (int16_t)deviceName.length() * 5;
    oled.setCursor((128 - nameW) / 2, 36);
    oled.print(deviceName.substring(0, 13));
    oled.sendBuffer();
}

static void renderConnectFrame(uint8_t frame) {
    oled.clearBuffer();
    for (uint8_t ring = 0; ring < 4; ring++) {
        int16_t r = (int16_t)frame * 4 - ring * 10;
        if (r > 0 && r < 40) oled.drawCircle(64, 32, r);
    }
    oled.setFont(u8g2_font_7x14B_tf);
    oled.setCursor(27, 40);
    oled.print("LINKED");
    oled.sendBuffer();
}

static void renderHandPage(const TelemetrySnapshot& snapshot) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, 8);
    oled.print("HAND");
    drawBleIcon(73, 1);
    drawBatteryIcon(106, 1, snapshot.batteryMv);
    oled.drawHLine(0, 11, 128);

    static const int16_t palmX = 12;
    static const int16_t palmY = 50;
    static const int16_t palmW = 104;
    static const int16_t palmH = 12;
    static const int16_t fingerBottomY = 47;
    static const int16_t fingerMaxH = 32;
    static const int16_t fingerMinH = 4;
    static const int16_t fingerW = 10;
    static const int16_t fingerX[5] = {16, 36, 56, 76, 96};

    oled.drawRBox(palmX, palmY, palmW, palmH, 3);

    for (uint8_t i = 0; i < FLEX_COUNT; i++) {
        uint8_t level = flexLevelPercent(i, snapshot.flex[i]);
        int16_t h = (int16_t)map(level, 0, 100, fingerMaxH, fingerMinH);
        int16_t fy = fingerBottomY - h;
        oled.drawRBox(fingerX[i], fy, fingerW, h, 2);
        oled.setDrawColor(0);
        oled.drawHLine(fingerX[i] + 2, fy + 3, fingerW - 4);
        oled.setDrawColor(1);
    }
}

static void pushWaveSample(const TelemetrySnapshot& snapshot) {
    for (uint8_t i = 0; i < FLEX_COUNT; i++) {
        waveBuffer[i][waveHead] = (uint8_t)flexLevelPercent(i, snapshot.flex[i]);
    }
    waveHead = (waveHead + 1) % WAVE_SAMPLES;
}

static void renderWavePage(const TelemetrySnapshot& snapshot) {
    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(0, 8);
    oled.print("WAVEFORM");
    drawBatteryIcon(106, 1, snapshot.batteryMv);
    oled.drawHLine(0, 11, 128);

    static const int16_t chartX = 18;
    static const uint8_t chartH = 10;

    for (uint8_t ch = 0; ch < FLEX_COUNT; ch++) {
        int16_t baseY = 12 + ch * (chartH + 1);
        oled.setCursor(0, baseY + 8);
        oled.print(ch + 1);
        for (uint8_t x = 0; x < WAVE_SAMPLES; x++) {
            uint8_t idx = (waveHead + x) % WAVE_SAMPLES;
            uint8_t level = waveBuffer[ch][idx];
            uint8_t pixH = (uint8_t)map(level, 0, 100, 1, chartH - 1);
            oled.drawVLine(chartX + x, baseY + chartH - pixH, pixH);
        }
        uint8_t cur = waveBuffer[ch][(waveHead + WAVE_SAMPLES - 1) % WAVE_SAMPLES];
        oled.setCursor(86, baseY + 8);
        if (cur < 10) oled.print(" ");
        if (cur < 100) oled.print(" ");
        oled.print(cur);
    }
}

static void drawLowBatteryOverlay(const TelemetrySnapshot& snapshot) {
    uint8_t percent = batteryPercent(snapshot.batteryMv);
    if (percent == 255 || percent >= OLED_LOW_BATTERY_PERCENT) return;
    if ((millis() / 600) % 2 != 0) return;

    oled.setDrawColor(0);
    oled.drawBox(22, 23, 84, 18);
    oled.setDrawColor(1);
    oled.drawFrame(22, 23, 84, 18);
    oled.setFont(u8g2_font_7x14B_tf);
    oled.setCursor(30, 37);
    oled.print("LOW BATT");
}

static void renderOled(const TelemetrySnapshot& snapshot) {
    if (!oledReady) return;

    oled.clearBuffer();
    switch (oledPage) {
        case OLED_PAGE_TELEMETRY:
            renderTelemetryPage(snapshot);
            break;
        case OLED_PAGE_SYSTEM:
            renderSystemPage(snapshot);
            break;
        case OLED_PAGE_HAND:
            renderHandPage(snapshot);
            break;
        case OLED_PAGE_WAVE:
            renderWavePage(snapshot);
            break;
        case OLED_PAGE_DASHBOARD:
        default:
            renderDashboardPage(snapshot);
            break;
    }

    drawLowBatteryOverlay(snapshot);
    oled.sendBuffer();
}

static void drawBootFrame(uint8_t frame) {
    uint8_t progress = map(frame + 1, 0, OLED_BOOT_FRAMES, 0, 100);
    uint8_t scanX = (frame * 5) % 128;
    uint8_t pulse = frame % 10;

    oled.clearBuffer();

    oled.drawVLine(scanX, 0, 64);
    oled.drawVLine((scanX + 43) % 128, 8, 48);
    oled.drawVLine((scanX + 86) % 128, 16, 32);

    oled.setFont(u8g2_font_7x14B_tf);
    oled.setCursor(43, 15);
    oled.print("GLOVE");

    oled.drawCircle(64, 31, 7 + pulse / 2);
    oled.drawCircle(64, 31, 15 + pulse);
    oled.drawDisc(64, 31, 3);
    oled.drawLine(42, 31, 56, 31);
    oled.drawLine(72, 31, 86, 31);
    oled.drawLine(64, 14, 64, 24);
    oled.drawLine(64, 38, 64, 49);

    for (uint8_t i = 0; i < 5; i++) {
        uint8_t h = ((frame + i * 3) % 12) + 3;
        oled.drawBox(25 + i * 8, 49 - h, 4, h);
        oled.drawBox(86 + i * 5, 49 - ((h + 5) % 12), 3, ((h + 5) % 12) + 2);
    }

    oled.setFont(u8g2_font_5x8_tf);
    oled.setCursor(45, 53);
    oled.print("FLEX LINK");

    oled.drawFrame(14, 56, 100, 6);
    oled.drawBox(16, 58, map(progress, 0, 100, 0, 96), 2);

    oled.sendBuffer();
}

static void playBootAnimation() {
    if (!oledReady) return;
    for (uint8_t frame = 0; frame < OLED_BOOT_FRAMES; frame++) {
        drawBootFrame(frame);
        delay(OLED_BOOT_FRAME_DELAY_MS);
    }
}

static void updateOled(bool force = false) {
    if (!oledReady) return;
    uint32_t now = millis();

    if (playConnectAnim) {
        if (!force && now - lastOledRefreshMs < 50) return;
        lastOledRefreshMs = now;
        renderConnectFrame(connectAnimFrame++);
        if (connectAnimFrame >= CONNECT_ANIM_FRAMES) playConnectAnim = false;
        return;
    }

    uint32_t refreshInterval = (deviceConnected && streaming)
        ? OLED_REFRESH_STREAMING_MS
        : OLED_REFRESH_IDLE_MS;
    if (!force && now - lastOledRefreshMs < refreshInterval) return;
    lastOledRefreshMs = now;

    if (!deviceConnected && now - lastActivityMs > SCREENSAVER_TIMEOUT_MS) {
        renderScreensaver();
        return;
    }

    if (!latestTelemetryValid || !deviceConnected || !streaming) {
        latestTelemetry = readTelemetrySnapshot();
        latestTelemetryValid = true;
    }

    renderOled(latestTelemetry);
}

static bool parseOledPageArg(String arg, OledPage& nextPage) {
    arg.trim();
    arg.toUpperCase();

    if (arg == "DASH" || arg == "DASHBOARD" || arg == "0") {
        nextPage = OLED_PAGE_DASHBOARD;
        return true;
    }
    if (arg == "TELEM" || arg == "TELEMETRY" || arg == "FLEX" || arg == "1") {
        nextPage = OLED_PAGE_TELEMETRY;
        return true;
    }
    if (arg == "SYS" || arg == "SYSTEM" || arg == "STATUS" || arg == "2") {
        nextPage = OLED_PAGE_SYSTEM;
        return true;
    }
    if (arg == "HAND" || arg == "FINGER" || arg == "3") {
        nextPage = OLED_PAGE_HAND;
        return true;
    }
    if (arg == "WAVE" || arg == "WAVEFORM" || arg == "GRAPH" || arg == "4") {
        nextPage = OLED_PAGE_WAVE;
        return true;
    }
    if (arg == "NEXT") {
        nextPage = sanitizeOledPage((static_cast<uint8_t>(oledPage) + 1) % OLED_PAGE_COUNT);
        return true;
    }

    return false;
}

static bool applyOledPageCommand(const String& value) {
    int separator = value.indexOf(':');
    if (separator < 0) return false;

    String prefix = value.substring(0, separator);
    prefix.trim();
    prefix.toUpperCase();
    if (prefix != "OLED" && prefix != "PAGE") return false;

    OledPage nextPage = oledPage;
    if (!parseOledPageArg(value.substring(separator + 1), nextPage)) {
        notifyText("ERR:BAD_OLED_PAGE");
        return true;
    }

    oledPage = nextPage;
    prefs.putUChar("oled", static_cast<uint8_t>(oledPage));
    lastActivityMs = millis();
    notifyText(String("OK:OLED:") + oledPageCode(oledPage));
    updateOled(true);
    return true;
}

static void applyBrightnessCommand(const String& arg) {
    long level = arg.toInt();
    if (level < 0 || level > 100) {
        notifyText("ERR:BAD_BRIGHT");
        return;
    }
    oledBrightness = static_cast<uint8_t>(level);
    prefs.putUChar("bri", oledBrightness);
    applyOledBrightness();
    lastActivityMs = millis();
    notifyText("OK:BRIGHT:" + String(oledBrightness));
    updateOled(true);
}
#endif

// ---------------------------------------------------------------------------
// Command processing (runs in loop(), not on the BLE stack thread)
// ---------------------------------------------------------------------------

static void sendStatus() {
    String status = "OK:STATUS:s=";
    status += streaming ? "1" : "0";
    status += ",r=";
    status += String(telemetryIntervalMs);
    status += ",b=";
    status += String(batteryMillivolts());
    status += ",u=";
    status += String(millis() / 1000);
    status += ",p=";
    status += String(telemetryPacketCount);
#if GLOVE_ENABLE_IMU
    status += ",i=";
    status += imuReady ? "1" : "0";
#endif
#if GLOVE_ENABLE_OLED
    status += ",l=";
    status += String(oledBrightness);
#endif
    notifyText(status);
}

static void applyTelemetryRate(const String& arg) {
    long rate = arg.toInt();
    if (rate < TELEMETRY_MIN_INTERVAL_MS || rate > TELEMETRY_MAX_INTERVAL_MS) {
        notifyText("ERR:BAD_RATE");
        return;
    }
    telemetryIntervalMs = static_cast<uint16_t>(rate);
    prefs.putUShort("rate", telemetryIntervalMs);
    notifyText("OK:RATE:" + String(telemetryIntervalMs));
}

static void processCommand(const String& rawValue) {
    String value = trimCommand(rawValue);
    if (value.length() == 0) return;

    String upper = value;
    upper.toUpperCase();

    if (upper == "START") {
        streaming = true;
        notifyText("OK:START");
        return;
    }

    if (upper == "STOP") {
        streaming = false;
        notifyText("OK:STOP");
        return;
    }

    if (upper == "CAL") {
        calibrateFlexBaseline();
        return;
    }

    if (upper == "CAL:CLEAR" || upper == "RESETCAL") {
        clearFlexBaseline();
        return;
    }

    if (upper == "PING") {
        notifyText("OK:PONG");
        return;
    }

    if (upper == "VER" || upper == "VERSION") {
        notifyText("OK:VER:" FW_VERSION);
        return;
    }

    if (upper == "STATUS") {
        sendStatus();
        return;
    }

    if (upper.startsWith("RATE:")) {
        applyTelemetryRate(value.substring(5));
        return;
    }

#if GLOVE_ENABLE_OLED
    if (upper.startsWith("BRIGHT:")) {
        applyBrightnessCommand(value.substring(7));
        return;
    }

    if (applyOledPageCommand(value)) {
        return;
    }
#else
    if (upper.startsWith("OLED:") || upper.startsWith("PAGE:") || upper.startsWith("BRIGHT:")) {
        notifyText("ERR:NO_OLED");
        return;
    }
#endif

    if (upper.startsWith("NAME:")) {
        String nextName = trimCommand(value.substring(5));
        if (nextName.length() == 0 || nextName.length() > MAX_DEVICE_NAME_CHARS) {
            notifyText("ERR:BAD_NAME");
        } else {
            saveDeviceName(nextName);
        }
        return;
    }

    // Previously any short unrecognized string was silently saved as the device
    // name, so a typo'd command (e.g. "STAT") quietly renamed the glove. Names
    // must now go through "NAME:" explicitly; everything else is an error.
    notifyText("ERR:UNKNOWN_COMMAND");
}

class GloveCharacteristicCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* characteristic) override {
        auto raw = characteristic->getValue();
        if (raw.length() == 0) return;

        // Hand the command to loop(); heavy work must not run on this thread.
        if (!commandPending) {
            pendingCommand = String(raw.c_str());
            commandPending = true;
        } else {
            notifyText("ERR:BUSY");
        }
    }

    void onRead(BLECharacteristic* characteristic) override {
        characteristic->setValue(deviceName.c_str());
    }
};

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

static void configureAdc() {
    analogReadResolution(12);
    for (int i = 0; i < FLEX_COUNT; i++) {
        pinMode(FLEX_PINS[i], INPUT);
        analogSetPinAttenuation(FLEX_PINS[i], ADC_11db);
    }
    if (BATTERY_PIN >= 0) {
        pinMode(BATTERY_PIN, INPUT);
        analogSetPinAttenuation(BATTERY_PIN, ADC_11db);
    }
}

static void loadSettings() {
    prefs.begin("glove", false);
    deviceName = prefs.getString("name", DEFAULT_DEVICE_NAME);
    if (deviceName.length() == 0 || deviceName.length() > MAX_DEVICE_NAME_CHARS) {
        deviceName = DEFAULT_DEVICE_NAME;
    }

    for (int i = 0; i < FLEX_COUNT; i++) {
        flexBaseline[i] = prefs.getUShort(("z" + String(i)).c_str(), 0);
    }

    uint16_t storedRate = prefs.getUShort("rate", TELEMETRY_DEFAULT_INTERVAL_MS);
    telemetryIntervalMs = constrain(
        storedRate, TELEMETRY_MIN_INTERVAL_MS, TELEMETRY_MAX_INTERVAL_MS);

#if GLOVE_ENABLE_OLED
    oledPage = sanitizeOledPage(prefs.getUChar("oled", OLED_PAGE_DASHBOARD));
    oledBrightness = constrain(prefs.getUChar("bri", OLED_DEFAULT_BRIGHTNESS), 0, 100);
#endif
}

static void startBle() {
    BLEDevice::init(deviceName.c_str());
    // Larger MTU so a full flex + IMU + battery + counter packet fits in one
    // notification (ATT payload = MTU - 3). The app requests the same value.
    BLEDevice::setMTU(185);

    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new ServerCallbacks());

    BLEService* service = bleServer->createService(SERVICE_UUID);
    gloveCharacteristic = service->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
            BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_NOTIFY
    );
    gloveCharacteristic->setCallbacks(new GloveCharacteristicCallbacks());
    gloveCharacteristic->addDescriptor(new BLE2902());
    gloveCharacteristic->setValue(deviceName.c_str());

    service->start();

    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    advertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();
}

#if GLOVE_ENABLE_OLED || GLOVE_ENABLE_IMU
static void startI2C() {
    Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
    Wire.setClock(I2C_CLOCK_HZ);
}
#endif

#if GLOVE_ENABLE_IMU
static bool mpuProbe(uint8_t address) {
    Wire.beginTransmission(address);
    return Wire.endTransmission() == 0;
}

static void startImu() {
    mpuAddress = mpuProbe(MPU_ADDR_LOW) ? MPU_ADDR_LOW
        : (mpuProbe(MPU_ADDR_HIGH) ? MPU_ADDR_HIGH : 0);
    if (mpuAddress == 0) {
        Serial.println("MPU6050 not found; continuing without IMU");
        imuReady = false;
        return;
    }
    uint8_t who = mpuReadReg(MPU_REG_WHO_AM_I);
    if (who != 0x68) {
        Serial.print("MPU6050 unexpected WHO_AM_I 0x");
        Serial.println(who, HEX);
    }
    mpuWriteReg(MPU_REG_PWR_MGMT_1, 0x00); // wake from sleep
    delay(100);
    imuReady = true;
    Serial.print("MPU6050 ready at 0x");
    Serial.println(mpuAddress, HEX);
}
#endif

#if GLOVE_ENABLE_OLED
static void startOled() {
    oled.setI2CAddress(OLED_I2C_ADDRESS << 1);
    oledReady = oled.begin();
    if (!oledReady) {
        Serial.println("OLED init failed; continuing without display");
        return;
    }

    applyOledBrightness();
    initRain();
    oled.clearBuffer();
    oled.sendBuffer();
}
#endif

void setup() {
    Serial.begin(115200);
    configureAdc();
    loadSettings();
#if GLOVE_ENABLE_OLED || GLOVE_ENABLE_IMU
    startI2C();
#endif
#if GLOVE_ENABLE_IMU
    startImu();
#endif
#if GLOVE_ENABLE_OLED
    startOled();
    playBootAnimation();
#endif
    startBle();

    Serial.print("Glove firmware v" FW_VERSION " advertising as ");
    Serial.println(deviceName);
#if GLOVE_ENABLE_OLED
    lastActivityMs = millis();
    updateOled(true);
#endif
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------

void loop() {
    // Run queued BLE commands here so the BLE stack thread never blocks.
    if (commandPending) {
        String command = pendingCommand;
        commandPending = false;
        processCommand(command);
    }

    if (deviceConnected && streaming && millis() - lastTelemetryMs >= telemetryIntervalMs) {
        lastTelemetryMs = millis();
        latestTelemetry = readTelemetrySnapshot();
        latestTelemetryValid = true;
        telemetryPacketCount++;
        String packet = makeTelemetryPacket(latestTelemetry);
        notifyText(packet);
        Serial.println(packet);
#if GLOVE_ENABLE_OLED
        pushWaveSample(latestTelemetry);
#endif
    }

    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        advertisingRestartAtMs = millis() + 500;
    }

    if (advertisingRestartAtMs != 0 && millis() >= advertisingRestartAtMs) {
        advertisingRestartAtMs = 0;
        BLEDevice::startAdvertising();
        Serial.println("BLE advertising restarted");
    }

    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        Serial.println("BLE client connected");
    }

#if GLOVE_ENABLE_OLED
    updateOled();
#endif
}
