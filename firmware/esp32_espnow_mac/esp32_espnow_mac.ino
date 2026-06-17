// Utility sketch: print this board's STA MAC address over serial.
// Flash this to each ESP32-C3 first, note the MAC, then use those values
// in esp32_espnow_peer to point each board at the other one.

#include <WiFi.h>

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_STA);

  Serial.println();
  Serial.print("STA MAC address: ");
  Serial.println(WiFi.macAddress());
}

void loop() {
  // Re-print every 5s so it's easy to catch after opening the monitor.
  Serial.print("STA MAC address: ");
  Serial.println(WiFi.macAddress());
  delay(5000);
}
