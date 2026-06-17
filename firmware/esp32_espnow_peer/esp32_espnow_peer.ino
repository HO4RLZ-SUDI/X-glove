// Two-way ESP-NOW link between two ESP32-C3 boards, with OLED status display.
//
// Flash the SAME sketch to both boards, no per-board edits. Both MAC
// addresses live in BOARD_A / BOARD_B below; at boot each board reads its own
// MAC and picks the other one as its peer.
//
// The board with an SH1106 128x64 OLED wired on I2C (SDA=5, SCL=6) shows the
// link status; boards without a display just log to serial.
//
// Requires ESP32 Arduino core v3.x. Uses the v3.3+ callback signatures
// (wifi_tx_info_t for send, esp_now_recv_info_t for receive).

#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <U8g2lib.h>
#include <Wire.h>

// === The two boards in this link ===
static uint8_t BOARD_A[6] = {0x9C, 0xCC, 0x01, 0xD1, 0x8F, 0x50};
static uint8_t BOARD_B[6] = {0x9C, 0xCC, 0x01, 0xD1, 0x8B, 0x0C};

// Both boards MUST use the same channel, otherwise every send fails.
static const uint8_t WIFI_CHANNEL = 1;

// Diagnostic: 1 = send to broadcast (no MAC-layer ACK required).
//   send -> OK  proves the sender's radio works.
//   "recv from" proves both boards are alive on the same channel.
// Set back to 0 for normal unicast operation once the link works.
#define BROADCAST_TEST 1
static uint8_t BROADCAST_ADDR[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// Consider the link "up" if we got an ACK or a packet within this window.
static const unsigned long LINK_TIMEOUT_MS = 3000;

// Filled in at boot with whichever of the two above is NOT this board.
static uint8_t PEER_ADDRESS[6];

// --- OLED ---
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#ifndef I2C_SDA
#if CONFIG_IDF_TARGET_ESP32C3
#define I2C_SDA 5
#define I2C_SCL 6
#endif
#endif
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
static bool oledReady = false;

// Message layout. Must be identical on both boards.
typedef struct struct_message {
  uint32_t id;
  char     text[32];
  float    value;
} struct_message;

static struct_message outgoing;
static struct_message incoming;

// --- Link state (shown on OLED) ---
static unsigned long lastSendMs = 0;
static unsigned long lastSendOkMs = 0;
static unsigned long lastRecvMs = 0;
static uint32_t counter = 0;
static uint32_t sentCount = 0;
static uint32_t okCount = 0;
static uint32_t recvCount = 0;
static int       lastRssi = 0;
static uint32_t lastRecvId = 0;
static float     lastRecvValue = 0.0f;

static bool linkUp() {
  unsigned long now = millis();
  return (lastSendOkMs && now - lastSendOkMs < LINK_TIMEOUT_MS) ||
         (lastRecvMs   && now - lastRecvMs   < LINK_TIMEOUT_MS);
}

// Called after a packet is handed to the radio.
void onSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  bool ok = (status == ESP_NOW_SEND_SUCCESS);
  if (ok) {
    okCount++;
    lastSendOkMs = millis();
  }
  Serial.printf("send -> %s\n", ok ? "OK" : "FAIL");
}

// Called when a packet arrives.
void onReceived(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  if (len != sizeof(incoming)) {
    Serial.printf("recv: unexpected length %d (want %u)\n",
                  len, (unsigned)sizeof(incoming));
    return;
  }
  memcpy(&incoming, data, sizeof(incoming));

  recvCount++;
  lastRecvMs = millis();
  lastRecvId = incoming.id;
  lastRecvValue = incoming.value;
  if (info->rx_ctrl) {
    lastRssi = info->rx_ctrl->rssi;
  }

  const uint8_t *m = info->src_addr;
  Serial.printf("recv from %02X:%02X:%02X:%02X:%02X:%02X  id=%lu  text=%s  value=%.2f  rssi=%d\n",
                m[0], m[1], m[2], m[3], m[4], m[5],
                (unsigned long)incoming.id, incoming.text, incoming.value, lastRssi);
}

// --- OLED helpers ---
bool probeI2C(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void setupOled() {
  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(100000);

  uint8_t addr = probeI2C(0x3C) ? 0x3C : (probeI2C(0x3D) ? 0x3D : 0);
  if (addr == 0) {
    Serial.println("OLED not found (0x3C/0x3D). Running headless.");
    return;
  }
  display.setI2CAddress(addr << 1);
  display.setBusClock(100000);
  display.begin();
  display.setContrast(255);
  oledReady = true;
  Serial.printf("OLED initialized at 0x%02X\n", addr);

  // Boot splash: if this shows, the draw path works.
  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);
  display.drawFrame(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
  display.drawStr(4, 14, "ESP-NOW");
  display.drawStr(4, 30, "booting...");
  display.sendBuffer();
}

void showStatusScreen() {
  if (!oledReady) {
    return;
  }
  bool up = linkUp();
  char line[32];

  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);
  display.drawFrame(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

  display.drawStr(4, 12, "ESP-NOW");
  display.drawStr(78, 12, up ? "LINK UP" : "NO LINK");

  snprintf(line, sizeof(line), "Peer %02X:%02X", PEER_ADDRESS[4], PEER_ADDRESS[5]);
  display.drawStr(4, 27, line);
  if (up) {
    snprintf(line, sizeof(line), "RSSI %d", lastRssi);
    display.drawStr(78, 27, line);
  }

  snprintf(line, sizeof(line), "TX ok %lu/%lu",
           (unsigned long)okCount, (unsigned long)sentCount);
  display.drawStr(4, 42, line);

  snprintf(line, sizeof(line), "RX #%lu v%.1f",
           (unsigned long)lastRecvId, lastRecvValue);
  display.drawStr(4, 57, line);

  display.sendBuffer();
}

// Pick the peer as whichever known board this one is NOT.
static bool resolvePeer() {
  uint8_t self[6];
  WiFi.macAddress(self);
  if (memcmp(self, BOARD_A, 6) == 0) { memcpy(PEER_ADDRESS, BOARD_B, 6); return true; }
  if (memcmp(self, BOARD_B, 6) == 0) { memcpy(PEER_ADDRESS, BOARD_A, 6); return true; }
  return false;
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();      // make sure we're not roaming to an AP's channel
  WiFi.setSleep(false);   // keep radio awake so unicast packets get ACKed
  esp_wifi_set_ps(WIFI_PS_NONE);

  setupOled();

  Serial.printf("This board MAC: %s\n", WiFi.macAddress().c_str());

  if (!resolvePeer()) {
    Serial.println("This board's MAC is not in BOARD_A/BOARD_B — update the sketch.");
    return;
  }
#if BROADCAST_TEST
  memcpy(PEER_ADDRESS, BROADCAST_ADDR, 6);
  Serial.println("BROADCAST_TEST: sending to FF:FF:FF:FF:FF:FF (no ACK)");
#endif

  Serial.printf("Peer MAC: %02X:%02X:%02X:%02X:%02X:%02X\n",
                PEER_ADDRESS[0], PEER_ADDRESS[1], PEER_ADDRESS[2],
                PEER_ADDRESS[3], PEER_ADDRESS[4], PEER_ADDRESS[5]);

  // Pin both radios to the same channel so unicast ACKs come through.
  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }

  esp_now_register_send_cb(onSent);
  esp_now_register_recv_cb(onReceived);

  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, PEER_ADDRESS, 6);
  peer.channel = WIFI_CHANNEL;
  peer.encrypt = false;

  if (esp_now_add_peer(&peer) != ESP_OK) {
    Serial.println("add peer failed");
    return;
  }

  Serial.printf("ready (channel %u)\n", WIFI_CHANNEL);
}

void loop() {
  unsigned long now = millis();

  // Send a packet every 2 seconds.
  if (now - lastSendMs > 2000) {
    lastSendMs = now;
    outgoing.id = counter++;
    strncpy(outgoing.text, "hello from peer", sizeof(outgoing.text) - 1);
    outgoing.text[sizeof(outgoing.text) - 1] = '\0';
    outgoing.value = 25.5f + counter;

    if (esp_now_send(PEER_ADDRESS, (uint8_t *)&outgoing, sizeof(outgoing)) == ESP_OK) {
      sentCount++;
    }
  }

  // Refresh the display ~4x/sec.
  static unsigned long lastDrawMs = 0;
  if (now - lastDrawMs > 250) {
    lastDrawMs = now;
    showStatusScreen();
  }
}
