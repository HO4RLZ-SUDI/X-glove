// 5x flex sensor test with OLED + serial output for the glove project.
//
// Reads the five flex sensors on the ESP32-C3 SuperMini's ADC1 channels and
// shows each finger as a live bar + delta on the SH1106 OLED. Captures a rest
// baseline on boot so bars sit near zero when the hand is flat and grow as you
// bend; brief drop-outs toward 0 (a loose high-side wire momentarily opening a
// divider) are rejected so a finger's value doesn't snap down.
//
// Pinout (ESP32-C3 SuperMini -- all on ADC1):
//   F1 thumb   -> GPIO0   (ADC1_0)
//   F2 index   -> GPIO1   (ADC1_1)
//   F3 middle  -> GPIO2   (ADC1_2)  <- strapping pin, keep node HIGH at rest
//   F4 ring    -> GPIO3   (ADC1_3)
//   F5 pinky   -> GPIO4   (ADC1_4)
//   OLED       -> SDA GPIO5, SCL GPIO6, 3V3, GND   (addr 0x3C)
//
// Divider per finger:
//   3V3 --- [flex] ---+--- ADC pin
//                     |
//                  [R 20k] --- GND
// Bending raises the flex resistance, so the node (raw count) drops.
//
// Press BOOT (GPIO9) any time to re-capture the flat-hand baseline.

#include <Arduino.h>
#include <Wire.h>
#include <U8g2lib.h>

// I2C for the OLED. GPIO5/6 are free on the C3 SuperMini (8/9 = LED/BOOT).
static const int I2C_SDA_PIN = 5;
static const int I2C_SCL_PIN = 6;
static const uint8_t OLED_I2C_ADDRESS = 0x3C;
U8G2_SH1106_128X64_NONAME_F_HW_I2C oled(U8G2_R0, U8X8_PIN_NONE);
bool oledReady = false;

// Flex sensor ADC pins. ESP32-C3 only exposes ADC1 on GPIO0..4 -- matches
// FLEX_PINS in esp32_glove_ble.ino. Index 0 = F1 (thumb) .. index 4 = F5.
static const int FLEX_PINS[] = {0, 1, 2, 3, 4};
static const int FLEX_COUNT = sizeof(FLEX_PINS) / sizeof(FLEX_PINS[0]);

static const int BAUD = 115200;
static const int BUTTON_PIN = 9; // BOOT button, re-zeros all baselines
static const int SAMPLES_PER_READ = 8;
static const int ADC_FULL_SCALE = 4095; // 12-bit
static const uint32_t PRINT_INTERVAL_MS = 100;
static const uint32_t OLED_INTERVAL_MS = 80;

// Readings below this are treated as a disconnect glitch, not a real bend.
static const int GLITCH_FLOOR = 100;
// Smoothing weight (0..1): higher = snappier. Matches the firmware's feel.
static const float EMA_ALPHA = 0.30f;
// Full-scale bend (counts) used to scale the bars to 0..100%.
static const int BEND_FULL_SCALE = 1400;

float flexEma[FLEX_COUNT] = {0};
bool emaReady[FLEX_COUNT] = {false};
int baseline[FLEX_COUNT] = {0};
bool glitchNow[FLEX_COUNT] = {false};
uint32_t lastPrintMs = 0;
uint32_t lastOledMs = 0;
bool lastButtonDown = false;

uint16_t readAnalogAverage(int pin) {
  uint32_t total = 0;
  for (int i = 0; i < SAMPLES_PER_READ; i++) {
    total += analogRead(pin);
    delayMicroseconds(250);
  }
  return total / SAMPLES_PER_READ;
}

// Average several reads per channel, skipping glitches, to set rest references.
void captureBaseline() {
  Serial.println("Capturing baseline (hold hand flat)...");
  for (int i = 0; i < FLEX_COUNT; i++) {
    uint32_t total = 0;
    int good = 0;
    for (int s = 0; s < 24; s++) {
      int r = readAnalogAverage(FLEX_PINS[i]);
      if (r >= GLITCH_FLOOR) { total += r; good++; }
      delay(3);
    }
    baseline[i] = good > 0 ? (int)(total / good) : 0;
    flexEma[i] = baseline[i];
    emaReady[i] = true;
  }
}

// Filtered count for a channel; rejects disconnect glitches by holding the EMA.
int readFiltered(int i) {
  int raw = readAnalogAverage(FLEX_PINS[i]);
  glitchNow[i] = raw < GLITCH_FLOOR;
  if (!glitchNow[i]) {
    if (!emaReady[i]) { flexEma[i] = raw; emaReady[i] = true; }
    else flexEma[i] += EMA_ALPHA * (raw - flexEma[i]);
  }
  return (int)lroundf(flexEma[i]);
}

// 0..100 bend level from the absolute delta off baseline.
uint8_t bendPercent(int delta) {
  int mag = abs(delta);
  if (mag > BEND_FULL_SCALE) mag = BEND_FULL_SCALE;
  return (uint8_t)map(mag, 0, BEND_FULL_SCALE, 0, 100);
}

void drawOled(const int filt[FLEX_COUNT], const int delta[FLEX_COUNT]) {
  if (!oledReady) return;
  oled.clearBuffer();
  oled.setFont(u8g2_font_5x8_tf);
  oled.drawStr(0, 8, "FLEX 5CH");
  oled.drawStr(96, 8, "delta");
  oled.drawHLine(0, 11, 128);

  const int16_t barX = 18;
  const int16_t barW = 70;
  for (int i = 0; i < FLEX_COUNT; i++) {
    int16_t y = 15 + i * 9;
    oled.setCursor(0, y + 7);
    oled.print('F');
    oled.print(i + 1);

    uint8_t level = bendPercent(delta[i]);
    int16_t fill = map(level, 0, 100, 0, barW - 2);
    oled.drawFrame(barX, y, barW, 7);
    if (fill > 0) oled.drawBox(barX + 1, y + 1, fill, 5);

    oled.setCursor(94, y + 7);
    if (glitchNow[i]) oled.print(" X");  // disconnect glitch on this channel
    else oled.print(delta[i]);
  }
  oled.sendBuffer();
}

void setup() {
  Serial.begin(BAUD);
  delay(300);

  analogReadResolution(12);
  for (int i = 0; i < FLEX_COUNT; i++) {
    pinMode(FLEX_PINS[i], INPUT);
    analogSetPinAttenuation(FLEX_PINS[i], ADC_11db); // full ~0..3.3V range
  }
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(400000);
  oled.setI2CAddress(OLED_I2C_ADDRESS << 1);
  oledReady = oled.begin();
  if (!oledReady) Serial.println("OLED not found (optional); serial only.");

  Serial.println("Flex 5ch test: F1..F5 on GPIO0..4 (ADC1).");
  Serial.println("Hold hand flat for baseline...");
  delay(400);
  captureBaseline();
  Serial.println("Bend = delta grows, release = returns to ~0. BOOT re-zeros.");
}

void loop() {
  // Re-zero baselines on a BOOT press (active-low edge).
  bool buttonDown = digitalRead(BUTTON_PIN) == LOW;
  if (buttonDown && !lastButtonDown) captureBaseline();
  lastButtonDown = buttonDown;

  int filt[FLEX_COUNT];
  int delta[FLEX_COUNT];
  for (int i = 0; i < FLEX_COUNT; i++) {
    filt[i] = readFiltered(i);
    delta[i] = filt[i] - baseline[i];
  }

  uint32_t now = millis();
  if (now - lastPrintMs >= PRINT_INTERVAL_MS) {
    lastPrintMs = now;
    for (int i = 0; i < FLEX_COUNT; i++) {
      Serial.printf("F%d d=%+5d%s  ", i + 1, delta[i],
                    glitchNow[i] ? "(X)" : "   ");
    }
    Serial.println();
  }

  if (now - lastOledMs >= OLED_INTERVAL_MS) {
    lastOledMs = now;
    drawOled(filt, delta);
  }
}
