// ESP-NOW broadcast diagnostic for two ESP32-C3 SuperMini boards.
//
// Goal: prove the link without guessing. Flash the SAME sketch to BOTH boards,
// power both, watch either serial monitor. Broadcast needs no MAC and no ACK,
// so the ONLY proof of a working link is the "<<< RECV" line.
//
// On-board LED (GPIO8, active-low on the C3 SuperMini):
//   - blinks once per packet RECEIVED  -> you can confirm RX with no laptop.
//   - if it never blinks while the other board is powered, this board's RX is
//     the suspect (or they are on different channels -> use esp32_espnow_scan).
//
// Use the ROLE switch below to isolate a bad board:
//   ROLE_BOTH   (default) both send and receive
//   ROLE_TX     only transmit   (flash to the "good" board)
//   ROLE_RX     only receive     (flash to the suspect; LED must blink)
//
// Requires ESP32 Arduino core v3.x (uses esp_now_recv_info_t / wifi_tx_info_t).

#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

// === Role: change to test one direction at a time ===
#define ROLE_BOTH 0
#define ROLE_TX   1
#define ROLE_RX   2
#define ROLE ROLE_BOTH

// Both boards MUST agree on this channel.
static const uint8_t WIFI_CHANNEL = 1;

// On-board LED on the C3 SuperMini. Active LOW (LOW = on).
static const uint8_t LED_PIN = 8;

static uint8_t BROADCAST_ADDR[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

typedef struct struct_message {
  uint32_t id;
  char     text[32];
  float    value;
} struct_message;

static struct_message outgoing;
static struct_message incoming;

static uint32_t counter = 0;
static uint32_t sentCount = 0, okCount = 0, recvCount = 0;
static unsigned long lastSendMs = 0, lastRecvMs = 0, lastWarnMs = 0;
static unsigned long ledOffAtMs = 0;

static void blinkLed() {
  digitalWrite(LED_PIN, LOW);        // on
  ledOffAtMs = millis() + 40;
}

void onSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
  if (status == ESP_NOW_SEND_SUCCESS) okCount++;
}

void onReceived(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  recvCount++;
  lastRecvMs = millis();
  blinkLed();

  int rssi = info->rx_ctrl ? info->rx_ctrl->rssi : 0;
  const uint8_t *m = info->src_addr;

  if (len == (int)sizeof(incoming)) {
    memcpy(&incoming, data, sizeof(incoming));
    Serial.printf("<<< RECV from %02X:%02X:%02X:%02X:%02X:%02X  id=%lu  value=%.2f  rssi=%d  (total %lu)\n",
                  m[0], m[1], m[2], m[3], m[4], m[5],
                  (unsigned long)incoming.id, incoming.value, rssi,
                  (unsigned long)recvCount);
  } else {
    Serial.printf("<<< RECV from %02X:%02X:%02X:%02X:%02X:%02X  len=%d rssi=%d (unexpected size)\n",
                  m[0], m[1], m[2], m[3], m[4], m[5], len, rssi);
  }
}

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);       // off

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);              // radio must stay awake to receive
  esp_wifi_set_ps(WIFI_PS_NONE);

  Serial.println();
  Serial.printf("This board MAC: %s\n", WiFi.macAddress().c_str());
#if ROLE == ROLE_TX
  Serial.println("ROLE = TX only");
#elif ROLE == ROLE_RX
  Serial.println("ROLE = RX only (LED should blink on every packet)");
#else
  Serial.println("ROLE = BOTH");
#endif

  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }
  esp_now_register_send_cb(onSent);
  esp_now_register_recv_cb(onReceived);

  // Broadcast must still be registered as a peer to send to it.
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, BROADCAST_ADDR, 6);
  peer.channel = WIFI_CHANNEL;
  peer.encrypt = false;
  if (esp_now_add_peer(&peer) != ESP_OK) {
    Serial.println("add broadcast peer failed");
    return;
  }

  Serial.printf("ready on channel %u — broadcasting %u-byte packets\n",
                WIFI_CHANNEL, (unsigned)sizeof(outgoing));
}

void loop() {
  unsigned long now = millis();

  // LED auto-off after a short blink.
  if (ledOffAtMs && now >= ledOffAtMs) {
    digitalWrite(LED_PIN, HIGH);
    ledOffAtMs = 0;
  }

#if ROLE != ROLE_RX
  if (now - lastSendMs > 1000) {
    lastSendMs = now;
    outgoing.id = counter++;
    strncpy(outgoing.text, "diag", sizeof(outgoing.text) - 1);
    outgoing.value = 25.5f + counter;
    if (esp_now_send(BROADCAST_ADDR, (uint8_t *)&outgoing, sizeof(outgoing)) == ESP_OK) {
      sentCount++;
    }
    Serial.printf(">>> SENT id=%lu  (tx ok %lu/%lu, rx %lu)\n",
                  (unsigned long)outgoing.id,
                  (unsigned long)okCount, (unsigned long)sentCount,
                  (unsigned long)recvCount);
  }
#endif

  // Loud warning if we have NEVER received, or RX went silent.
#if ROLE != ROLE_TX
  if (now - lastWarnMs > 5000) {
    lastWarnMs = now;
    if (recvCount == 0) {
      Serial.println("!!! RX silent: 0 packets received. Is the OTHER board "
                     "powered + flashed? Same channel? Try esp32_espnow_scan.");
    } else if (now - lastRecvMs > 5000) {
      Serial.printf("!!! RX stalled: no packet for %lus (last total %lu)\n",
                    (now - lastRecvMs) / 1000, (unsigned long)recvCount);
    }
  }
#endif
}
