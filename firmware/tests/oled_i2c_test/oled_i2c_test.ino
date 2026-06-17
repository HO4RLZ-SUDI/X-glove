#include <Wire.h>
#include <WiFi.h>
#include <U8g2lib.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64

// I2C pins. Change these if your board uses other pins.
#if CONFIG_IDF_TARGET_ESP32C3
// C3 SuperMini: GPIO5/6 free (8/9 = onboard LED/BOOT button).
#define I2C_SDA 5
#define I2C_SCL 6
#else
#define I2C_SDA 21
#define I2C_SCL 22
#endif

// Put your WiFi here if you want the ESP32 to connect and show IP/RSSI.
// Leave WIFI_SSID empty to show scan status instead.
#define WIFI_SSID "Jirayu_2.4G"
#define WIFI_PASSWORD "0806498701"

// Many blue 1.3" I2C OLED modules use SH1106, not SSD1306.
// If your module is really SSD1306, see README.md for the alternate constructor.
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);

uint8_t oledAddress = 0;
bool scanMode = false;
bool scanRunning = false;
int scanCount = -1;
String strongestSsid = "";
int32_t strongestRssi = 0;
unsigned long lastReconnectMs = 0;
unsigned long lastScanStartMs = 0;
unsigned long lastDisplayMs = 0;

bool probeI2C(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

uint8_t findOledAddress() {
  if (probeI2C(0x3C)) {
    return 0x3C;
  }
  if (probeI2C(0x3D)) {
    return 0x3D;
  }
  return 0;
}

void scanI2CBus() {
  Serial.println("Scanning I2C bus...");

  int found = 0;
  for (uint8_t address = 1; address < 127; address++) {
    if (probeI2C(address)) {
      Serial.print("I2C device found at 0x");
      if (address < 16) {
        Serial.print("0");
      }
      Serial.println(address, HEX);
      found++;
    }
  }

  if (found == 0) {
    Serial.println("No I2C devices found.");
  }
}

const char *wifiStatusText(wl_status_t status) {
  switch (status) {
    case WL_CONNECTED:
      return "CONNECTED";
    case WL_NO_SSID_AVAIL:
      return "NO SSID";
    case WL_CONNECT_FAILED:
      return "FAILED";
    case WL_CONNECTION_LOST:
      return "LOST";
    case WL_DISCONNECTED:
      return "DISCONNECTED";
    case WL_IDLE_STATUS:
      return "IDLE";
    default:
      return "CONNECTING";
  }
}

void drawText(uint8_t x, uint8_t y, const char *text, uint8_t maxChars) {
  char clipped[32];
  snprintf(clipped, sizeof(clipped), "%s", text);

  if (strlen(clipped) > maxChars) {
    clipped[maxChars] = '\0';
    if (maxChars >= 2) {
      clipped[maxChars - 1] = '.';
      clipped[maxChars - 2] = '.';
    }
  }

  display.drawStr(x, y, clipped);
}

void formatUptime(char *buffer, size_t len) {
  unsigned long totalSeconds = millis() / 1000UL;
  unsigned int hours = totalSeconds / 3600UL;
  unsigned int minutes = (totalSeconds / 60UL) % 60UL;
  unsigned int seconds = totalSeconds % 60UL;
  snprintf(buffer, len, "%02u:%02u:%02u", hours, minutes, seconds);
}

void beginWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);

  if (strlen(WIFI_SSID) == 0) {
    scanMode = true;
    WiFi.disconnect(false, false);
    return;
  }

  scanMode = false;
  Serial.print("Connecting WiFi: ");
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  lastReconnectMs = millis();
}

void maintainWifi() {
  if (scanMode) {
    if (!scanRunning && millis() - lastScanStartMs > 10000UL) {
      WiFi.scanDelete();
      WiFi.scanNetworks(true);
      scanRunning = true;
      lastScanStartMs = millis();
      scanCount = -1;
    }

    if (scanRunning) {
      int result = WiFi.scanComplete();
      if (result >= 0) {
        scanCount = result;
        strongestSsid = "";
        strongestRssi = -999;

        for (int i = 0; i < result; i++) {
          if (WiFi.RSSI(i) > strongestRssi) {
            strongestRssi = WiFi.RSSI(i);
            strongestSsid = WiFi.SSID(i);
          }
        }

        scanRunning = false;
        Serial.print("WiFi scan found ");
        Serial.print(scanCount);
        Serial.println(" APs");
      }
    }
    return;
  }

  if (WiFi.status() != WL_CONNECTED && millis() - lastReconnectMs > 15000UL) {
    Serial.println("WiFi reconnect...");
    WiFi.disconnect(false, false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    lastReconnectMs = millis();
  }
}

void showNetworkStatusScreen() {
  char line[32];
  char uptime[12];

  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);

  display.drawFrame(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
  display.drawStr(4, 12, "NETWORK STATUS");

  formatUptime(uptime, sizeof(uptime));
  snprintf(line, sizeof(line), "UP %s", uptime);
  display.drawStr(69, 12, line);

  if (scanMode) {
    display.drawStr(4, 27, "WiFi: SCAN MODE");

    if (scanRunning) {
      display.drawStr(4, 42, "Scanning...");
    } else if (scanCount >= 0) {
      snprintf(line, sizeof(line), "APs:%d Best:%lddBm", scanCount, strongestRssi);
      display.drawStr(4, 42, line);
    } else {
      display.drawStr(4, 42, "Waiting scan...");
    }

    if (strongestSsid.length() > 0) {
      snprintf(line, sizeof(line), "SSID:%s", strongestSsid.c_str());
      drawText(4, 57, line, 20);
    } else {
      display.drawStr(4, 57, "Set WIFI_SSID to connect");
    }
  } else if (WiFi.status() == WL_CONNECTED) {
    snprintf(line, sizeof(line), "WiFi: %ld dBm", WiFi.RSSI());
    display.drawStr(4, 27, line);

    snprintf(line, sizeof(line), "IP: %s", WiFi.localIP().toString().c_str());
    display.drawStr(4, 42, line);

    snprintf(line, sizeof(line), "SSID:%s", WiFi.SSID().c_str());
    drawText(4, 57, line, 20);
  } else {
    snprintf(line, sizeof(line), "WiFi: %s", wifiStatusText(WiFi.status()));
    display.drawStr(4, 27, line);

    snprintf(line, sizeof(line), "SSID:%s", WIFI_SSID);
    drawText(4, 42, line, 20);

    display.drawStr(4, 57, "Reconnecting...");
  }

  display.sendBuffer();
}

void setup() {
  Serial.begin(115200);
  delay(300);

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);

  scanI2CBus();

  oledAddress = findOledAddress();
  if (oledAddress == 0) {
    Serial.println("OLED not found at 0x3C or 0x3D. Check wiring/pins.");
    return;
  }

  display.setI2CAddress(oledAddress << 1);
  display.setBusClock(100000);
  display.begin();

  Serial.println("OLED initialized.");
  display.clearBuffer();
  display.sendBuffer();

  beginWifi();
}

void loop() {
  if (oledAddress == 0) {
    delay(1000);
    return;
  }

  maintainWifi();

  if (millis() - lastDisplayMs > 1000UL) {
    showNetworkStatusScreen();
    lastDisplayMs = millis();
  }

  delay(20);
}
