#!/usr/bin/env python3
"""Serial -> Thai TTS bridge for the flex_sensor_test sketch.

The ESP32-C3 cannot drive a Bluetooth speaker itself (it has BLE only, no
A2DP). So instead it prints a trigger over USB serial and this script, running
on the computer the AM-127 speaker is paired to, speaks the word out the
default audio sink (= the Bluetooth speaker).

Protocol: any serial line of the form `SAY:<text>` is spoken with live TTS.
The firmware emits one `SAY:<phrase>` per recognised gesture (e.g. `SAY:สวัสดี`
when you hold the thumb+ring+pinky bend) -- see the sketch's gesture table for
the full list. All other lines are echoed, so this also works as your serial
monitor -- close the Arduino IDE monitor first, only one program can own the port.

Usage:
    pip install pyserial gTTS
    # a player for mp3: mpg123 (preferred), or ffplay/ffmpeg, or mpv
    sudo dnf install mpg123
    python3 greet_bridge.py /dev/ttyACM0

TTS is generated fresh on every trigger (gTTS, needs internet). For an
offline setup, pre-render the clip once and play the file instead.
"""

import os
import shutil
import subprocess
import sys
import tempfile

try:
    import serial  # pyserial
except ImportError:
    sys.exit("missing dependency: pip install pyserial")

try:
    from gtts import gTTS
except ImportError:
    sys.exit("missing dependency: pip install gTTS")

BAUD = 115200
TRIGGER = "SAY:"
LANG = "th"

# First available mp3 player wins. All send audio to the default sink.
PLAYERS = [
    ["mpg123", "-q"],
    ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"],
    ["mpv", "--really-quiet", "--no-video"],
]


def pick_player():
    for p in PLAYERS:
        if shutil.which(p[0]):
            return p
    sys.exit("no mp3 player found -- install one of: mpg123, ffmpeg (ffplay), mpv")


def speak(text, player):
    """Generate Thai TTS for `text` and play it out the default audio device."""
    fd, path = tempfile.mkstemp(suffix=".mp3")
    os.close(fd)
    try:
        gTTS(text=text, lang=LANG).save(path)
        subprocess.run(player + [path], check=False)
    except Exception as e:  # network/TTS failure shouldn't kill the bridge
        print(f"[tts error] {e}", file=sys.stderr)
    finally:
        os.unlink(path)


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyACM0"
    player = pick_player()
    print(f"bridge: {port} @ {BAUD}, player={player[0]}, lang={LANG}")
    print("listening for 'SAY:<text>' lines (Ctrl-C to quit)\n")

    with serial.Serial(port, BAUD, timeout=1) as ser:
        while True:
            raw = ser.readline()
            if not raw:
                continue
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            if line.startswith(TRIGGER):
                text = line[len(TRIGGER):].strip()
                print(f"  >>> speaking: {text}")
                if text:
                    speak(text, player)
            else:
                print(line)  # act as a plain serial monitor for everything else


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nbye")
