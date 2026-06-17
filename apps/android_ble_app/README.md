# Glove BLE Android App

Kotlin + Jetpack Compose app for scanning, connecting, monitoring, and sending
commands to an ESP32-based BLE glove.

## BLE contract

- Service UUID: `0000FFE0-0000-1000-8000-00805F9B34FB`
- Name Characteristic UUID: `0000FFE1-0000-1000-8000-00805F9B34FB`
- Characteristic properties: Read, Write, Notify
- The app scans devices whose advertised name starts with `ESP32`, `Glove`,
  `SmartGlove`, or `BLEGlove`, or whose advertising data contains the service UUID.
- Notifications on `FFE1` are shown as live glove data when the payload starts
  with `DATA:`, `SENSOR:`, `FLEX:`, `IMU:`, or looks like numeric telemetry
  such as `f1=123,f2=456` or `123,456,789`.
- Live packets carry five flex channels plus optional MPU6050 accel/gyro
  (`ax,ay,az,gx,gy,gz`, raw int16 counts). The Live screen shows accel in g and
  gyro in °/s; the IMU section is hidden when no IMU data arrives.
- Device renames are sent as `NAME:<name>` (the firmware rejects other
  unrecognized writes with `ERR:UNKNOWN_COMMAND`). Acknowledgements use
  `OK:<name>` or `ERR:<reason>`.
- The app requests a 185-byte MTU so a full flex + IMU packet fits one
  notification.
- The command box writes UTF-8 commands to `FFE1`; quick commands are `CAL`,
  `START`, and `STOP`.
- The connected screen includes an OLED page selector. It sends `OLED:DASH`,
  `OLED:TELEM`, `OLED:SYS`, `OLED:HAND`, or `OLED:WAVE` to switch the glove
  display page.
- Recorded gesture sessions are saved to `gestures.csv`
  (`label,session,t_ms,f1..f5,ax,ay,az,gx,gy,gz`; older 8-column files without
  IMU still load) and can be reviewed in the dataset viewer with 5-channel
  waveform playback.

## Build

Open `apps/android_ble_app` in Android Studio, or build from the repo root:

```bash
cd apps/android_ble_app
./gradlew assembleDebug
```

If Gradle cannot find the Android SDK on this machine, update `local.properties`.

## Run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 12+ requires Bluetooth Scan and Connect permissions. Android 11 and older require Location permission for BLE scanning.

## Firmware

ESP32 Arduino firmware is in `firmware/esp32_glove_ble`.
