# Glove Project

Smart glove project with Android BLE controls, ESP32 firmware, CAD case files,
and build notes.

## Folder layout

- `apps/android_ble_app` - Kotlin + Jetpack Compose Android app.
- `firmware/esp32_glove_ble` - main ESP32 BLE glove firmware.
- `firmware/esp32_ble_name_server` - BLE name server and OLED status sketch.
- `firmware/tests/oled_i2c_test` - OLED I2C test sketch.
- `hardware/cad` - OpenSCAD, STL, and 3MF case versions.
- `docs/bom_budget.ods` - parts budget and BOM spreadsheet.
- `up.sh` - firmware picker, compile, upload, and serial monitor helper.

## Common commands

List available Arduino sketches:

```bash
./up.sh --list
```

Build and upload firmware:

```bash
./up.sh
```

Build the Android app:

```bash
cd apps/android_ble_app
./gradlew assembleDebug
```

# X-glove
