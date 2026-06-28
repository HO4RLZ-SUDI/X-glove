// 5x flex sensor gesture test with OLED + serial output for the glove project.
//
// Reads the five flex sensors on the ESP32-C3 SuperMini's ADC1 channels, decides
// per finger whether it is bent (with hysteresis), and turns the five bend states
// into a 5-bit pattern. Each pattern maps to one Thai phrase from the gesture
// chart; when a pattern is held steady the firmware prints "SAY:<phrase>" and the
// host bridge (greet_bridge.py) speaks it. A flat-hand baseline is captured on
// boot so a finger reads "straight" at rest and "bent" as it curls; brief
// drop-outs toward 0 (a loose high-side wire momentarily opening a divider) are
// rejected so a finger's value doesn't snap down.
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
// Bending raises the flex resistance, so the node (raw count) drops. We track the
// absolute delta off the flat baseline, so the sign of the swing doesn't matter.
//
// Press BOOT (GPIO9) any time to re-capture the flat-hand baseline.
//
// Serial Monitor calibration (type a command + Enter, 115200 baud):
//   c | cal            re-capture the flat-hand baseline (same as BOOT)
//   a <0.01..1.0>      set EMA alpha (lower = smoother/steadier, higher = snappier)
//   s <1..64>          set samples averaged per read (more = steadier, slower)
//   m <0|1>            median filter instead of mean (0=off, 1=on; best glitch rejection)
//   n <50..1400>       bend-ON delta: a finger counts as bent above this
//   f <30..1400>       bend-OFF delta: a finger counts as straight below this
//   g | gestures       list the full gesture table
//   ? | show           print the current settings and one line of live deltas
//   h | help           list these commands
// Settings stay until reset; tune live until bends register cleanly and rest is quiet.

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
// FLEX_PINS in esp32_glove_ble.ino. Index 0 = F1 (thumb) .. index 4 = F5 (pinky).
static const int FLEX_PINS[] = {0, 1, 2, 3, 4};
static const int FLEX_COUNT = sizeof(FLEX_PINS) / sizeof(FLEX_PINS[0]);

static const int BAUD = 115200;
static const int BUTTON_PIN = 9; // BOOT button, re-zeros all baselines
static const int ADC_FULL_SCALE = 4095; // 12-bit
static const uint32_t PRINT_INTERVAL_MS = 100;
static const uint32_t OLED_INTERVAL_MS = 80;

// Readings below this are treated as a disconnect glitch, not a real bend.
static const int GLITCH_FLOOR = 100;
// Full-scale bend (counts) used to scale the OLED bars to 0..100%.
static const int BEND_FULL_SCALE = 1400;

// --- Live-tunable calibration (adjust over Serial; see header for commands) ---
static const int MAX_SAMPLES_PER_READ = 64;
// Samples averaged (or median'd) per read. More = steadier but slower.
int samplesPerRead = 16;
// EMA smoothing weight (0..1): lower = smoother/steadier, higher = snappier.
float emaAlpha = 0.20f;
// Median instead of mean per read: best rejection of single-sample spikes.
bool medianFilter = true;

// A finger flips to "bent" once its |delta| rises past BEND_ON and back to
// "straight" once it falls below BEND_OFF. The gap is hysteresis so a finger
// hovering at the edge can't chatter between bent/straight.
int bendOnDelta = 200;
int bendOffDelta = 120;

// --- Gesture recognition ----------------------------------------------------
// Each finger contributes one bit to a 5-bit pattern (1 = bent). Bit order
// matches FLEX_PINS: bit0 = F1 thumb .. bit4 = F5 pinky.
#define B_THUMB  0x01  // F1 (โป้ง)
#define B_INDEX  0x02  // F2 (ชี้)
#define B_MIDDLE 0x04  // F3 (กลาง)
#define B_RING   0x08  // F4 (นาง)
#define B_PINKY  0x10  // F5 (ก้อย)

// Phrase fires only after the pattern is held unchanged this long, so the
// transient patterns you pass through while curling fingers don't blurt out a
// wrong phrase. The pattern must also return to neutral (or change to another
// known one) before the same phrase can fire again.
static const uint32_t GESTURE_SETTLE_MS = 300;

// The 10 most-useful phrases, each on an easy 1-2 finger (or full-fist) combo.
// `thai` is what gets spoken; `en` is an ASCII label for the OLED + serial log
// (the OLED font has no Thai glyphs). Every mask is unique so each gesture is
// distinguishable.
struct Gesture {
  uint8_t mask;
  const char *thai;
  const char *en;
};

static const Gesture GESTURES[] = {
  { B_THUMB | B_INDEX | B_MIDDLE | B_RING | B_PINKY, "ช่วยด้วย", "help" },    // 1+2+3+4+5 (fist)
  { B_THUMB | B_INDEX,                      "เจ็บ",         "hurt" },          // 1+2
  { B_INDEX,                                "ใช่",          "yes" },           // 2
  { B_INDEX | B_MIDDLE,                     "ไม่ใช่",        "no" },            // 2+3
  { B_THUMB,                                "หิวข้าว",      "hungry" },        // 1
  { B_PINKY,                                "ต้องการน้ำ",   "need water" },     // 5
  { B_RING | B_PINKY,                       "เข้าห้องน้ำ",   "bathroom" },      // 4+5
  { B_THUMB | B_PINKY,                      "สวัสดี",       "hello" },         // 1+5
  { B_INDEX | B_MIDDLE | B_RING | B_PINKY,  "ขอบคุณ",      "thank you" },     // 2+3+4+5
  { B_INDEX | B_MIDDLE | B_RING,            "ไม่สบาย",      "sick" },          // 2+3+4
};
static const int GESTURE_COUNT = sizeof(GESTURES) / sizeof(GESTURES[0]);

float flexEma[FLEX_COUNT] = {0};
bool emaReady[FLEX_COUNT] = {false};
int baseline[FLEX_COUNT] = {0};
bool glitchNow[FLEX_COUNT] = {false};
bool fingerBent[FLEX_COUNT] = {false};
uint32_t lastPrintMs = 0;
uint32_t lastOledMs = 0;
bool lastButtonDown = false;

// Gesture state machine: track the pattern that has been steady, fire once it
// has settled, and remember what was last spoken so it doesn't repeat-fire.
uint8_t candidateMask = 0;
uint32_t candidateSince = 0;
uint8_t spokenMask = 0;
char lastLabel[20] = "ready";

// One steadied read of a channel: collect samplesPerRead samples, then return
// their median (spike-proof) or mean depending on medianFilter.
uint16_t readAnalogAverage(int pin) {
  int n = samplesPerRead;
  if (n < 1) n = 1;
  if (n > MAX_SAMPLES_PER_READ) n = MAX_SAMPLES_PER_READ;

  if (!medianFilter) {
    uint32_t total = 0;
    for (int i = 0; i < n; i++) {
      total += analogRead(pin);
      delayMicroseconds(250);
    }
    return total / n;
  }

  uint16_t buf[MAX_SAMPLES_PER_READ];
  for (int i = 0; i < n; i++) {
    buf[i] = analogRead(pin);
    delayMicroseconds(250);
  }
  // Insertion sort (n is small) then take the middle element.
  for (int i = 1; i < n; i++) {
    uint16_t key = buf[i];
    int j = i - 1;
    while (j >= 0 && buf[j] > key) { buf[j + 1] = buf[j]; j--; }
    buf[j + 1] = key;
  }
  return buf[n / 2];
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
    fingerBent[i] = false;
  }
  // Re-arm the gesture machine so the first bend after a re-zero fires cleanly.
  candidateMask = 0;
  spokenMask = 0;
}

// Filtered count for a channel; rejects disconnect glitches by holding the EMA.
int readFiltered(int i) {
  int raw = readAnalogAverage(FLEX_PINS[i]);
  glitchNow[i] = raw < GLITCH_FLOOR;
  if (!glitchNow[i]) {
    if (!emaReady[i]) { flexEma[i] = raw; emaReady[i] = true; }
    else flexEma[i] += emaAlpha * (raw - flexEma[i]);
  }
  return (int)lroundf(flexEma[i]);
}

// 0..100 bend level from the absolute delta off baseline (for the OLED bars).
uint8_t bendPercent(int delta) {
  int mag = abs(delta);
  if (mag > BEND_FULL_SCALE) mag = BEND_FULL_SCALE;
  return (uint8_t)map(mag, 0, BEND_FULL_SCALE, 0, 100);
}

// Return the index of the gesture matching `mask`, or -1 if none.
int findGesture(uint8_t mask) {
  for (int i = 0; i < GESTURE_COUNT; i++) {
    if (GESTURES[i].mask == mask) return i;
  }
  return -1;
}

void printSettings() {
  Serial.printf("settings: alpha=%.2f  samples=%d  median=%s  bendOn=%d  bendOff=%d\n",
                emaAlpha, samplesPerRead, medianFilter ? "on" : "off",
                bendOnDelta, bendOffDelta);
}

// Render a mask as finger digits, e.g. 0b00101 -> "13" (F1 + F3 bent).
void maskToDigits(uint8_t mask, char *out, size_t n) {
  size_t k = 0;
  for (int i = 0; i < FLEX_COUNT && k + 1 < n; i++) {
    if (mask & (1 << i)) out[k++] = char('1' + i);
  }
  if (k == 0 && n > 0) out[k++] = '-';
  out[k] = '\0';
}

void printGestures() {
  Serial.println("gesture table (finger digits: 1=thumb..5=pinky):");
  char digits[FLEX_COUNT + 1];
  for (int i = 0; i < GESTURE_COUNT; i++) {
    maskToDigits(GESTURES[i].mask, digits, sizeof(digits));
    Serial.printf("  %-6s %-16s %s\n", digits, GESTURES[i].en, GESTURES[i].thai);
  }
}

void printHelp() {
  Serial.println("commands:");
  Serial.println("  c | cal          re-capture flat-hand baseline");
  Serial.println("  a <0.01..1.0>    set EMA alpha (lower=steadier)");
  Serial.println("  s <1..64>        samples per read (more=steadier)");
  Serial.println("  m <0|1>          median filter off/on");
  Serial.println("  n <50..1400>     bend-ON delta (bent above this)");
  Serial.println("  f <30..1400>     bend-OFF delta (straight below this)");
  Serial.println("  g | gestures     list the gesture table");
  Serial.println("  ? | show         print settings + one live line");
  Serial.println("  h | help         this list");
}

// Parse one newline-terminated command from the Serial Monitor.
void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;
  cmd.toLowerCase();

  char c = cmd.charAt(0);
  // Argument is whatever follows the first token.
  int sp = cmd.indexOf(' ');
  String arg = sp >= 0 ? cmd.substring(sp + 1) : "";
  arg.trim();

  if (cmd == "c" || cmd == "cal") {
    captureBaseline();
    Serial.println("baseline re-captured.");
  } else if (cmd == "g" || cmd == "gestures") {
    printGestures();
  } else if (cmd == "?" || cmd == "show") {
    printSettings();
  } else if (cmd == "h" || cmd == "help") {
    printHelp();
  } else if (c == 'a') {
    float v = arg.toFloat();
    if (v >= 0.01f && v <= 1.0f) { emaAlpha = v; printSettings(); }
    else Serial.println("alpha must be 0.01..1.0");
  } else if (c == 's') {
    int v = arg.toInt();
    if (v >= 1 && v <= MAX_SAMPLES_PER_READ) { samplesPerRead = v; printSettings(); }
    else Serial.printf("samples must be 1..%d\n", MAX_SAMPLES_PER_READ);
  } else if (c == 'm') {
    int v = arg.toInt();
    medianFilter = (v != 0);
    printSettings();
  } else if (c == 'n') {
    int v = arg.toInt();
    if (v >= 50 && v <= BEND_FULL_SCALE) {
      bendOnDelta = v;
      if (bendOffDelta >= bendOnDelta) bendOffDelta = bendOnDelta - 20; // keep hysteresis
      printSettings();
    } else Serial.printf("bend-ON must be 50..%d\n", BEND_FULL_SCALE);
  } else if (c == 'f') {
    int v = arg.toInt();
    if (v >= 30 && v < bendOnDelta) { bendOffDelta = v; printSettings(); }
    else Serial.printf("bend-OFF must be 30..%d (below bend-ON)\n", bendOnDelta - 1);
  } else {
    Serial.println("unknown command -- type 'h' for help");
  }
}

void pollSerial() {
  static String line;
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') {
      if (line.length() > 0) { handleCommand(line); line = ""; }
    } else if (line.length() < 32) {
      line += ch;
    }
  }
}

void drawOled(const int delta[FLEX_COUNT], uint8_t liveMask) {
  if (!oledReady) return;
  oled.clearBuffer();
  oled.setFont(u8g2_font_5x8_tf);

  // Header: last spoken gesture (English label) so you can see what fired.
  oled.drawStr(0, 8, "GES:");
  oled.drawStr(22, 8, lastLabel);
  oled.drawHLine(0, 11, 128);

  const int16_t barX = 18;
  const int16_t barW = 64;
  for (int i = 0; i < FLEX_COUNT; i++) {
    int16_t y = 15 + i * 9;
    oled.setCursor(0, y + 7);
    oled.print('F');
    oled.print(i + 1);

    uint8_t level = bendPercent(delta[i]);
    int16_t fill = map(level, 0, 100, 0, barW - 2);
    oled.drawFrame(barX, y, barW, 7);
    if (fill > 0) oled.drawBox(barX + 1, y + 1, fill, 5);

    // Right column: "*" when this finger currently counts as bent, else delta.
    oled.setCursor(88, y + 7);
    if (glitchNow[i]) oled.print(" X");           // disconnect glitch
    else if (liveMask & (1 << i)) oled.print(" *"); // bent now
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

  Serial.println("Flex 5ch gesture test: F1..F5 on GPIO0..4 (ADC1).");
  Serial.println("Hold hand flat for baseline...");
  delay(400);
  captureBaseline();
  Serial.println("Bend a finger combo and hold -> it speaks the matching phrase.");
  Serial.println("BOOT re-zeros. Type 'g' for the gesture list, 'h' for help.");
  printSettings();
}

void loop() {
  // Calibration commands typed into the Serial Monitor.
  pollSerial();

  // Re-zero baselines on a BOOT press (active-low edge).
  bool buttonDown = digitalRead(BUTTON_PIN) == LOW;
  if (buttonDown && !lastButtonDown) captureBaseline();
  lastButtonDown = buttonDown;

  int delta[FLEX_COUNT];
  uint8_t liveMask = 0;
  for (int i = 0; i < FLEX_COUNT; i++) {
    int filt = readFiltered(i);
    delta[i] = filt - baseline[i];
    int mag = abs(delta[i]);
    // Per-finger bend state with hysteresis. A glitch holds the previous state.
    if (!glitchNow[i]) {
      if (mag >= bendOnDelta) fingerBent[i] = true;
      else if (mag <= bendOffDelta) fingerBent[i] = false;
    }
    if (fingerBent[i]) liveMask |= (1 << i);
  }

  // Gesture state machine. The host bridge (greet_bridge.py) speaks whatever
  // follows "SAY:". A pattern must hold for GESTURE_SETTLE_MS before it fires so
  // the transient patterns you sweep through while curling fingers are ignored.
  uint32_t gnow = millis();
  if (liveMask != candidateMask) {
    candidateMask = liveMask;
    candidateSince = gnow;
  } else if (gnow - candidateSince >= GESTURE_SETTLE_MS && candidateMask != spokenMask) {
    if (candidateMask == 0) {
      spokenMask = 0;                 // hand relaxed -> re-arm, nothing to say
    } else {
      int gi = findGesture(candidateMask);
      if (gi >= 0) {
        Serial.print("SAY:");
        Serial.println(GESTURES[gi].thai);
        strncpy(lastLabel, GESTURES[gi].en, sizeof(lastLabel) - 1);
        lastLabel[sizeof(lastLabel) - 1] = '\0';
        spokenMask = candidateMask;   // fired once; won't repeat until it changes
      }
      // Unknown pattern: leave spokenMask alone so adding/removing a finger to
      // reach a known combo still fires.
    }
  }

  uint32_t now = millis();
  if (now - lastPrintMs >= PRINT_INTERVAL_MS) {
    lastPrintMs = now;
    for (int i = 0; i < FLEX_COUNT; i++) {
      Serial.printf("F%d d=%+5d%s  ", i + 1, delta[i], glitchNow[i] ? "(X)" : "   ");
    }
    char digits[FLEX_COUNT + 1];
    maskToDigits(liveMask, digits, sizeof(digits));
    Serial.printf("bent=%s\n", digits);
  }

  if (now - lastOledMs >= OLED_INTERVAL_MS) {
    lastOledMs = now;
    drawOled(delta, liveMask);
  }
}
