# CAD Versions

All dimensions are in millimeters.

## v4_comfy

Recommended current version.

- `esp32c3_oled_case_comfy_layout.3mf` - print-ready layout with case, lid, and glove mount separated
- `esp32c3_oled_case_comfy_assembly.3mf` - assembled preview
- `esp32c3_oled_case_comfy.scad` - editable OpenSCAD source
- `*_case.stl`, `*_lid.stl`, `*_mount.stl` - individual printable parts

A usability pass over `v3_usable` aimed at being easier to print, open, wear, and
service:

- **One `fit` knob** at the top of the .scad feeds every press/slide clearance
  (lid lip, case-in-mount rails, snap latch). Print a test case + lid, then nudge
  `fit` by 0.05 mm if anything binds or feels loose. Default is 0.10 mm.
- **Easier to open:** `lid_screw_count = 2` keeps only the diagonal screw pair
  (the alignment tabs and rear snap latch hold the other corners); set it to 4 to
  fully lock. A fingernail pry scoop (`lid_pry_recess`) on the front lid edge pops
  the lid by hand.
- **Smaller / comfier:** the glove mount plate is slimmer (62 x 48 x 2.35 mm vs
  68 x 52 x 2.7) with softer 10 mm corners for a lower profile on the hand.
- **Port/button access:** a `tool_hole` pin port through the lid reaches the
  board's BOOT/RESET button without opening the case. It defaults over the front
  of the board, clear of the OLED — verify `tool_hole_x/_y` against your module,
  or set `tool_hole = false` to remove it.

All four behaviours are parameters at the top of the source, so anything can be
dialled back to the v3 geometry.

## v3_usable

Previous recommended version.

- `esp32c3_oled_case_usable_layout.3mf` - print-ready layout with case, lid, and glove mount separated
- `esp32c3_oled_case_usable_assembly.3mf` - assembled preview
- `esp32c3_oled_case_usable.scad` - editable OpenSCAD source
- `*_case.stl`, `*_lid.stl`, `*_mount.stl` - individual printable parts

This version is a usability pass over `v2_masterpiece`: the case is slightly smaller, USB-C and wire exits have more clearance, the OLED lid pass-through is set for a top-center display connector, the glove mount plate is shorter and lower, screw bosses have support ribs, and the rear snap latch has a larger tactile release area. The two M2 mount screws can still be used as safety retainers for hard movement.

## v2_masterpiece

Older version.

- `esp32c3_oled_case_masterpiece_layout.3mf` - print-ready layout with case, lid, and glove mount separated
- `esp32c3_oled_case_masterpiece_assembly.3mf` - assembled preview
- `esp32c3_oled_case_masterpiece.scad` - editable OpenSCAD source
- `*_case.stl`, `*_lid.stl`, `*_mount.stl` - individual printable parts

This version includes a quick-lock glove mount: two front stops leave the USB-C port clear, and a rear snap latch engages a notch in the case.

## v1_oled_case

Earlier ESP32-C3 + OLED glove mount version.

- `esp32c3_oled_case_layout.3mf` - print-ready layout
- `esp32c3_oled_case_assembly.3mf` - assembled preview
- `esp32c3_oled_case.scad` - editable OpenSCAD source

## v0_xglove

Original wrist case version.

- `xglove.scad` - editable OpenSCAD source
- `xglove.3mf` - exported model

## legacy_wrist_3mf

Older exported 3MF files kept for reference only.
