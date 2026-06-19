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
W          = 43;           // width  (X)  — ref 43.4
L          = 49;           // length (Y)  — ref 49.0, the wrist proximal-distal axis
case_h     = 21;           // thickness (Z). Ref is 14.4 (a solid styling shell);
                           // raised to actually hold 502535 + ESP32-C3 + 1.3"
                           // OLED. The pillow form below makes it *read* thinner
                           // than a 21mm box. Drop to ~16 with 302030 + 0.96".
corner_r   = 10;           // squircle corner radius (ref ~10)

// Pillow profile (this is the redesign — replaces the old flat-top box):
// the body is a squircle prism Minkowski-rounded by a sphere, so every edge
// (top, back, and the vertical corners) is filleted into one smooth "pillow"
// like the reference — with flat vertical side walls (clean, uniform shell).
edge_r     = 4.0;          // 3D fillet radius on all outer edges (the pillow)
lid_cap    = 6.0;          // height of the top cap = the lid (carries the glass)

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

// MPU6050 GY-521 ~21 x 16 x 3.
mpu_w = 21; mpu_l = 16; mpu_t = 3;

// 502535 LiPo: 5.0 thick x 25 x 35 (+ wrap/clearance).
batt_w = 26; batt_l = 36; batt_t = 6;

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
            apple_lugs_add();
        }

        apple_lugs_cut();      // spring-bar channel + concave band seat

        // boss pilot holes
        corner_centres()
            translate([0,0,floor_th+0.6]) cylinder(d=screw_pilot, h=case_h);

        // USB-C slot on +X wall (ESP32 short end)
        translate([W/2, -L*0.18, floor_th+esp_t/2+1])
            rotate([0,90,0]) cube([usb_h, usb_w, wall*3], center=true);

        // tactile button hole on +X wall
        translate([W/2, L*0.05, case_h*0.38])
            rotate([0,90,0]) cylinder(d=btn_d, h=wall*3, center=true);

        // flex ribbon slot on -Y wall (palm side), at floor level
        translate([0, -L/2, floor_th+ribbon_h/2])
            cube([ribbon_w, wall*3, ribbon_h], center=true);
    }
}

// OLED support ledges so the glass sits just under the window
oled_glass_top = parting_z - 0.5;             // ~touching the lid underside
oled_pcb_z     = oled_glass_top - oled_stack - oled_pcb_t;
module oled_ledge() {
    for (sx=[-1,1], sy=[-1,1])
        translate([sx*(oled_pcb_w/2-2), sy*(oled_pcb_l/2-2), floor_th])
            cylinder(d=4, h=oled_pcb_z-floor_th);
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
    color([0.1,0.4,0.8,0.5]) translate([-L*0.0,-L*0.18,floor_th+esp_t/2]) cube([esp_w,esp_l,esp_t], center=true);
    color([0.1,0.6,0.3,0.5]) translate([W*0.15,L*0.22,floor_th+mpu_t/2]) cube([mpu_w,mpu_l,mpu_t], center=true);
    color([0.7,0.5,0.1,0.5]) translate([0,0,floor_th+batt_t/2]) cube([batt_w,batt_l,batt_t], center=true);
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
