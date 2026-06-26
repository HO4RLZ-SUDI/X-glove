// ============================================================================
// Glove wrist unit — watch-style enclosure
// Reference: ~/Downloads/frame.stl (Apple-Watch-like squircle, 43 x 49 x 14.4mm,
// round face boss + side crown/button). This keeps that silhouette but makes a
// functional two-part case for the glove electronics:
//   - SH1106 1.3" 128x64 OLED (U8G2 driver in firmware)
//   - ESP32-C3 SuperMini   (USB-C on a side wall for charge/flash)
//   - MPU6050 (GY-521)     (rigidly mounted so accel/gyro tracks the wrist)
//   - 502535 LiPo (~5 x 25 x 35 mm)
//   - flex-sensor ribbon exits the palm-side (-Y) end
//
// The reference is 14.4mm thick (a solid styling shell). A real 1.3" OLED + MCU
// + battery stack needs ~20mm, so footprint matches the ref and thickness is a
// parameter (case_h). Drop a 0.96" OLED / thinner battery to slim it down.
//
// Render:  openscad -o base.stl -D 'part="base"' watch_case.scad
//          openscad -o lid.stl  -D 'part="lid"'  watch_case.scad
// ============================================================================

part = "preview";          // "base" | "lid" | "preview" | "section"
$fn = 64;

// ---- Outer shell (squircle "pillow" form, like the reference) -------------
W          = 48;           // width  (X)  — grown from ref 43.4 to floor the LiPo
                           // compartment (-X) beside the ESP32 + charge module (+X)
L          = 55;           // length (Y)  — grown from ref 49.0 so the +X column fits
                           // the ESP32 and the Type-C charge module end-to-end
case_h     = 23;           // thickness (Z). Ref is 14.4 (a solid styling shell);
                           // raised to hold the 6mm LiPo + ESP32-C3 + 1.3" OLED +
                           // a thin flex-divider perfboard stacked over the battery.
                           // The pillow form makes it read thinner than a box.
                           // Drop to ~16 with 302030 + 0.96" and no perfboard.
corner_r   = 10;           // squircle corner radius (ref ~10)

// Pillow profile (this is the redesign — replaces the old flat-top box):
// the body is a squircle prism Minkowski-rounded by a sphere, so every edge
// (top, back, and the vertical corners) is filleted into one smooth "pillow"
// like the reference — with flat vertical side walls (clean, uniform shell).
edge_r     = 4.0;          // 3D fillet radius on all outer edges (the pillow)
lid_cap    = 5.0;          // height of the top cap = the lid (carries the glass)

wall       = 2.4;          // side wall thickness (thinnest at mid; thicker at top)
floor_th   = 2.0;          // base floor
lid_th     = 2.0;          // lid solid-plate thickness under the glass
fit_gap    = 0.25;         // print clearance between lid and base rebate

// The lid is the whole domed cap; base is the tub up to here.
parting_z  = case_h - lid_cap;

// ---- Components (edit to your exact parts) --------------------------------
// SH1106 1.3" module: board ~35x33, active area ~29x15, glass+parts ~3mm tall.
oled_pcb_w = 35; oled_pcb_l = 33; oled_pcb_t = 1.6;
oled_act_w = 29; oled_act_l = 15.5;   // visible glass -> window size + margin
oled_stack = 3.2;                     // glass/ribbon height above the PCB

// ESP32-C3 SuperMini ~22.5 x 18 x 6 (with parts), USB-C on a short end.
esp_w = 18; esp_l = 22.5; esp_t = 6;
usb_w = 9.2; usb_h = 3.6;             // USB-C cutout in the side wall
esp_rib = 1.4;                        // retaining-rail thickness around the board

// Type-C charge module (TP4056-USB-C class) ~26 x 17 x 5, USB-C on a short end.
// The LiPo is already wired to this module; it charges through the case wall.
chg_w = 17; chg_l = 26; chg_t = 5;
chg_rib = 1.4;
chgusb_w = 9.2; chgusb_h = 3.6;       // USB-C cutout for the charge module

// MPU6050 GY-521 ~21 x 16 x 3. Mounted 16(X) x 21(Y) on a shelf over the charge
// module (floor is full); still rigid to the case so it tracks the wrist.
mpu_w = 16; mpu_l = 21; mpu_t = 3;

// LiPo pouch — user-specified compartment 20(W) x 40(L) x 6(H) mm.
batt_w = 20; batt_l = 40; batt_t = 6;
batt_rib  = 1.6;           // retaining-wall thickness around the LiPo pocket
batt_clr  = 0.4;           // slip clearance so the cell drops in
// Pocket hugs the -X inner wall; +X column holds the ESP32 + charge module.
batt_cx   = -(W/2 - wall - batt_rib - batt_w/2 - batt_clr);

// ---- Floor layout (everything single-layer; W/L grown to make it fit) ------
// -X column = battery (batt_cx, above). +X column = ESP32 (-Y end) + charge (+Y).
esp_cx = batt_cx + batt_w/2 + batt_clr + batt_rib + 1 + esp_w/2;  // +X column centre
esp_cy = -(L/2 - wall - esp_l/2 - 0.5);                            // -Y end
chg_cx = esp_cx;
chg_cy =  (L/2 - wall - chg_l/2 - 0.5);                            // +Y end
mpu_z  = floor_th + chg_t + 0.6;                                   // MPU shelf over charge

// ---- Flex-divider junction board (the "breadboard") -----------------------
// Holds the 5 flex pull-down resistors + the ribbon/I2C/ESP junction. A real
// SYB-170 mini-breadboard is 8.5mm tall and will NOT fit under the 1.3" OLED
// here; cut it (or use perfboard) to ~22 x 40 and keep it THIN. It rests flat on
// the battery compartment walls (no posts — they fouled the ESP32 slot), under
// the OLED.  8 columns of SYB-170 = 5 resistor cols + 3 bus cols.
bb_w   = 22; bb_l = 40;    // cut board footprint (X x Y) — sits over the battery
bb_t   = 2.0;              // board thickness (perfboard ~1.6-2; breadboard 8.5)
bb_z    = floor_th + batt_t;   // rests on the battery wall top (~8mm); board top
                               // ~10mm stays under the 1.3" OLED PCB (~11.7mm)

// ---- Side features (crown + button, like the reference) -------------------
crown_d = 6;  crown_len = 3.5;        // cosmetic crown on +X side
btn_d   = 3.6;                        // tactile button access hole on +X side

// ---- Wrist band: Apple-Watch-style lugs -----------------------------------
// Two integrated lugs per end that hug the body like an Apple Watch, with a
// through quick-release spring-bar channel. Use an Apple-Watch band that has
// quick-release pins, or a cheap "Apple Watch -> spring bar" adapter for Apple's
// first-party slide-lock bands. Tune band_w / bar_z to your physical band.
aw_large   = true;                    // 42/44/45/49 band (true) vs 38/40/41 (false)
band_w     = aw_large ? 24.0 : 20.0;  // connector width between the lugs [match band]
band_gap   = band_w + 0.6;            // print clearance so the band slips in
bar_d      = 1.9;                     // quick-release / spring-bar hole dia
bar_z      = 6.0;                     // bar height above the back face
lug_ear    = 3.0;                     // lug thickness (X) outside the band gap
lug_reach  = 5.0;                     // how far the lugs extend past the body (Y)
lug_tall   = 10.5;                    // lug height (Z)
seat_r     = 26;                      // concave seat radius so the band wraps in

// ---- Flex ribbon entry (palm side, -Y) ------------------------------------
ribbon_w = 11; ribbon_h = 3.0;

// ---- Screw bosses ---------------------------------------------------------
screw_pilot = 1.5;         // M2 self-tap pilot
screw_head  = 3.8;         // countersink dia in lid
boss_d      = 5.0;
boss_inset  = 6;           // corner boss offset from the corners

// ===========================================================================
// Helpers
// ===========================================================================
module rrect(w, d, r) {
    hull() for (sx = [-1, 1], sy = [-1, 1])
        translate([sx * (w/2 - r), sy * (d/2 - r)]) circle(r);
}

// Solid outer body — the reference's "pillow": a squircle prism rounded on
// every edge by a sphere (Minkowski). Footprint stays W x L, height case_h,
// vertical corner radius stays corner_r; only the edges get the edge_r fillet.
// Flat vertical side walls keep the shell a uniform thickness and let the crown
// pass through cleanly. A small sphere $fn keeps the Minkowski fast.
module body_solid() {
    minkowski() {
        translate([0,0,edge_r])
            linear_extrude(case_h - 2*edge_r)
                rrect(W - 2*edge_r, L - 2*edge_r, max(corner_r - edge_r, 0.5));
        sphere(r = edge_r, $fn = 28);
    }
}

// Inner cavity (open top). Leaves floor_th at the bottom.
module cavity() {
    translate([0,0,floor_th])
        linear_extrude(case_h) rrect(W-2*wall, L-2*wall, max(corner_r-wall,1));
}

lip_h  = 3;          // alignment lip height on the lid
lip_t  = 1.4;        // lip wall thickness
cav_w  = W-2*wall;   // inner cavity width
cav_l  = L-2*wall;   // inner cavity length

// Corner boss centres
boss_x = W/2 - boss_inset;
boss_y = L/2 - boss_inset;
module corner_centres() {
    for (sx=[-1,1], sy=[-1,1]) translate([sx*boss_x, sy*boss_y, 0]) children();
}

// ===========================================================================
// Base (tub holding the electronics)
// ===========================================================================
// (parting_z is defined up top — the lid is the whole domed cap.)

// Apple-Watch-style lugs: a pair of ears at each ±Y end, hugging the body, with
// a through spring-bar channel that a quick-release band clips into.
lug_tip_y = L/2 - 1 + lug_reach;          // Y of the bar axis (per +Y end)
module apple_lugs_add() {
    for (sy=[-1,1], sx=[-1,1])
        translate([sx*(band_gap/2 + lug_ear/2), 0, 0])
            hull() {
                // rounded tip carrying the spring-bar
                translate([0, sy*lug_tip_y, bar_z])
                    rotate([0,90,0]) cylinder(d=lug_tall*0.72, h=lug_ear, center=true);
                // root blended into the body wall
                translate([0, sy*(L/2-2), lug_tall/2]) cube([lug_ear, 1, lug_tall], center=true);
                translate([0, sy*(L/2-2), 0.5])        cube([lug_ear, 1, 1], center=true);
            }
}
module apple_lugs_cut() {
    for (sy=[-1,1]) {
        // through spring-bar channel (open across the gap, into both ears)
        translate([0, sy*lug_tip_y, bar_z])
            rotate([0,90,0]) cylinder(d=bar_d, h=band_gap+2*lug_ear+2, center=true);
        // shave the inner band gap into a gentle concave seat so the band wraps
        // in toward the wrist (cut from outside the lugs only, not the body)
        translate([0, sy*(L/2 + lug_reach + seat_r - 0.6), bar_z])
            rotate([0,90,0]) cylinder(r=seat_r, h=band_gap-0.2, center=true);
    }
}

// Battery compartment: a low retaining wall around the 20x40x6 LiPo, open top,
// with a finger-scoop on the +Y end so the cell lifts out. Rounded outer corners
// keep it clean (minimal/modern) and fused to the floor.
module battery_bay() {
    pw = batt_w + 2*batt_clr;
    pl = batt_l + 2*batt_clr;
    translate([batt_cx, 0, floor_th])
        difference() {
            linear_extrude(batt_t)
                offset(r = batt_rib) square([pw, pl], center = true);
            translate([0, 0, -0.5])
                linear_extrude(batt_t + 1) square([pw, pl], center = true);
            // scoop on the +Y wall to thumb the battery out
            translate([0, pl/2 + 0.5, batt_t])
                rotate([0, 90, 0])
                    cylinder(d = batt_t*1.5, h = pw*0.6, center = true, $fn = 28);
        }
}

// Generic low-walled board pocket / retaining rail. The USB-C opening is cut
// through the case wall separately in base(); outer ribs that reach the shell
// just fuse to it. Used for the ESP32 board slot and the charge-module bay.
module walled_pocket(cx, cy, bw, bl, h, rib, clr = 0.4) {
    pw = bw + 2*clr; pl = bl + 2*clr;
    translate([cx, cy, floor_th])
        difference() {
            linear_extrude(h) offset(r = rib) square([pw, pl], center = true);
            translate([0, 0, -0.5]) linear_extrude(h + 1) square([pw, pl], center = true);
        }
}

module base() {
    difference() {
        union() {
            // body, hollowed, keep only up to the parting plane
            difference() {
                intersection() {
                    body_solid();
                    translate([0,0,-1]) linear_extrude(parting_z+1) rrect(W+8, L+8, corner_r+4);
                }
                cavity();
            }
            // corner bosses for the lid screws
            corner_centres()
                translate([0,0,floor_th]) cylinder(d=boss_d, h=parting_z-floor_th);
            // crown stub on +X (cosmetic, like the reference). Rooted inside the
            // flat side wall so it stays fused; pokes ~crown_len proud.
            translate([W/2 - 1.5, L*0.18, case_h*0.50])
                rotate([0,90,0]) cylinder(d=crown_d, h=crown_len + 1.5);
            oled_ledge();      // shelves the OLED just under the window
            battery_bay();     // 20x40x6 LiPo compartment (-X side); its walls
                               // also carry the flex-divider board (no posts)
            walled_pocket(esp_cx, esp_cy, esp_w, esp_l, 3, esp_rib); // ESP32 slot
            walled_pocket(chg_cx, chg_cy, chg_w, chg_l, 3, chg_rib); // charge bay
            apple_lugs_add();
        }

        apple_lugs_cut();      // spring-bar channel + concave band seat

        // boss pilot holes
        corner_centres()
            translate([0,0,floor_th+0.6]) cylinder(d=screw_pilot, h=case_h);

        // ESP32 USB-C slot on -Y wall (board short end; offset +X from the ribbon)
        translate([esp_cx, -L/2, floor_th+esp_t/2])
            cube([usb_w, wall*3, usb_h], center=true);

        // charge-module USB-C slot on +Y wall (the LiPo charges through here)
        translate([chg_cx, L/2, floor_th+chg_t/2])
            cube([chgusb_w, wall*3, chgusb_h], center=true);

        // tactile button hole on +X wall
        translate([W/2, L*0.05, case_h*0.38])
            rotate([0,90,0]) cylinder(d=btn_d, h=wall*3, center=true);

        // flex ribbon slot on -Y wall (palm side, centre), at floor level
        translate([0, -L/2, floor_th+ribbon_h/2])
            cube([ribbon_w, wall*3, ribbon_h], center=true);
    }
}

// OLED support so the glass sits just under the window. Instead of four floor
// posts (which fouled the ESP32 / charge-module slots), the OLED rests on four
// short tabs cantilevered from the +-X side walls, up at oled_pcb_z — the whole
// floor below stays clear for the boards.
oled_glass_top = parting_z - 0.5;             // ~touching the lid underside
oled_pcb_z     = oled_glass_top - oled_stack - oled_pcb_t;
oled_tab_th    = 1.5;                          // tab thickness (Z)
module oled_ledge() {
    inx = oled_pcb_w/2 - 1;                    // X where the OLED edge lands
    xwall = W/2 - wall;
    for (sx=[-1,1], sy=[-1,1])
        translate([sx*(inx + (xwall-inx)/2), sy*(oled_pcb_l/2-3), oled_pcb_z-oled_tab_th])
            cube([(xwall-inx)+1, 8, oled_tab_th], center=true);
}

// ===========================================================================
// Lid = the whole domed top cap (outer profile always matches the body), with a
// downward alignment lip, a flat rounded-rect "glass" face recessed into the
// dome (Apple-Watch style — no more cosmetic round ring), and the OLED window.
// ===========================================================================
glass_w     = W - 2*edge_r - 2;  // glass face sits inside the flat top (X)
glass_l     = L - 2*edge_r - 2;  //                                     (Y)
glass_depth = 1.2;               // how deep the glass area is recessed in the top
glass_r     = corner_r - edge_r; // glass corner radius (follows the squircle)
module lid() {
    difference() {
        union() {
            // top cap = the domed body above the parting plane
            intersection() {
                body_solid();
                translate([0,0,parting_z]) linear_extrude(case_h) rrect(W+8, L+8, corner_r+4);
            }
            // downward lip that plugs into the cavity for alignment
            translate([0,0,parting_z-lip_h])
                linear_extrude(lip_h)
                    difference() {
                        rrect(cav_w-2*fit_gap,        cav_l-2*fit_gap,        corner_r-wall);
                        rrect(cav_w-2*fit_gap-2*lip_t, cav_l-2*fit_gap-2*lip_t, corner_r-wall-lip_t);
                    }
        }
        // flat recessed glass face cut into the dome (the "screen" of the watch)
        translate([0,0,case_h-glass_depth])
            linear_extrude(glass_depth+1) rrect(glass_w, glass_l, glass_r);
        // rectangular OLED window (active area + margin), through to the cavity
        translate([0,0,parting_z-lip_h-1])
            linear_extrude(case_h) rrect(oled_act_w+2, oled_act_l+2, 2);
        // countersunk screw holes
        corner_centres() {
            translate([0,0,parting_z-lip_h-1]) cylinder(d=screw_pilot+0.8, h=case_h);
            translate([0,0,case_h-1.0])        cylinder(d=screw_head, h=3);
        }
    }
}

// ===========================================================================
// Output
// ===========================================================================
module ghost_parts() {
    // translucent component placeholders for the preview
    color([0.2,0.2,0.2,0.5]) translate([0,0,oled_pcb_z]) cube([oled_pcb_w,oled_pcb_l,oled_pcb_t], center=true);
    // ESP32-C3 in its slot (-Y end of the +X column), USB-C to the -Y wall
    color([0.1,0.4,0.8,0.5]) translate([esp_cx,esp_cy,floor_th+esp_t/2]) cube([esp_w,esp_l,esp_t], center=true);
    // Type-C charge module (+Y end of the +X column), USB-C to the +Y wall
    color([0.8,0.2,0.2,0.5]) translate([chg_cx,chg_cy,floor_th+chg_t/2]) cube([chg_w,chg_l,chg_t], center=true);
    // MPU6050 on the charge module
    color([0.1,0.6,0.3,0.5]) translate([chg_cx,chg_cy,mpu_z+mpu_t/2]) cube([mpu_w,mpu_l,mpu_t], center=true);
    // LiPo in its -X compartment
    color([0.7,0.5,0.1,0.5]) translate([batt_cx,0,floor_th+batt_t/2]) cube([batt_w,batt_l,batt_t], center=true);
    // thin flex-divider board stacked over the battery
    color([0.15,0.5,0.15,0.6]) translate([batt_cx,0,bb_z+bb_t/2]) cube([bb_w,bb_l,bb_t], center=true);
}

if (part == "base")    base();
else if (part == "lid") lid();
else if (part == "section") {
    // assembled, cut on the Y=0 plane (keep +Y half) so the lid seating and the
    // component stack are both visible
    difference() {
        union() { base(); color([0.8,0.8,0.85]) lid(); %ghost_parts(); }
        translate([-100,-200,-2]) cube([200,200,400]);
    }
}
else {                 // preview: lid lifted off + ghost parts
    base();
    color([0.8,0.8,0.85,0.85]) translate([0,0,14]) lid();
    %ghost_parts();
}
