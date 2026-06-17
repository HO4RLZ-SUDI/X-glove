// MPU6050 sanity test for the glove project.
//
// Shares the same I2C bus as the OLED (SDA/SCL below). It scans the bus,
// verifies the MPU6050 WHO_AM_I register, wakes the sensor, then streams
// accel / gyro / temperature to both the serial monitor and the OLED.
//
// Wiring (ESP32-C3 SuperMini): VCC->3V3, GND->GND, SDA->GPIO5, SCL->GPIO6.
// The OLED can stay connected in parallel on the same two pins.

#include <Wire.h>
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

// MPU6050 registers.
static const uint8_t MPU_ADDR_LOW = 0x68;  // AD0 low (default)
static const uint8_t MPU_ADDR_HIGH = 0x69; // AD0 high
static const uint8_t REG_PWR_MGMT_1 = 0x6B;
static const uint8_t REG_WHO_AM_I = 0x75;
static const uint8_t REG_ACCEL_XOUT_H = 0x3B;

// Default full-scale ranges after reset: accel +/-2g, gyro +/-250 deg/s.
static const float ACCEL_LSB_PER_G = 16384.0f;
static const float GYRO_LSB_PER_DPS = 131.0f;

// Many blue 1.3" I2C OLED modules use SH1106, not SSD1306.
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);

uint8_t mpuAddress = 0;
uint8_t oledAddress = 0;
unsigned long lastDisplayMs = 0;
unsigned long lastPrintMs = 0;

// Orientation estimate (degrees), updated by a complementary filter.
float roll = 0.0f;
float pitch = 0.0f;
float yaw = 0.0f;             // integrated from gyro Z; drifts (no magnetometer)
unsigned long lastUpdateUs = 0;

static const float RAD2DEG = 57.29578f;
static const float DEG2RAD = 0.0174533f;

bool probeI2C(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void scanI2CBus() {
  Serial.println("Scanning I2C bus...");
  uint8_t found = 0;
  for (uint8_t address = 1; address < 127; address++) {
    if (probeI2C(address)) {
      Serial.print("  device found at 0x");
      if (address < 16) Serial.print('0');
      Serial.println(address, HEX);
      found++;
    }
  }
  if (found == 0) {
    Serial.println("  no I2C devices found. Check wiring/pins.");
  }
}

uint8_t findMpu() {
  if (probeI2C(MPU_ADDR_LOW)) return MPU_ADDR_LOW;
  if (probeI2C(MPU_ADDR_HIGH)) return MPU_ADDR_HIGH;
  return 0;
}

uint8_t findOled() {
  if (probeI2C(0x3C)) return 0x3C;
  if (probeI2C(0x3D)) return 0x3D;
  return 0;
}

void writeReg(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(mpuAddress);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}

uint8_t readReg(uint8_t reg) {
  Wire.beginTransmission(mpuAddress);
  Wire.write(reg);
  Wire.endTransmission(false);
  Wire.requestFrom(mpuAddress, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0;
}

// Reads 14 bytes: accel XYZ, temp, gyro XYZ (all big-endian int16).
bool readMotion(int16_t* ax, int16_t* ay, int16_t* az,
                int16_t* gx, int16_t* gy, int16_t* gz, int16_t* temp) {
  Wire.beginTransmission(mpuAddress);
  Wire.write(REG_ACCEL_XOUT_H);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(mpuAddress, (uint8_t)14) != 14) return false;

  *ax = (Wire.read() << 8) | Wire.read();
  *ay = (Wire.read() << 8) | Wire.read();
  *az = (Wire.read() << 8) | Wire.read();
  *temp = (Wire.read() << 8) | Wire.read();
  *gx = (Wire.read() << 8) | Wire.read();
  *gy = (Wire.read() << 8) | Wire.read();
  *gz = (Wire.read() << 8) | Wire.read();
  return true;
}

void showScreen(const char* line1, const char* line2, const char* line3,
                const char* line4) {
  if (oledAddress == 0) return;
  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);
  display.drawFrame(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
  display.drawStr(4, 12, line1);
  display.drawStr(4, 27, line2);
  display.drawStr(4, 42, line3);
  display.drawStr(4, 57, line4);
  display.sendBuffer();
}

// Draw a wireframe cube rotated by the current orientation, plus the angles.
void draw3D(float rollDeg, float pitchDeg, float yawDeg) {
  if (oledAddress == 0) return;

  float r = rollDeg * DEG2RAD, p = pitchDeg * DEG2RAD, y = yawDeg * DEG2RAD;
  float sr = sinf(r), cr = cosf(r);
  float sp = sinf(p), cp = cosf(p);
  float sy = sinf(y), cy = cosf(y);

  // Unit cube: 8 corners and the 12 edges connecting them.
  static const int8_t V[8][3] = {
    {-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},
    {-1,-1, 1},{1,-1, 1},{1,1, 1},{-1,1, 1}};
  static const uint8_t E[12][2] = {
    {0,1},{1,2},{2,3},{3,0},  // back face
    {4,5},{5,6},{6,7},{7,4},  // front face
    {0,4},{1,5},{2,6},{3,7}}; // connectors

  const int   cx = 92, cyc = 34;   // cube center on the right of the screen
  const float scale = 15.0f;
  int px[8], py[8];

  for (int i = 0; i < 8; i++) {
    float x = V[i][0], yy = V[i][1], z = V[i][2];
    // rotate X (pitch) -> Y (yaw) -> Z (roll)
    float y1 = yy * cp - z * sp,  z1 = yy * sp + z * cp,  x1 = x;
    float x2 = x1 * cy + z1 * sy, z2 = -x1 * sy + z1 * cy, y2 = y1;
    float x3 = x2 * cr - y2 * sr, y3 = x2 * sr + y2 * cr,  z3 = z2;

    float persp = 2.6f / (2.6f + z3);  // simple perspective
    px[i] = cx + (int)(x3 * scale * persp);
    py[i] = cyc + (int)(y3 * scale * persp);
  }

  display.clearBuffer();
  display.setFont(u8g2_font_5x8_tf);
  char b[10];
  snprintf(b, sizeof(b), "R%+d", (int)rollDeg);  display.drawStr(0, 10, b);
  snprintf(b, sizeof(b), "P%+d", (int)pitchDeg); display.drawStr(0, 22, b);
  snprintf(b, sizeof(b), "Y%+d", (int)yawDeg);   display.drawStr(0, 34, b);

  for (int i = 0; i < 12; i++) {
    display.drawLine(px[E[i][0]], py[E[i][0]], px[E[i][1]], py[E[i][1]]);
  }
  display.sendBuffer();
}

void setup() {
  Serial.begin(115200);
  delay(300);

  // Enable the C3 internal pull-ups as a fallback in case the modules lack
  // their own. External 4.7k pull-ups to 3V3 are still recommended.
  pinMode(I2C_SDA, INPUT_PULLUP);
  pinMode(I2C_SCL, INPUT_PULLUP);

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(50000); // slower clock is more forgiving on long/weak wiring

  scanI2CBus();

  oledAddress = findOled();
  if (oledAddress != 0) {
    display.setI2CAddress(oledAddress << 1);
    display.setBusClock(100000);
    display.begin();
    Serial.print("OLED found at 0x");
    Serial.println(oledAddress, HEX);
  } else {
    Serial.println("OLED not found (optional). Continuing with serial only.");
  }

  mpuAddress = findMpu();
  if (mpuAddress == 0) {
    Serial.println("MPU6050 NOT found at 0x68/0x69. Check wiring/pins.");
    showScreen("MPU6050 TEST", "NOT FOUND", "check SDA/SCL", "and 3V3/GND");
    return;
  }

  Serial.print("MPU6050 found at 0x");
  Serial.println(mpuAddress, HEX);

  uint8_t who = readReg(REG_WHO_AM_I);
  Serial.print("WHO_AM_I = 0x");
  Serial.println(who, HEX);
  if (who != 0x68) {
    Serial.println("WARNING: unexpected WHO_AM_I (clone chip?). Trying anyway.");
  }

  writeReg(REG_PWR_MGMT_1, 0x00); // wake from sleep
  delay(100);
  Serial.println("MPU6050 initialized. Streaming data...");
}

void loop() {
  if (mpuAddress == 0) {
    delay(1000);
    return;
  }

  int16_t ax, ay, az, gx, gy, gz, t;
  if (!readMotion(&ax, &ay, &az, &gx, &gy, &gz, &t)) {
    Serial.println("read failed");
    return;
  }

  float axg = ax / ACCEL_LSB_PER_G;
  float ayg = ay / ACCEL_LSB_PER_G;
  float azg = az / ACCEL_LSB_PER_G;
  float gxd = gx / GYRO_LSB_PER_DPS;
  float gyd = gy / GYRO_LSB_PER_DPS;
  float gzd = gz / GYRO_LSB_PER_DPS;

  // Time step for integrating the gyro.
  unsigned long nowUs = micros();
  float dt = (lastUpdateUs == 0) ? 0.0f : (nowUs - lastUpdateUs) / 1e6f;
  lastUpdateUs = nowUs;

  // Roll/pitch from gravity (absolute but noisy), fused with gyro (smooth but
  // drifts). Yaw has no absolute reference, so it's pure gyro integration.
  float accRoll  = atan2f(ayg, azg) * RAD2DEG;
  float accPitch = atan2f(-axg, sqrtf(ayg * ayg + azg * azg)) * RAD2DEG;
  roll  = 0.96f * (roll + gxd * dt) + 0.04f * accRoll;
  pitch = 0.96f * (pitch + gyd * dt) + 0.04f * accPitch;
  yaw  += gzd * dt;

  unsigned long now = millis();

  // Stream to serial ~5x/sec.
  if (now - lastPrintMs >= 200UL) {
    lastPrintMs = now;
    Serial.printf("A[g] x=%+.2f y=%+.2f z=%+.2f  G[dps] x=%+.1f y=%+.1f z=%+.1f  R=%+.0f P=%+.0f Y=%+.0f\n",
                  axg, ayg, azg, gxd, gyd, gzd, roll, pitch, yaw);
  }

  // Redraw the 3D cube ~25x/sec.
  if (now - lastDisplayMs >= 40UL) {
    lastDisplayMs = now;
    draw3D(roll, pitch, yaw);
  }
}
