# Glove wrist unit — watch-style enclosure

A 3D-printable, two-part wrist case for the glove electronics, styled after the
reference `frame.stl` (an Apple-Watch-like squircle: ~43 × 49 mm face, rounded
"pillow" edges, side crown + button). The reference is a solid styling shell —
this is the functional version: hollow, holds the boards, and has a display
window, USB access, and a flex-cable entry.

The body is built as a squircle prism **Minkowski-rounded by a sphere**, so every
outer edge (top, back, vertical corners) is filleted into one smooth pillow like
the reference, while the side walls stay flat and the shell stays a uniform
thickness. The face is a flat, recessed **rounded-rectangle "glass" area** (no
cosmetic round ring) with the OLED window in it — Apple-Watch style.

![preview](preview.png)

## What it fits

| Part | Size used | Where |
| --- | --- | --- |
| SH1106 1.3" OLED (128×64) | board 35×33, active 29×15.5 | top, behind the window |
| ESP32-C3 SuperMini | 22.5×18×6, USB-C on short end | board slot, +X column −Y end, USB to −Y wall |
| Type-C charge module | TP4056-class 26×17×5, USB-C on short end | board slot, +X column +Y end, USB to +Y wall |
| MPU6050 (GY-521) | 16×21×3 | shelf over the charge module (floor is full; still rigid) |
| LiPo pouch | **20×40×6** | walled compartment on the −X floor, wired to the charge module |
| Flex-divider board | thin perfboard ~22×40×≤2.5 | rests on the battery compartment walls (holds the 5 pull-down resistors) |
| Flex-sensor ribbon | ~11×3 slot | −Y wall centre (palm side) |

Wrist band: **Apple-Watch-style lugs** with a quick-release spring-bar channel
(default 24 mm / 42-45-49 family — see Assembly step 7).

**Power path:** the LiPo is wired to the on-board **Type-C charge module**, so the
case charges through the +Y wall port without opening it; the ESP32's own USB-C
(−Y wall) is then only for flashing. (No `BATTERY_PIN` is free on the C3 — all of
ADC1 goes to the 5 flex channels — so battery % isn't read in firmware.)

The footprint grew from the reference's 43 × 49 mm to **48 × 55 mm** so the LiPo
compartment (−X) sits beside the ESP32 + charge module column (+X) on a single
floor layer. **Thickness is 23 mm, not the reference's 14.4 mm:** the stack is
floor → 6 mm LiPo (in its compartment) +
ESP32 / charge module on the floor → a thin flex-divider perfboard (over the
battery) and the MPU (over the charge module) → the 1.3" OLED at the lid. The
LiPo, ESP32 and charge module are a single floor layer; only the thin perfboard
and the MPU stack. The pillow form (rounded
edges) makes it *read* much thinner than a flat-topped box of the same height. To
get closer to the reference profile, use a 0.96" OLED and set `case_h = 16`.

### Why a perfboard, not a real breadboard
The wiring junction (the 5 flex pull-down resistors + the ribbon/I2C/ESP meeting
point) is the only thing that actually needs a board — **9 nets total**: `3V3`,
`GND`, `SDA`/`SCL`, and `F1..F5`. An 8-column slice of an SYB-170 mini-breadboard
(5 resistor columns + 3 bus columns ≈ 22 × 35 mm) is enough *electrically*, but a
breadboard is **8.5 mm tall** and will not fit under the 1.3" OLED without pushing
the case to ~27 mm. So the bay is sized for a **thin perfboard (≤ 2.5 mm)** or a
breadboard with its leads cropped flush. Tune with `bb_t`; raise `case_h` and
`bb_z` if you insist on a full-height breadboard.

## Files

- `watch_case.scad` — the parametric source (edit the variables at the top).
- `base.stl`, `lid.stl` — ready-to-slice parts (regenerate after editing).
- `preview.png`, `section.png` — renders.

## Render / export

```bash
openscad -o base.stl -D 'part="base"' watch_case.scad
openscad -o lid.stl  -D 'part="lid"'  watch_case.scad
# previews:
openscad -o preview.png --viewall --autocenter watch_case.scad            # exploded + ghost parts
openscad -o section.png -D 'part="section"' --viewall --autocenter watch_case.scad
```

`part` accepts `base`, `lid`, `preview` (default, lid lifted off with translucent
component placeholders), or `section` (cutaway).

## Key parameters (top of `watch_case.scad`)

| Var | Default | Meaning |
| --- | --- | --- |
| `W`, `L`, `case_h` | 48, 55, 22 | outer width / length / thickness (mm) |
| `batt_w`, `batt_l`, `batt_t` | 20, 40, 6 | LiPo compartment W×L×depth (mm) |
| `batt_rib`, `batt_clr` | 1.6, 0.4 | compartment wall thickness / cell slip clearance |
| `esp_w`, `esp_l`, `esp_t` | 18, 22.5, 6 | ESP32-C3 board slot W×L×H (mm) |
| `chg_w`, `chg_l`, `chg_t` | 17, 26, 5 | Type-C charge module bay W×L×H (mm) |
| `bb_w`, `bb_l`, `bb_t`, `bb_z` | 22, 40, 2.0, — | flex-divider board footprint / thickness / shelf height |
| `corner_r` | 10 | squircle vertical corner radius |
| `edge_r` | 4.0 | 3D fillet on all outer edges (the pillow roundness) |
| `lid_cap` | 5.0 | height of the domed top cap = the lid |
| `wall`, `floor_th`, `lid_th` | 2.4, 2.0, 2.0 | shell thicknesses |
| `fit_gap` | 0.25 | lid↔base print clearance — widen if the lid is tight |
| `oled_*`, `esp_*`, `mpu_*`, `batt_*` | — | component sizes; edit to your exact parts |
| `aw_large`, `band_w` | true, 24 | Apple band size: true=42/44/45/49 (24mm), false=38/40/41 (20mm) |
| `bar_d`, `bar_z` | 1.9, 6.0 | spring-bar hole dia / height above the back |
| `crown_d`, `btn_d` | 6, 3.6 | cosmetic crown / button-access hole |

Everything downstream (ledge heights, window, screw bosses, lugs) is derived, so
changing a component size re-fits the case automatically.

## Assembly

1. **LiPo** drops into the walled −X compartment (20×40×6; thumb-scoop on the +Y
   end to lift it out). Its leads run to the **charge module**.
2. **ESP32-C3** slots into the +X column, −Y end (USB-C faces the −Y wall, for
   flashing). **Charge module** slots into the +X column, +Y end (USB-C faces the
   +Y wall — this is the charge port). **MPU6050** glues flat onto the charge
   module, pressed rigid — any play shows up as accel/gyro noise.
3. **Flex-divider board** drops in flat onto the battery compartment walls (no
   posts). Land the five pull-downs here (one per finger, node→GND across the
   board), the incoming ribbon (`F1..F5` + `3V3`), and short jumpers to the
   ESP32's `GPIO0..4`, `3V3`, `GND`, plus the shared I2C bus (`SDA`=G5, `SCL`=G6)
   out to the OLED and MPU. 9 nets total. Keep it ≤ 2.5 mm thick (perfboard, or a
   breadboard with the leads cropped flush) or it fouls the OLED.
4. **OLED** rests on the four wall tabs (high on the ±X walls, clear of the
   boards), glass just under the window.
5. Route the flex ribbon in through the −Y (palm-side) slot to the divider board.
6. Drop the **lid** in (lip aligns it) and fix with 4× M2 self-tapping screws
   into the corner bosses; heads countersink flush on top.
7. **Band:** the lugs are Apple-Watch style with a through spring-bar channel.
   Use an Apple-Watch band that has **quick-release pins**, or a cheap
   "Apple Watch → spring bar" adapter for Apple's first-party slide-lock bands.
   Default gap is 24 mm (42/44/45/49 family); set `aw_large = false` for the
   38/40/41 (20 mm) family. Apple's native metal slide-lock is intentionally not
   reproduced — it needs the band's spring mechanism and won't print reliably.
   Tune `band_w` / `bar_z` to your physical band and reprint just one end with
   `-D 'part="base"'` cropped, or do a quick caliper check before the full print.

## Print notes

- Base prints open-side up (no supports). Lid prints face-down (glass side on the
  bed) so the flat recessed face comes out clean; the rounded top then needs no
  supports and the countersinks self-support.
- PETG/ABS for a wearable that flexes; PLA is fine for a prototype.
- Both STLs verified watertight (0 non-manifold edges, single connected solid).

## Deviations from the reference (on purpose)

- **Bigger** (48 × 55 × 22 vs 43 × 49 × 14.4 mm) to hold real components on a
  single floor layer (LiPo + ESP32 + charge module) — see above. The pillow
  edges keep it looking watch-like despite the height.
- **Flat rectangular glass face** instead of the reference's round front boss:
  the SH1106 is rectangular, so the face is a recessed rounded-rect "screen" with
  the OLED window in it. Switch to a round GC9A01 display for a fully round face
  (needs a firmware driver change away from U8G2/SH1106).
- **Crown is cosmetic**; the rectangular side hole is the functional button
  (wire it to EN/reset or a GPIO as you like).
