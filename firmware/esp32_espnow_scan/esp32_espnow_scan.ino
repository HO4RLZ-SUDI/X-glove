// ESP-NOW channel scanner for ESP32-C3. Finds which Wi-Fi channel the other
// board is actually on, and proves this board's RX hardware works across the
// whole 2.4 GHz band.
//
// How to use:
//   - Flash esp32_espnow_diag (fixed channel 1) to board A and power it.
//   - Flash THIS sketch to board B.
//   Board B hops channels 1..13, broadcasting a probe and listening on each.
//   It prints "HEARD peer on channel N" the moment it picks A up.
//
// You can also flash THIS to both boards: they hop independently and will
// overlap on a channel often enough to hear each other within a few seconds.
//
// If this sketch NEVER hears anything while the other board is powered, this
// board's receiver is almost certainly dead (RX hardware fault) — that's the
// definitive test.
//
// Requires ESP32 Arduino core v3.x.

#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>

static const uint8_t LED_PIN = 8;          // active-low on C3 SuperMini
static const uint8_t FIRST_CHANNEL = 1;
static const uint8_t LAST_CHANNEL  = 13;
static const unsigned long DWELL_MS = 500; // time spent listening per channel

static uint8_t BROADCAST_ADDR[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

typedef struct struct_message {
  uint32_t id;
  char     text[32];
  float    value;
} struct_message;

static struct_message outgoing;

static uint8_t curChannel = FIRST_CHANNEL;
static unsigned long channelStartMs = 0;
static unsigned long lastSendMs = 0;
static uint32_t counter = 0;
static uint32_t recvCount = 0;
static unsigned long ledOffAtMs = 0;
static int heardOnChannel[14] = {0}; // count of packets heard per channel

static void setChannel(uint8_t ch) {
  esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
}

void onSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {}

void onReceived(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  recvCount++;
  if (curChannel <= LAST_CHANNEL) heardOnChannel[curChannel]++;
  digitalWrite(LED_PIN, LOW);
  ledOffAtMs = millis() + 40;

  int rssi = info->rx_ctrl ? info->rx_ctrl->rssi : 0;
  const uint8_t *m = info->src_addr;
  Serial.printf("<<< HEARD peer on channel %u  from %02X:%02X:%02X:%02X:%02X:%02X  rssi=%d  (total %lu)\n",
                curChannel, m[0], m[1], m[2], m[3], m[4], m[5], rssi,
                (unsigned long)recvCount);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  WiFi.setSleep(false);
  esp_wifi_set_ps(WIFI_PS_NONE);

  Serial.println();
  Serial.printf("This board MAC: %s\n", WiFi.macAddress().c_str());
  Serial.printf("Scanning channels %u..%u, %lums each\n",
                FIRST_CHANNEL, LAST_CHANNEL, DWELL_MS);

  if (esp_now_init() != ESP_OK) {
    Serial.println("ESP-NOW init failed");
    return;
  }
  esp_now_register_send_cb(onSent);
  esp_now_register_recv_cb(onReceived);

  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, BROADCAST_ADDR, 6);
  peer.channel = 0;            // 0 = use the radio's current channel
  peer.encrypt = false;
  esp_now_add_peer(&peer);

  curChannel = FIRST_CHANNEL;
  setChannel(curChannel);
  channelStartMs = millis();
}

void loop() {
  unsigned long now = millis();

  if (ledOffAtMs && now >= ledOffAtMs) {
    digitalWrite(LED_PIN, HIGH);
    ledOffAtMs = 0;
  }

  // Broadcast a probe a few times per channel dwell.
  if (now - lastSendMs > 150) {
    lastSendMs = now;
    outgoing.id = counter++;
    strncpy(outgoing.text, "scan", sizeof(outgoing.text) - 1);
    outgoing.value = (float)curChannel;
    esp_now_send(BROADCAST_ADDR, (uint8_t *)&outgoing, sizeof(outgoing));
  }

  // Hop to the next channel after the dwell time.
  if (now - channelStartMs > DWELL_MS) {
    curChannel++;
    if (curChannel > LAST_CHANNEL) {
      // Report a summary at the end of each full sweep.
      Serial.print("--- sweep done. heard on channels:");
      bool any = false;
      for (int c = FIRST_CHANNEL; c <= LAST_CHANNEL; c++) {
        if (heardOnChannel[c]) {
          Serial.printf(" ch%d(%d)", c, heardOnChannel[c]);
          any = true;
        }
      }
      if (!any) Serial.print(" NONE — RX may be dead or peer is off");
      Serial.println();
      curChannel = FIRST_CHANNEL;
    }
    setChannel(curChannel);
    channelStartMs = now;
  }
}
