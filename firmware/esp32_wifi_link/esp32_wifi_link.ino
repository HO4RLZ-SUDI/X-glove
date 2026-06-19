// Wi-Fi UDP fallback link between two ESP32-C3 boards.
//
// This does NOT use ESP-NOW at all — it uses the normal Wi-Fi stack. One board
// becomes a SoftAP, the other connects to it as a station, and they exchange
// UDP datagrams both ways. Useful when ESP-NOW won't cooperate, or to confirm
// the radios work at all.
//
// Flash the SAME sketch to both boards. Roles are decided by MAC:
//   - BOARD_A  -> SoftAP  (IP 192.168.4.1)
//   - BOARD_B  -> Station (gets 192.168.4.2, talks to .1)
// Fill BOARD_A / BOARD_B with your two MACs (from esp32_espnow_mac).
//
// Open both serial monitors: each board prints what it sends and receives.

#include <WiFi.h>
#include <WiFiUdp.h>

static uint8_t BOARD_A[6] = {0x9C, 0xCC, 0x01, 0xD1, 0x8F, 0x50}; // -> SoftAP
static uint8_t BOARD_B[6] = {0x9C, 0xCC, 0x01, 0xD1, 0x8B, 0x0C}; // -> Station

static const char *AP_SSID = "glove-link";
static const char *AP_PASS = "glove12345";     // >= 8 chars
static const uint16_t UDP_PORT = 4210;

static const uint8_t LED_PIN = 8;              // active-low on C3 SuperMini

static const IPAddress AP_IP(192, 168, 4, 1);
static const IPAddress AP_GW(192, 168, 4, 1);
static const IPAddress AP_MASK(255, 255, 255, 0);

static WiFiUDP udp;
static bool isAP = false;
static IPAddress peerIp;
static bool peerKnown = false;

static uint32_t counter = 0, recvCount = 0;
static unsigned long lastSendMs = 0, ledOffAtMs = 0;
static char rxbuf[64];

static void blink() { digitalWrite(LED_PIN, LOW); ledOffAtMs = millis() + 40; }

static int roleFromMac() {
  uint8_t self[6];
  WiFi.macAddress(self);
  if (memcmp(self, BOARD_A, 6) == 0) return 1;   // AP
  if (memcmp(self, BOARD_B, 6) == 0) return 2;   // STA
  return 0;                                        // unknown
}

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);

  // Read MAC in STA mode first so role detection matches esp32_espnow_mac.
  WiFi.mode(WIFI_STA);
  Serial.println();
  Serial.printf("This board MAC: %s\n", WiFi.macAddress().c_str());

  int role = roleFromMac();
  if (role == 0) {
    Serial.println("MAC not in BOARD_A/BOARD_B — defaulting to Station. "
                   "Update the sketch if both boards pick the same role.");
    role = 2;
  }
  isAP = (role == 1);

  if (isAP) {
    Serial.println("ROLE = SoftAP (192.168.4.1)");
    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(AP_IP, AP_GW, AP_MASK);
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("AP up: SSID=%s  IP=%s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
    // We learn the station's IP from the first packet it sends us.
  } else {
    Serial.println("ROLE = Station");
    WiFi.mode(WIFI_STA);
    WiFi.begin(AP_SSID, AP_PASS);
    Serial.printf("connecting to %s", AP_SSID);
    unsigned long t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) {
      delay(300);
      Serial.print(".");
    }
    Serial.println();
    if (WiFi.status() == WL_CONNECTED) {
      Serial.printf("connected. my IP=%s  gateway(AP)=%s\n",
                    WiFi.localIP().toString().c_str(),
                    WiFi.gatewayIP().toString().c_str());
      peerIp = WiFi.gatewayIP();   // the AP
      peerKnown = true;
    } else {
      Serial.println("!!! could not connect to AP. Is the other board powered "
                     "and set as BOARD_A (SoftAP)?");
    }
  }

  udp.begin(UDP_PORT);
}

void loop() {
  unsigned long now = millis();

  if (ledOffAtMs && now >= ledOffAtMs) {
    digitalWrite(LED_PIN, HIGH);
    ledOffAtMs = 0;
  }

  // Receive.
  int sz = udp.parsePacket();
  if (sz > 0) {
    int n = udp.read(rxbuf, sizeof(rxbuf) - 1);
    if (n < 0) n = 0;
    rxbuf[n] = '\0';
    recvCount++;
    blink();
    IPAddress from = udp.remoteIP();
    if (isAP) { peerIp = from; peerKnown = true; }  // learn station IP
    Serial.printf("<<< RECV from %s : %s  (total %lu)\n",
                  from.toString().c_str(), rxbuf, (unsigned long)recvCount);
  }

  // Send once per second to the peer (once we know where it is).
  if (peerKnown && now - lastSendMs > 1000) {
    lastSendMs = now;
    char msg[48];
    int len = snprintf(msg, sizeof(msg), "hello id=%lu", (unsigned long)counter++);
    udp.beginPacket(peerIp, UDP_PORT);
    udp.write((const uint8_t *)msg, len);
    udp.endPacket();
    Serial.printf(">>> SENT to %s : %s\n", peerIp.toString().c_str(), msg);
  }
}
