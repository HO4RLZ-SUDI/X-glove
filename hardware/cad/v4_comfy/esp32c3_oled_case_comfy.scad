$fn = 112;

// ESP32-C3 + OLED glove enclosure - comfy edition (v4)
// Units: millimeters
//
// Usability pass over v3_usable. Four goals, all driven by named knobs below:
//   1. Easier to print/assemble  -> "fit" master clearance (bump after a test print)
//   2. Easier to open            -> lid_screw_count + lid_pry_recess
//   3. Smaller / comfier to wear  -> slimmer mount plate, softer corners
//   4. Port/button access         -> tool_hole poke port on the lid
//
// SAFE TUNING: print one case + lid, check the fit, then nudge `fit` by 0.05 mm
// at a time. Every mating clearance references `fit`, so one number tunes all.

// ---------- Printable output ----------
part = "all";          // "all", "case", "lid", "mount", "assembly"
print_layout = true;   // true: lay lid upside-down for printing

// ---------- Master print-fit clearance ----------
// Added on top of the lid lip and case-in-mount slide fits. 0.0 = identical to
// v3. Most FDM printers are happiest at 0.08-0.15. Raise if parts bind.
fit = 0.10;

// ---------- Hardware ----------
board_L = 24.0;        // ESP32-C3 board length, 2.4 cm
board_W = 18.0;        // ESP32-C3 board width, 1.8 cm
board_clearance = 0.7;

oled_L = 34.0;         // OLED module length, 3.4 cm
oled_W = 21.0;         // OLED module width, 2.1 cm
oled_clearance = 0.7;
oled_connector_position = "top_center"; // "top_center", "right", "left"

// ---------- Case ----------
L = 54.0;
W = 39.0;
case_H = 14.2;
lid_H = 3.2;
wall = 2.25;
floor_H = 2.0;
gap = 9.0;

corner_r = 5.4;
inner_r = 3.1;
body_bottom_chamfer = 0.65;
body_top_chamfer = 0.90;
body_face_inset = 0.70;
lid_bottom_chamfer = 0.45;
lid_top_chamfer = 0.65;
lid_face_inset = 0.50;
skin = 0.04;

// ---------- USB-C opening ----------
// Opening is on the LEFT short (wide) wall of the case (x = 0), centered on the
// ESP32-C3 port. The wire exit sits on the opposite short wall (x = L).
usb_slot_w = 16.2;
usb_slot_h = 6.4;
usb_slot_z = floor_H + 0.65;
usb_slot_r = 1.55;
usb_mouth_extra = 1.6;

// ---------- External wire exit ----------
wire_exit_w = 8.6;
wire_exit_h = 5.6;
wire_exit_z = floor_H + 3.85;
wire_exit_r = 2.0;

// ---------- OLED viewing window and pass-through ----------
// The lid now carries a real through-hole window in the centre so the display
// shows through. The module body seats up into a footprint recess on the
// underside and is captured by a thin retaining ledge at the top; the glass is
// visible through the narrower viewing window cut through that ledge.
oled_ledge = 1.8;      // top lip that overlaps the module edge to retain it
oled_ledge_T = 1.0;    // thickness of solid lid left at the top as that lip
oled_window_r = 1.4;   // corner radius of the through viewing window
oled_pocket_r = 1.6;
oled_wire_slot_L = 12.0;
oled_wire_slot_W = 5.5;
oled_wire_slot_edge_gap = 1.8;
oled_wire_slot_r = 1.1;
oled_guard_T = 1.35;
oled_guard_H = 0.75;
oled_guard_gap = 0.50;

// ---------- Lid screws ----------
screw_inset = 5.4;
boss_d = 5.2;
pilot_d = 1.65;        // M2 self-tapping pilot
lid_screw_d = 2.35;
screw_head_d = 4.8;
screw_head_H = 1.25;
boss_top_gap = 0.45;
boss_embed = 0.18;
boss_rib_T = 1.10;
boss_rib_H = 3.15;

// ---------- Lid screws ----------
// Daily access is easier with fewer screws. 2 = diagonal pair (recommended for
// a glove: the alignment tabs + snap latch hold the other corners). 4 = locked.
lid_screw_count = 2;   // 2 or 4

// ---------- Lid alignment tabs ----------
lip_H = 1.65;
lip_clearance = 0.38 + fit;
lip_tab_T = 1.15;
lip_tab_L = 12.5;

// ---------- Lid pry recess ----------
// Scalloped fingernail notch on the front lid edge so the lid pops off by hand.
lid_pry_recess = true;
lid_pry_w = 14.0;
lid_pry_d = 2.6;       // how far it bites into the lid edge
lid_pry_r = 3.2;

// ---------- Lid tool / button poke port ----------
// Pin hole through the lid to reach the board's BOOT/RESET button without
// opening the case. Default sits over the FRONT of the board, clear of the
// OLED pocket. The OLED covers the rear of the board, so if your button is
// back there, move the OLED or poke from the side instead. Always verify
// tool_hole_x / tool_hole_y against your specific ESP32-C3 module.
tool_hole = true;
tool_hole_d = 3.2;
tool_hole_x = 0;       // offset from board centre line (+y), along the USB wall
tool_hole_y = 3.5;     // distance from the board USB edge toward the rear (+x)

// ---------- Board tray ----------
tray_H = 0.75;
tray_embed = 0.16;
rail_W = 1.0;
rail_H = 1.35;
rail_overlap = 0.15;
front_usb_gap = 11.0;  // gap in the USB-side rail for the USB-C connector (on the 18 mm edge, now along Y)
clip_L = 4.6;
clip_H = 0.55;
clip_overlap = 0.12;

// ---------- Glove mount ----------
// Slimmer + smaller footprint than v3 (was 68 x 52 x 2.7) for a lower, comfier
// profile on the back of the hand. Still fully houses the case + locator rails.
mount_L = 62.0;
mount_W = 48.0;
mount_H = 2.10;        // thinner plate: flexes to the wrist and lower profile
mount_r = 12.0;        // larger corner radius for a softer outline
mount_bottom_chamfer = 1.05;  // big skin-side chamfer so the underside edge isn't sharp
mount_top_chamfer = 0.65;
mount_face_inset = 0.45;
mount_x = (L - mount_L) / 2;
mount_y = (W - mount_W) / 2;

velcro_w = 22.0;
velcro_clearance = 2.6;
velcro_slot_L = velcro_w + velcro_clearance;
velcro_slot_W = 5.4;
velcro_slot_r = 1.45;
velcro_slot_edge = 8.5;
velcro_channel_depth = 1.00;
velcro_channel_clearance = 2.0;

glove_sew_slot_L = 11.5;
glove_sew_slot_W = 3.4;
glove_sew_slot_r = 1.25;
glove_sew_slot_x = 4.6;
glove_sew_slot_y_offset = 10.5;

glove_lace_hole_d = 2.2;
glove_lace_edge_x = 15.0;
glove_lace_edge_y = 5.5;

cable_tie_slot_L = 11.5;
cable_tie_slot_W = 2.8;
cable_tie_slot_r = 1.1;
cable_tie_slot_x = mount_L - 12.0;
cable_tie_slot_y_offset = 4.6;

locator_rail_T = 1.00;
locator_rail_H = 1.10;          // taller rails grip more of the case side wall
locator_clearance = 0.65 + fit; // tighter nest so the case seats with less play

case_on_mount_x = (mount_L - L) / 2;
case_on_mount_y = (mount_W - W) / 2;

front_stop_L = 8.0;
front_stop_T = 1.8;
front_stop_H = 1.45;
front_stop_side_gap = 7.0;

lock_tab_W = 20.0;
lock_tab_T = 2.0;
lock_tab_H = 6.8;
lock_tab_back_gap = 0.55 + fit;
lock_tooth_W = 13.0;
lock_tooth_T = 1.55;   // deeper snap tooth so the rear latch grabs the case harder
lock_tooth_H = 1.40;
lock_tooth_z = 3.80;
lock_notch_W = 14.8;
lock_notch_H = 3.2;
lock_notch_z = 2.85;
lock_notch_r = 1.20;
latch_grip_W = 25.0;
latch_grip_T = 1.15;
latch_grip_H = 4.0;
latch_rib_W = 1.05;
latch_rib_T = 0.70;
latch_rib_H = 3.45;
latch_rib_gap = 5.6;
rear_finger_recess_W = 18.0;
rear_finger_recess_D = 5.4;

mount_fastener_x_offset = 13.0;
mount_fastener_y = W - wall - 5.4;
mount_fastener_boss_d = 6.2;
mount_fastener_boss_H = 3.7;
mount_fastener_pilot_d = 1.65;
mount_fastener_clearance_d = 2.35;
mount_fastener_head_d = 4.9;
mount_fastener_head_H = 1.2;

// USB-C is on the board's WIDTH (18 mm) edge. That edge now faces the LEFT
// short wall (x = 0), so the board LENGTH runs along X (into the case from the
// USB wall) and the board WIDTH runs along Y (parallel to the USB wall).
board_foot_L = board_L;   // board extent along X (into the case from the USB wall)
board_foot_W = board_W;   // board extent along Y (parallel to the USB wall)
board_x = wall + board_clearance;   // USB edge sits just inside the left wall
board_y = (W - board_foot_W) / 2;   // centered across the case width
oled_x = (L - oled_L) / 2;
oled_y = (W - oled_W) / 2;
oled_pocket_x = oled_x - oled_clearance;
oled_pocket_y = oled_y - oled_clearance;
oled_pocket_L = oled_L + oled_clearance * 2;
oled_pocket_W = oled_W + oled_clearance * 2;
oled_wire_slot_x = oled_connector_position == "top_center"
    ? oled_pocket_x + oled_pocket_L / 2 - oled_wire_slot_L / 2
    : (
        oled_connector_position == "right"
            ? oled_x + oled_L - oled_wire_slot_L - oled_wire_slot_edge_gap
            : oled_x + oled_wire_slot_edge_gap
    );
oled_wire_slot_y = oled_connector_position == "top_center"
    ? oled_y + oled_W - oled_wire_slot_W - oled_wire_slot_edge_gap
    : (W - oled_wire_slot_W) / 2;
mount_fastener_xs = [L / 2 - mount_fastener_x_offset, L / 2 + mount_fastener_x_offset];

module rounded_rect_2d(size=[10,10], r=2) {
    rr = max(0.01, min(r, min(size[0], size[1]) / 2 - 0.01));

    hull() {
        for (x=[rr, size[0] - rr])
        for (y=[rr, size[1] - rr])
            translate([x, y])
                circle(r=rr);
    }
}

module centered_profile(parent=[10,10], face=[8,8], r=1, z=0, h=skin) {
    translate([(parent[0] - face[0]) / 2, (parent[1] - face[1]) / 2, z])
        linear_extrude(h)
            rounded_rect_2d(face, r);
}

module rounded_prism(size=[10,10,10], r=2) {
    linear_extrude(size[2])
        rounded_rect_2d([size[0], size[1]], r);
}

module chamfered_rounded_box(size=[10,10,10], r=2, bottom=0.5, top=0.5, inset=0.4) {
    face = [
        max(0.1, size[0] - inset * 2),
        max(0.1, size[1] - inset * 2)
    ];
    face_r = max(0.1, r - inset);
    mid_H = max(0.01, size[2] - bottom - top);

    union() {
        hull() {
            centered_profile([size[0], size[1]], face, face_r, 0, skin);
            centered_profile([size[0], size[1]], [size[0], size[1]], r, bottom, skin);
        }

        translate([0, 0, bottom])
            rounded_prism([size[0], size[1], mid_H], r);

        hull() {
            centered_profile([size[0], size[1]], [size[0], size[1]], r, size[2] - top - skin, skin);
            centered_profile([size[0], size[1]], face, face_r, size[2] - skin, skin);
        }
    }
}

module rounded_slot_x(length=5, y_size=10, z_size=4, r=1) {
    rr = max(0.01, min(r, min(y_size, z_size) / 2 - 0.01));

    hull() {
        for (y=[rr, y_size - rr])
        for (z=[rr, z_size - rr])
            translate([0, y, z])
                rotate([0, 90, 0])
                    cylinder(h=length, r=rr);
    }
}

module rounded_slot_y(length=5, x_size=10, z_size=4, r=1) {
    rr = max(0.01, min(r, min(x_size, z_size) / 2 - 0.01));

    hull() {
        for (x=[rr, x_size - rr])
        for (z=[rr, z_size - rr])
            translate([x, 0, z])
                rotate([-90, 0, 0])
                    cylinder(h=length, r=rr);
    }
}

module screw_positions(z=0) {
    for (x=[screw_inset, L - screw_inset])
    for (y=[screw_inset, W - screw_inset])
        translate([x, y, z])
            children();
}

// Corners that actually get a fastener. lid_screw_count == 2 keeps the
// diagonal pair (front-left + rear-right); 4 keeps all corners.
module active_screw_positions(z=0) {
    pts = lid_screw_count == 2
        ? [[screw_inset, screw_inset], [L - screw_inset, W - screw_inset]]
        : [for (x=[screw_inset, L - screw_inset]) for (y=[screw_inset, W - screw_inset]) [x, y]];
    for (p = pts)
        translate([p[0], p[1], z])
            children();
}

module mount_fastener_positions(z=0) {
    for (x=mount_fastener_xs)
        translate([x, mount_fastener_y, z])
            children();
}

module inner_cavity_cutout() {
    translate([wall, wall, floor_H])
        rounded_prism([L - wall * 2, W - wall * 2, case_H - floor_H + 1.0], inner_r);
}

module usb_cutouts() {
    depth = wall + board_clearance + 1.1;

    // Slot through the LEFT short wall (x = 0), extruded along +X; usb_slot_w
    // now spans the case width (Y), centered on the board.
    translate([-0.70, W / 2 - usb_slot_w / 2, usb_slot_z])
        rounded_slot_x(depth + 0.70, usb_slot_w, usb_slot_h, usb_slot_r);

    translate([
        -0.75,
        W / 2 - (usb_slot_w + usb_mouth_extra * 2) / 2,
        usb_slot_z - usb_mouth_extra / 2
    ])
        rounded_slot_x(
            1.30,
            usb_slot_w + usb_mouth_extra * 2,
            usb_slot_h + usb_mouth_extra,
            usb_slot_r + 0.28
        );
}

module wire_exit_cutouts() {
    translate([L - wall - 0.80, W / 2 - wire_exit_w / 2, wire_exit_z])
        rounded_slot_x(wall + 1.60, wire_exit_w, wire_exit_h, wire_exit_r);

    translate([
        L - 0.70,
        W / 2 - (wire_exit_w + 1.60) / 2,
        wire_exit_z - 0.80
    ])
        rounded_slot_x(
            1.40,
            wire_exit_w + 1.60,
            wire_exit_h + 1.60,
            wire_exit_r + 0.35
        );
}

module rear_lock_notch_cutout() {
    translate([L / 2 - lock_notch_W / 2, W - wall - 0.35, lock_notch_z])
        rounded_slot_y(wall + 1.10, lock_notch_W, lock_notch_H, lock_notch_r);

    translate([
        L / 2 - (lock_notch_W + 2.0) / 2,
        W - 0.72,
        lock_notch_z - 0.45
    ])
        rounded_slot_y(1.35, lock_notch_W + 2.0, lock_notch_H + 0.90, lock_notch_r + 0.25);
}

module bottom_shell() {
    difference() {
        chamfered_rounded_box(
            [L, W, case_H],
            corner_r,
            body_bottom_chamfer,
            body_top_chamfer,
            body_face_inset
        );

        inner_cavity_cutout();
        usb_cutouts();
        wire_exit_cutouts();
        rear_lock_notch_cutout();
    }
}

module screw_bosses() {
    screw_positions(floor_H - boss_embed)
        cylinder(h=case_H - floor_H - boss_top_gap + boss_embed, d=boss_d);
}

module screw_boss_support_ribs() {
    z0 = floor_H - boss_embed;

    for (x=[screw_inset, L - screw_inset])
    for (y=[screw_inset, W - screw_inset]) {
        if (x < L / 2) {
            translate([wall - 0.05, y - boss_rib_T / 2, z0])
                cube([max(0.1, x - wall + 0.05), boss_rib_T, boss_rib_H], false);
        } else {
            translate([x, y - boss_rib_T / 2, z0])
                cube([max(0.1, L - wall - x + 0.05), boss_rib_T, boss_rib_H], false);
        }

        if (y < W / 2) {
            translate([x - boss_rib_T / 2, wall - 0.05, z0])
                cube([boss_rib_T, max(0.1, y - wall + 0.05), boss_rib_H], false);
        } else {
            translate([x - boss_rib_T / 2, y, z0])
                cube([boss_rib_T, max(0.1, W - wall - y + 0.05), boss_rib_H], false);
        }
    }
}

module bottom_screw_pilots() {
    active_screw_positions(floor_H + 0.35)
        cylinder(h=case_H - floor_H + 0.20, d=pilot_d);
}

module mount_screw_pads() {
    mount_fastener_positions(floor_H - boss_embed)
        cylinder(h=mount_fastener_boss_H + boss_embed, d=mount_fastener_boss_d);
}

module bottom_mount_screw_pilots() {
    mount_fastener_positions(-0.10)
        cylinder(h=floor_H + mount_fastener_boss_H + 0.20, d=mount_fastener_pilot_d);
}

module board_tray() {
    // Board support shelf.
    translate([board_x, board_y, floor_H - tray_embed])
        cube([board_foot_L, board_foot_W, tray_H + tray_embed], false);

    // Side rails (the board's long edges, running along X).
    translate([board_x, board_y - rail_W, floor_H - tray_embed])
        cube([board_foot_L, rail_W + rail_overlap, rail_H + tray_embed], false);

    translate([board_x, board_y + board_foot_W - rail_overlap, floor_H - tray_embed])
        cube([board_foot_L, rail_W + rail_overlap, rail_H + tray_embed], false);

    // Rear rail (the +X edge, opposite the USB connector).
    translate([board_x + board_foot_L - rail_overlap, board_y, floor_H - tray_embed])
        cube([rail_W + rail_overlap, board_foot_W, rail_H + tray_embed], false);

    // USB-side rail (the -X / left edge) is split so it does not block the USB-C connector.
    front_segment_W = (board_foot_W - front_usb_gap) / 2;
    if (front_segment_W > 0) {
        translate([board_x - rail_W, board_y, floor_H - tray_embed])
            cube([rail_W + rail_overlap, front_segment_W, rail_H + tray_embed], false);

        translate([board_x - rail_W, board_y + board_foot_W - front_segment_W, floor_H - tray_embed])
            cube([rail_W + rail_overlap, front_segment_W, rail_H + tray_embed], false);
    }

    // Light retaining clips at the rear edge (+X, opposite the USB connector).
    translate([board_x + board_foot_L - 0.25, board_y + 3.0, floor_H + rail_H - clip_overlap])
        cube([rail_W + 0.70, clip_L, clip_H + clip_overlap], false);

    translate([board_x + board_foot_L - 0.25, board_y + board_foot_W - 3.0 - clip_L, floor_H + rail_H - clip_overlap])
        cube([rail_W + 0.70, clip_L, clip_H + clip_overlap], false);
}

module bottom_case() {
    color([1.0, 0.76, 0.02])
    difference() {
        union() {
            bottom_shell();
            screw_bosses();
            screw_boss_support_ribs();
            mount_screw_pads();
            board_tray();
        }

        bottom_screw_pilots();
        bottom_mount_screw_pilots();
    }
}

module velcro_slot_cutouts() {
    for (y=[velcro_slot_edge, mount_W - velcro_slot_edge])
        translate([mount_L / 2 - velcro_slot_L / 2, y - velcro_slot_W / 2, -0.10])
            linear_extrude(mount_H + 0.20)
                rounded_rect_2d([velcro_slot_L, velcro_slot_W], velcro_slot_r);
}

module velcro_under_channel_cutout() {
    channel_w = velcro_slot_L + velcro_channel_clearance * 2;
    channel_l = mount_W - velcro_slot_edge * 2 + velcro_slot_W;

    translate([
        mount_L / 2 - channel_w / 2,
        velcro_slot_edge - velcro_slot_W / 2,
        -0.08
    ])
        linear_extrude(velcro_channel_depth + 0.08)
            rounded_rect_2d([channel_w, channel_l], velcro_slot_r + 0.45);
}

module glove_sew_slot_cutouts() {
    for (x=[glove_sew_slot_x, mount_L - glove_sew_slot_x])
    for (y=[mount_W / 2 - glove_sew_slot_y_offset, mount_W / 2 + glove_sew_slot_y_offset])
        translate([x - glove_sew_slot_W / 2, y - glove_sew_slot_L / 2, -0.10])
            linear_extrude(mount_H + 0.20)
                rounded_rect_2d([glove_sew_slot_W, glove_sew_slot_L], glove_sew_slot_r);
}

module mount_plate_screw_cutouts() {
    for (x=mount_fastener_xs) {
        lx = x - mount_x;
        ly = mount_fastener_y - mount_y;

        translate([lx, ly, -0.10])
            cylinder(h=mount_H + 0.20, d=mount_fastener_clearance_d);

        translate([lx, ly, -0.10])
            cylinder(h=mount_fastener_head_H + 0.10, d=mount_fastener_head_d);
    }
}

module glove_lace_hole_cutouts() {
    for (x=[glove_lace_edge_x, mount_L - glove_lace_edge_x])
    for (y=[glove_lace_edge_y, mount_W - glove_lace_edge_y])
        translate([x, y, -0.10])
            cylinder(h=mount_H + locator_rail_H + 0.30, d=glove_lace_hole_d);
}

module cable_tie_slot_cutouts() {
    for (y=[mount_W / 2 - cable_tie_slot_y_offset, mount_W / 2 + cable_tie_slot_y_offset])
        translate([
            cable_tie_slot_x - cable_tie_slot_W / 2,
            y - cable_tie_slot_L / 2,
            -0.10
        ])
            linear_extrude(mount_H + locator_rail_H + 0.30)
                rounded_rect_2d([cable_tie_slot_W, cable_tie_slot_L], cable_tie_slot_r);
}

module rear_finger_recess_cutout() {
    translate([
        mount_L / 2 - rear_finger_recess_W / 2,
        mount_W - rear_finger_recess_D + 0.10,
        -0.10
    ])
        linear_extrude(mount_H - 0.12)
            rounded_rect_2d([rear_finger_recess_W, rear_finger_recess_D + 0.25], 2.0);
}

module mount_case_locator_rails() {
    inner_L = L + locator_clearance * 2;
    inner_W = W + locator_clearance * 2;
    outer_L = inner_L + locator_rail_T * 2;
    outer_W = inner_W + locator_rail_T * 2;

    difference() {
        translate([
            mount_L / 2 - outer_L / 2,
            mount_W / 2 - outer_W / 2,
            mount_H - 0.06
        ])
            linear_extrude(locator_rail_H + 0.06)
                rounded_rect_2d([outer_L, outer_W], corner_r + locator_clearance + locator_rail_T);

        translate([
            mount_L / 2 - inner_L / 2,
            mount_W / 2 - inner_W / 2,
            mount_H - 0.12
        ])
            linear_extrude(locator_rail_H + 0.24)
                rounded_rect_2d([inner_L, inner_W], corner_r + locator_clearance);
    }
}

module front_case_stops() {
    y0 = case_on_mount_y - locator_clearance - front_stop_T + 0.15;
    z0 = mount_H - 0.05;

    for (x=[
        case_on_mount_x + front_stop_side_gap,
        case_on_mount_x + L - front_stop_side_gap - front_stop_L
    ])
        translate([x, y0, z0])
            rounded_prism([front_stop_L, front_stop_T, front_stop_H + 0.05], 0.65);
}

module rear_snap_latch() {
    tab_y = case_on_mount_y + W + locator_clearance + lock_tab_back_gap;
    tab_z = mount_H - 0.06;

    translate([mount_L / 2 - lock_tab_W / 2, tab_y, tab_z])
        rounded_prism([lock_tab_W, lock_tab_T, lock_tab_H + 0.06], 0.65);

    translate([
        mount_L / 2 - lock_tooth_W / 2,
        tab_y - lock_tooth_T + 0.08,
        mount_H + lock_tooth_z
    ])
        rounded_prism([lock_tooth_W, lock_tooth_T, lock_tooth_H], 0.35);

    translate([
        mount_L / 2 - latch_grip_W / 2,
        tab_y + lock_tab_T - 0.05,
        mount_H + 1.40
    ])
        rounded_prism([latch_grip_W, latch_grip_T, latch_grip_H], 0.55);

    for (x=[
        mount_L / 2 - latch_rib_gap,
        mount_L / 2,
        mount_L / 2 + latch_rib_gap
    ])
        translate([
            x - latch_rib_W / 2,
            tab_y + lock_tab_T + latch_grip_T - 0.22,
            mount_H + 1.75
        ])
            rounded_prism([latch_rib_W, latch_rib_T, latch_rib_H], 0.25);
}

module glove_mount_raw() {
    color([1.0, 0.76, 0.02])
    difference() {
        union() {
            chamfered_rounded_box(
                [mount_L, mount_W, mount_H],
                mount_r,
                mount_bottom_chamfer,
                mount_top_chamfer,
                mount_face_inset
            );
            mount_case_locator_rails();
            front_case_stops();
            rear_snap_latch();
        }

        velcro_under_channel_cutout();
        velcro_slot_cutouts();
        glove_sew_slot_cutouts();
        glove_lace_hole_cutouts();
        cable_tie_slot_cutouts();
        mount_plate_screw_cutouts();
        rear_finger_recess_cutout();
    }
}

module lid_alignment_tabs() {
    z0 = -lip_H;

    translate([L / 2 - lip_tab_L / 2, wall + lip_clearance, z0])
        cube([lip_tab_L, lip_tab_T, lip_H + skin], false);

    translate([L / 2 - lip_tab_L / 2, W - wall - lip_clearance - lip_tab_T, z0])
        cube([lip_tab_L, lip_tab_T, lip_H + skin], false);

    translate([wall + lip_clearance, W / 2 - lip_tab_L / 2, z0])
        cube([lip_tab_T, lip_tab_L, lip_H + skin], false);

    translate([L - wall - lip_clearance - lip_tab_T, W / 2 - lip_tab_L / 2, z0])
        cube([lip_tab_T, lip_tab_L, lip_H + skin], false);
}

module lid_screw_cutouts() {
    active_screw_positions(-lip_H - 0.15)
        cylinder(h=lid_H + lip_H + 0.40, d=lid_screw_d);

    active_screw_positions(lid_H - screw_head_H)
        cylinder(h=screw_head_H + 0.30, d=screw_head_d);

    active_screw_positions(lid_H - 0.18)
        cylinder(h=0.28, d=screw_head_d + 0.45);
}

module oled_window_cutout() {
    // Narrow viewing window cut all the way through the lid (the screen shows
    // through here).
    win_L = oled_pocket_L - oled_ledge * 2;
    win_W = oled_pocket_W - oled_ledge * 2;
    translate([oled_pocket_x + oled_ledge, oled_pocket_y + oled_ledge, -lip_H - 0.20])
        linear_extrude(lid_H + lip_H + 0.40)
            rounded_rect_2d([win_L, win_W], oled_window_r);

    // Footprint recess on the underside: the module body seats up into this and
    // the remaining oled_ledge_T-thick lip at the top holds it from falling out.
    translate([oled_pocket_x, oled_pocket_y, -lip_H - 0.20])
        linear_extrude(lid_H - oled_ledge_T + lip_H + 0.20)
            rounded_rect_2d([oled_pocket_L, oled_pocket_W], oled_pocket_r);
}

module oled_wire_pass_cutout() {
    translate([oled_wire_slot_x, oled_wire_slot_y, -lip_H - 0.15])
        linear_extrude(lid_H + lip_H + 0.40)
            rounded_rect_2d([oled_wire_slot_L, oled_wire_slot_W], oled_wire_slot_r);
}

module oled_guard_rail() {
    inner_L = oled_pocket_L + oled_guard_gap * 2;
    inner_W = oled_pocket_W + oled_guard_gap * 2;
    outer_L = inner_L + oled_guard_T * 2;
    outer_W = inner_W + oled_guard_T * 2;
    guard_r = oled_pocket_r + oled_guard_gap + oled_guard_T;

    difference() {
        translate([
            oled_pocket_x - oled_guard_gap - oled_guard_T,
            oled_pocket_y - oled_guard_gap - oled_guard_T,
            lid_H - 0.05
        ])
            linear_extrude(oled_guard_H + 0.05)
                rounded_rect_2d([outer_L, outer_W], guard_r);

        translate([
            oled_pocket_x - oled_guard_gap,
            oled_pocket_y - oled_guard_gap,
            lid_H - 0.12
        ])
            linear_extrude(oled_guard_H + 0.24)
                rounded_rect_2d([inner_L, inner_W], oled_pocket_r + oled_guard_gap);
    }
}

// Cylindrical fingernail scoop on the top-front edge of the lid. Depth is
// capped to lid_pry_d so it never breaks through into the case cavity.
module lid_pry_cutout() {
    translate([L / 2 - lid_pry_w / 2, 0, lid_H - lid_pry_d + lid_pry_r])
        rotate([0, 90, 0])
            cylinder(h=lid_pry_w, r=lid_pry_r);
}

module lid_tool_hole() {
    translate([
        board_x + tool_hole_y,
        W / 2 + tool_hole_x,
        -lip_H - 0.20
    ])
        cylinder(h=lid_H + lip_H + 0.50, d=tool_hole_d);
}

module lid_raw() {
    color([1.0, 0.76, 0.02])
    difference() {
        union() {
            chamfered_rounded_box(
                [L, W, lid_H],
                corner_r,
                lid_bottom_chamfer,
                lid_top_chamfer,
                lid_face_inset
            );
            lid_alignment_tabs();
            oled_guard_rail();
        }

        lid_screw_cutouts();
        oled_window_cutout();
        oled_wire_pass_cutout();
        if (lid_pry_recess) lid_pry_cutout();
        if (tool_hole) lid_tool_hole();
    }
}

module lid_for_print(offset_y=0) {
    translate([0, offset_y + W, lid_H + oled_guard_H])
        rotate([180, 0, 0])
            lid_raw();
}

module lid_for_assembly() {
    translate([0, 0, case_H])
        lid_raw();
}

module glove_mount_for_print(offset_y=0) {
    translate([mount_x, offset_y, 0])
        glove_mount_raw();
}

module glove_mount_for_assembly() {
    translate([mount_x, mount_y, -mount_H])
        glove_mount_raw();
}

module assembly_preview() {
    glove_mount_for_assembly();
    bottom_case();
    lid_for_assembly();
}

if (part == "case") {
    bottom_case();
} else if (part == "lid") {
    if (print_layout)
        lid_for_print(0);
    else
        lid_raw();
} else if (part == "mount") {
    glove_mount_raw();
} else if (part == "assembly") {
    assembly_preview();
} else {
    bottom_case();
    lid_for_print(W + gap);
    glove_mount_for_print(W * 2 + gap * 2);
}
