#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>
#include <U8g2lib.h>
#include <Wire.h>

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64

#ifndef I2C_SDA
#if CONFIG_IDF_TARGET_ESP32C3
#define I2C_SDA 5
#define I2C_SCL 6
#else
#endif
#endif

static const char *SERVICE_UUID = "0000FFE0-0000-1000-8000-00805F9B34FB";
static const char *NAME_CHAR_UUID = "0000FFE1-0000-1000-8000-00805F9B34FB";
static const char *NVS_NAMESPACE = "glove";
static const char *NVS_NAME_KEY = "ble_name";
static const char *DEFAULT_NAME = "ESP32 Glove";
static const size_t MAX_NAME_LEN = 20;

Preferences preferences;
BLEServer *server = nullptr;
BLEService *service = nullptr;
BLECharacteristic *nameCharacteristic = nullptr;
BLEAdvertising *advertising = nullptr;
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);

String bleName = DEFAULT_NAME;
String statusText = "BOOTING";
String lastEvent = "Starting BLE";
bool deviceConnected = false;
bool oledReady = false;
uint8_t oledAddress = 0;
unsigned long connectedAtMs = 0;
unsigned long lastDisplayMs = 0;
unsigned long lastEventMs = 0;

void setEvent(const String &event) {
  lastEvent = event;
  lastEventMs = millis();
}

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

void formatDuration(unsigned long elapsedMs, char *buffer, size_t len) {
  unsigned long totalSeconds = elapsedMs / 1000UL;
  unsigned int hours = totalSeconds / 3600UL;
  unsigned int minutes = (totalSeconds / 60UL) % 60UL;
  unsigned int seconds = totalSeconds % 60UL;
  snprintf(buffer, len, "%02u:%02u:%02u", hours, minutes, seconds);
}

void setupOled() {
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);

  oledAddress = findOledAddress();
  if (oledAddress == 0) {
    Serial.println("OLED not found at 0x3C or 0x3D. BLE will run without display.");
    return;
  }

  display.setI2CAddress(oledAddress << 1);
  display.setBusClock(100000);
  display.begin();
  display.clearBuffer();
  display.sendBuffer();
  oledReady = true;

  Serial.print("OLED initialized at 0x");
  Serial.println(oledAddress, HEX);
}

void showBleStatusScreen() {
  if (!oledReady) {
    return;
  }

  char line[32];
  char uptime[12];
  unsigned long elapsedMs = deviceConnected && connectedAtMs > 0
    ? millis() - connectedAtMs
    : millis();

  formatDuration(elapsedMs, uptime, sizeof(uptime));

  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);

  display.drawFrame(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
  display.drawStr(4, 12, "GLOVE BLE");

  snprintf(line, sizeof(line), "UP %s", uptime);
  display.drawStr(70, 12, line);

  snprintf(line, sizeof(line), "Status:%s", statusText.c_str());
  drawText(4, 27, line, 20);

  snprintf(line, sizeof(line), "Name:%s", bleName.c_str());
  drawText(4, 42, line, 20);

  if (lastEvent.length() > 0 && millis() - lastEventMs < 3000UL) {
    snprintf(line, sizeof(line), "Msg:%s", lastEvent.c_str());
  } else {
    snprintf(line, sizeof(line), "Client:%s", deviceConnected ? "CONNECTED" : "WAITING");
  }
  drawText(4, 57, line, 20);

  display.sendBuffer();
}

void notifyNameResult(const String &message) {
  if (nameCharacteristic == nullptr) {
    return;
  }

  nameCharacteristic->setValue(message.c_str());
  if (deviceConnected) {
    nameCharacteristic->notify();
  }
  nameCharacteristic->setValue(bleName.c_str());
}

String loadBleName() {
  preferences.begin(NVS_NAMESPACE, true);
  String storedName = preferences.getString(NVS_NAME_KEY, DEFAULT_NAME);
  preferences.end();

  storedName.trim();
  if (storedName.length() == 0 || storedName.length() > MAX_NAME_LEN) {
    return DEFAULT_NAME;
  }
  return storedName;
}

bool saveBleName(const String &newName) {
  preferences.begin(NVS_NAMESPACE, false);
  size_t written = preferences.putString(NVS_NAME_KEY, newName);
  preferences.end();
  return written > 0;
}

void startAdvertising() {
  if (advertising == nullptr) {
    return;
  }

  BLEAdvertisementData advertisementData;
  advertisementData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
  advertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));

  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(bleName);

  advertising->stop();
  advertising->setScanResponse(true);
  advertising->setAdvertisementData(advertisementData);
  advertising->setScanResponseData(scanResponseData);
  advertising->start();
  statusText = "ADVERTISING";
  setEvent("Ready");

  Serial.print("Advertising as ");
  Serial.println(bleName);
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    connectedAtMs = millis();
    statusText = "CONNECTED";
    setEvent("Phone OK");
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;
    connectedAtMs = 0;
    statusText = "DISCONNECTED";
    setEvent("Lost link");
    Serial.println("BLE client disconnected");
    delay(100);
    startAdvertising();
    setEvent("Lost link");
  }
};

class NameCharacteristicCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    String newName = String(characteristic->getValue().c_str());
    newName.trim();

    Serial.print("Name write request: ");
    Serial.println(newName);

    if (newName.length() == 0) {
      setEvent("Name empty");
      notifyNameResult("ERR:Empty name");
      return;
    }

    if (newName.length() > MAX_NAME_LEN) {
      setEvent("Name long");
      notifyNameResult("ERR:Name too long");
      return;
    }

    if (!saveBleName(newName)) {
      setEvent("NVS error");
      notifyNameResult("ERR:NVS save failed");
      return;
    }

    bleName = newName;
    nameCharacteristic->setValue(bleName.c_str());
    setEvent("Name saved");
    notifyNameResult("OK:" + bleName);

    Serial.print("Saved BLE name: ");
    Serial.println(bleName);
  }

  void onRead(BLECharacteristic *characteristic) override {
    characteristic->setValue(bleName.c_str());
  }
};

void setupBle() {
  BLEDevice::init(bleName.c_str());

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  service = server->createService(SERVICE_UUID);

  nameCharacteristic = service->createCharacteristic(
    NAME_CHAR_UUID,
    BLECharacteristic::PROPERTY_READ |
      BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_NOTIFY
  );
  nameCharacteristic->setCallbacks(new NameCharacteristicCallbacks());
  nameCharacteristic->addDescriptor(new BLE2902());
  nameCharacteristic->setValue(bleName.c_str());

  service->start();

  advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  startAdvertising();
}

void setup() {
  Serial.begin(115200);
  delay(300);

  setupOled();
  bleName = loadBleName();

  Serial.println();
  Serial.println("ESP32 BLE Name Server");
  Serial.print("Loaded BLE name: ");
  Serial.println(bleName);

  setupBle();
  showBleStatusScreen();
}

void loop() {
  if (millis() - lastDisplayMs > 500UL) {
    showBleStatusScreen();
    lastDisplayMs = millis();
  }

  delay(20);
}
