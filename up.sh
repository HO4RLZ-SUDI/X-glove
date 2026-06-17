#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

SKETCH="${SKETCH:-}"
# UploadSpeed=115200 keeps esptool from switching baud on the native
# USB-Serial/JTAG port, which crashes with termios I/O errors at 921600.
FQBN="${FQBN:-esp32:esp32:esp32c3:CDCOnBoot=cdc,UploadSpeed=115200}"
PORT="${PORT:-}"
BAUD="${BAUD:-115200}"
MONITOR="${MONITOR:-0}"
NO_COMPILE="${NO_COMPILE:-0}"
ARDUINO_CLI="${ARDUINO_CLI:-}"

usage() {
  cat <<'EOF'
Usage:
  ./up.sh [PORT] [options]

Shows a firmware picker, then compiles and uploads the selected Arduino sketch.

Options:
  --port PORT       Serial port, for example /dev/ttyACM0
  --fqbn FQBN       Board FQBN, default: esp32:esp32:esp32c3:CDCOnBoot=cdc
  --sketch PATH     Sketch directory; skips the firmware picker
  --baud BAUD       Serial monitor baudrate, default: 115200
  --list            List available firmware sketches and exit
  --monitor         Open serial monitor after upload
  --monitor-only    Skip compile and upload; open the serial monitor only
  --no-compile      Skip compile step and upload only
  -h, --help        Show this help

Environment overrides:
  PORT=/dev/ttyACM0 FQBN=esp32:esp32:esp32c3:CDCOnBoot=cdc SKETCH=firmware/tests/oled_i2c_test ./up.sh
  ARDUINO_CLI=$HOME/bin/arduino-cli ./up.sh
  MONITOR=1 BAUD=115200 ./up.sh
EOF
}

die() {
  echo "up.sh: $*" >&2
  exit 1
}

find_arduino_cli() {
  if [[ -n "$ARDUINO_CLI" ]]; then
    [[ -x "$ARDUINO_CLI" ]] || die "ARDUINO_CLI is not executable: $ARDUINO_CLI"
    return
  fi

  if command -v arduino-cli >/dev/null 2>&1; then
    ARDUINO_CLI="$(command -v arduino-cli)"
    return
  fi

  if [[ -x "$HOME/bin/arduino-cli" ]]; then
    ARDUINO_CLI="$HOME/bin/arduino-cli"
    return
  fi

  die "missing command: arduino-cli

Fedora install options:
  sudo dnf install arduino-cli

Or install to \$HOME/bin:
  mkdir -p \$HOME/bin
  curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | sh -s -- -b \$HOME/bin

Then run:
  ./up.sh"
}

detect_port() {
  "$ARDUINO_CLI" board list | awk '$1 ~ "^/dev/tty(ACM|USB)" { print $1; exit }'
}

relative_path() {
  local path="$1"
  path="${path#$SCRIPT_DIR/}"
  printf '%s\n' "$path"
}

firmware_name() {
  local sketch="$1"
  case "$(relative_path "$sketch")" in
    firmware/esp32_glove_ble)
      printf 'ESP32 Glove BLE + OLED (main firmware)'
      ;;
    firmware/esp32_ble_name_server)
      printf 'ESP32 BLE Name Server + OLED status'
      ;;
    firmware/tests/oled_i2c_test)
      printf 'OLED I2C Network Status Test'
      ;;
    firmware/tests/mpu6050_test)
      printf 'MPU6050 I2C Sensor Test'
      ;;
    *)
      basename "$sketch"
      ;;
  esac
}

find_firmwares() {
  find "$SCRIPT_DIR" -maxdepth 4 -type f -name '*.ino' \
    -not -path '*/build/*' \
    -not -path '*/.gradle/*' \
    -print 2>/dev/null \
    | while IFS= read -r sketch_file; do
        dirname "$sketch_file"
      done \
    | sort -u
}

load_firmwares() {
  mapfile -t FIRMWARES < <(find_firmwares)
  [[ ${#FIRMWARES[@]} -gt 0 ]] || die "no Arduino firmware sketches found"

  # Keep the main glove firmware first so it is the default choice.
  local ordered=() sketch
  for sketch in "${FIRMWARES[@]}"; do
    [[ "$(relative_path "$sketch")" == "firmware/esp32_glove_ble" ]] && ordered+=("$sketch")
  done
  for sketch in "${FIRMWARES[@]}"; do
    [[ "$(relative_path "$sketch")" != "firmware/esp32_glove_ble" ]] && ordered+=("$sketch")
  done
  FIRMWARES=("${ordered[@]}")
}

list_firmwares() {
  local index=1
  local sketch

  load_firmwares
  for sketch in "${FIRMWARES[@]}"; do
    printf '%2d) %-36s %s\n' "$index" "$(firmware_name "$sketch")" "$(relative_path "$sketch")"
    index=$((index + 1))
  done
}

choose_firmware() {
  local choice

  load_firmwares
  if [[ ${#FIRMWARES[@]} -eq 1 ]]; then
    SKETCH="${FIRMWARES[0]}"
    return
  fi

  echo
  echo "Select firmware to upload:"
  list_firmwares
  echo

  while true; do
    read -r -p "Firmware number [1-${#FIRMWARES[@]}] (Enter = 1): " choice
    [[ -z "$choice" ]] && choice=1
    if [[ "$choice" =~ ^[0-9]+$ ]] &&
      (( choice >= 1 && choice <= ${#FIRMWARES[@]} )); then
      SKETCH="${FIRMWARES[$((choice - 1))]}"
      return
    fi
    echo "Invalid choice: $choice" >&2
  done
}

LIST_ONLY=0
MONITOR_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      [[ $# -ge 2 ]] || die "--port needs a value"
      PORT="$2"
      shift 2
      ;;
    --fqbn)
      [[ $# -ge 2 ]] || die "--fqbn needs a value"
      FQBN="$2"
      shift 2
      ;;
    --sketch)
      [[ $# -ge 2 ]] || die "--sketch needs a value"
      SKETCH="$2"
      shift 2
      ;;
    --list)
      LIST_ONLY=1
      shift
      ;;
    --baud)
      [[ $# -ge 2 ]] || die "--baud needs a value"
      BAUD="$2"
      shift 2
      ;;
    --monitor)
      MONITOR=1
      shift
      ;;
    --monitor-only)
      MONITOR=1
      MONITOR_ONLY=1
      shift
      ;;
    --no-compile)
      NO_COMPILE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      if [[ -n "$PORT" ]]; then
        die "unexpected argument: $1"
      fi
      PORT="$1"
      shift
      ;;
  esac
done

find_arduino_cli

if [[ "$LIST_ONLY" == "1" ]]; then
  list_firmwares
  exit 0
fi

if [[ -z "$PORT" ]]; then
  PORT="$(detect_port)"
fi

[[ -n "$PORT" ]] || die "no serial port found; pass one, for example: ./up.sh /dev/ttyACM0"

if [[ "$MONITOR_ONLY" == "1" ]]; then
  echo "Port:     $PORT"
  echo "Baud:     $BAUD"
  exec "$ARDUINO_CLI" monitor -p "$PORT" -c "baudrate=$BAUD"
fi

if [[ -z "$SKETCH" ]]; then
  choose_firmware
fi

[[ -d "$SKETCH" ]] || die "sketch directory not found: $SKETCH"

echo "Firmware: $(firmware_name "$SKETCH")"
echo "Sketch:   $(relative_path "$SKETCH")"
echo "Board:    $FQBN"
echo "Port:     $PORT"
echo "CLI:      $ARDUINO_CLI"

if [[ "$NO_COMPILE" != "1" ]]; then
  "$ARDUINO_CLI" compile --fqbn "$FQBN" "$SKETCH"
fi

"$ARDUINO_CLI" upload -p "$PORT" --fqbn "$FQBN" "$SKETCH"

if [[ "$MONITOR" == "1" ]]; then
  echo "Opening serial monitor at $BAUD baud (Ctrl+C to exit)"
  "$ARDUINO_CLI" monitor -p "$PORT" -c "baudrate=$BAUD"
fi
