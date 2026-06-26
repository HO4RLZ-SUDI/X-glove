// Single flex sensor test -- one channel, with a rest baseline so the output
// tracks finger bend and settles back to ~0 when released.
//
// On boot it captures a baseline (hold the finger straight). The printed
// "delta" is filtered_raw - baseline: it moves as you bend and returns to ~0
// on release. Brief drop-outs toward 0 (a loose high-side wire momentarily
// opening the divider) are rejected as glitches so the value doesn't snap down.
//
// Divider per finger:
//   3V3 --- [flex] ---+--- FLEX_PIN
//                     |
//                  [R 20k] --- GND
//
// Press BOOT (GPIO9) any time to re-capture the baseline.

#include <Arduino.h>

// The one channel under test. GPIO3 = the pad labelled "3" on the SuperMini.
static const int FLEX_PIN = 3;
static const int BUTTON_PIN = 9; // BOOT button, re-zeros the baseline

static const int BAUD = 115200;
static const int SAMPLES_PER_READ = 8;
static const int ADC_FULL_SCALE = 4095; // 12-bit
static const uint32_t PRINT_INTERVAL_MS = 50;

// Readings below this are treated as a disconnect glitch (divider high side
// momentarily open -> node falls to GND), not a real bend, and are ignored.
static const int GLITCH_FLOOR = 100;
// Smoothing weight (0..1): higher = snappier, lower = smoother. Matches the
// main firmware's FLEX_EMA_ALPHA feel.
static const float EMA_ALPHA = 0.30f;

float flexEma = 0.0f;
bool emaReady = false;
int baseline = 0;
uint32_t lastPrintMs = 0;
bool lastButtonDown = false;

uint16_t readAnalogAverage(int pin) {
  uint32_t total = 0;
  for (int i = 0; i < SAMPLES_PER_READ; i++) {
    total += analogRead(pin);
    delayMicroseconds(250);
  }
  return total / SAMPLES_PER_READ;
}

// Average several reads, skipping glitch samples, to set the rest reference.
void captureBaseline() {
  uint32_t total = 0;
  int good = 0;
  for (int i = 0; i < 32; i++) {
    int r = readAnalogAverage(FLEX_PIN);
    if (r >= GLITCH_FLOOR) {
      total += r;
      good++;
    }
    delay(5);
  }
  baseline = good > 0 ? (int)(total / good) : 0;
  flexEma = baseline;
  emaReady = true;
  Serial.print("Baseline set to ");
  Serial.println(baseline);
}

void setup() {
  Serial.begin(BAUD);
  delay(300);
  analogReadResolution(12);
  pinMode(FLEX_PIN, INPUT);
  analogSetPinAttenuation(FLEX_PIN, ADC_11db); // full ~0..3.3V range
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  Serial.print("Single flex test on GPIO");
  Serial.println(FLEX_PIN);
  Serial.println("Hold finger straight for baseline...");
  delay(400);
  captureBaseline();
  Serial.println("Bend = delta moves, release = delta returns to ~0.");
  Serial.println("Press BOOT to re-zero.");
}

void loop() {
  // Re-zero baseline on a BOOT press (active-low edge).
  bool buttonDown = digitalRead(BUTTON_PIN) == LOW;
  if (buttonDown && !lastButtonDown) {
    captureBaseline();
  }
  lastButtonDown = buttonDown;

  int raw = readAnalogAverage(FLEX_PIN);

  // Reject disconnect glitches: hold the last filtered value instead of letting
  // the reading snap to 0 when the divider's high side briefly opens.
  bool glitch = raw < GLITCH_FLOOR;
  if (!glitch) {
    if (!emaReady) {
      flexEma = raw;
      emaReady = true;
    } else {
      flexEma += EMA_ALPHA * (raw - flexEma);
    }
  }

  int filtered = (int)lroundf(flexEma);
  int delta = filtered - baseline;

  uint32_t now = millis();
  if (now - lastPrintMs >= PRINT_INTERVAL_MS) {
    lastPrintMs = now;
    Serial.printf("raw=%4d  filt=%4d  base=%4d  delta=%+5d%s\n",
                  raw, filtered, baseline, delta, glitch ? "  (glitch held)" : "");
  }
}
