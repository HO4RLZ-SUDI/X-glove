$fn = 96;

// ESP32-C3 + OLED enclosure
// Units: millimeters

// ---------- Printable output ----------
part = "all";          // "all", "case", "lid", "mount", "assembly"
print_layout = true;   // true: lay lid upside-down for printing

// ---------- Hardware ----------
board_L = 24.0;        // ESP32-C3 board length, 2.4 cm
board_W = 18.0;        // ESP32-C3 board width, 1.8 cm
board_clearance = 0.7;

oled_L = 34.0;         // OLED module length, 3.4 cm
oled_W = 21.0;         // OLED module width, 2.1 cm
oled_clearance = 0.7;
oled_header_on_right = true;

// ---------- Case ----------
L = 52.0;
W = 38.0;
case_H = 14.0;
lid_H = 3.2;
wall = 2.2;
floor_H = 2.0;
gap = 8.0;

corner_r = 4.0;
inner_r = 2.6;
body_bottom_chamfer = 0.55;
body_top_chamfer = 0.75;
body_face_inset = 0.55;
lid_bottom_chamfer = 0.35;
lid_top_chamfer = 0.55;
lid_face_inset = 0.42;
skin = 0.04;

// ---------- USB-C opening ----------
// Opening is on the long side of the case, centered on the ESP32-C3 port.
usb_slot_w = 15.0;     // requested 1.5 cm
usb_slot_h = 6.0;
usb_slot_z = floor_H + 0.75;
usb_slot_r = 1.35;
usb_mouth_extra = 1.2;

// ---------- External wire exit ----------
wire_exit_w = 7.0;
wire_exit_h = 4.8;
wire_exit_z = floor_H + 4.0;
wire_exit_r = 1.8;

// ---------- OLED top pocket and pass-through ----------
oled_pocket_depth = 1.35;
oled_pocket_r = 1.6;
oled_wire_slot_L = 12.0;
oled_wire_slot_W = 5.5;
oled_wire_slot_edge_gap = 1.8;
oled_wire_slot_r = 1.1;

// ---------- Lid screws ----------
screw_inset = 5.7;
boss_d = 5.4;
pilot_d = 1.65;        // M2 self-tapping pilot
lid_screw_d = 2.35;
screw_head_d = 4.8;
screw_head_H = 1.25;
boss_top_gap = 0.45;
boss_embed = 0.18;

// ---------- Lid alignment tabs ----------
lip_H = 1.65;
lip_clearance = 0.30;
lip_tab_T = 1.15;
lip_tab_L = 13.0;

// ---------- Board tray ----------
tray_H = 0.75;
tray_embed = 0.16;
rail_W = 1.0;
rail_H = 1.35;
rail_overlap = 0.15;
front_usb_gap = 17.0;
clip_L = 4.6;
clip_H = 0.55;
clip_overlap = 0.12;

// ---------- Glove mount ----------
mount_L = 64.0;
mount_W = 50.0;
mount_H = 2.8;
mount_r = 5.2;
mount_bottom_chamfer = 0.45;
mount_top_chamfer = 0.55;
mount_face_inset = 0.50;
mount_x = (L - mount_L) / 2;
mount_y = (W - mount_W) / 2;

velcro_w = 22.0;
velcro_clearance = 2.4;
velcro_slot_L = velcro_w + velcro_clearance;
velcro_slot_W = 5.2;
velcro_slot_r = 1.45;
velcro_slot_edge = 8.5;
velcro_channel_depth = 1.05;
velcro_channel_clearance = 1.8;

glove_sew_slot_L = 11.5;
glove_sew_slot_W = 3.4;
glove_sew_slot_r = 1.25;
glove_sew_slot_x = 4.3;
glove_sew_slot_y_offset = 10.0;

mount_fastener_x_offset = 13.0;
mount_fastener_y = W - wall - 5.4;
mount_fastener_boss_d = 6.2;
mount_fastener_boss_H = 3.7;
mount_fastener_pilot_d = 1.65;
mount_fastener_clearance_d = 2.35;
mount_fastener_head_d = 4.9;
mount_fastener_head_H = 1.2;

board_x = (L - board_L) / 2;
board_y = wall + board_clearance;
oled_x = (L - oled_L) / 2;
oled_y = (W - oled_W) / 2;
oled_pocket_x = oled_x - oled_clearance;
oled_pocket_y = oled_y - oled_clearance;
oled_pocket_L = oled_L + oled_clearance * 2;
oled_pocket_W = oled_W + oled_clearance * 2;
oled_wire_slot_x = oled_header_on_right
    ? oled_x + oled_L - oled_wire_slot_L - oled_wire_slot_edge_gap
    : oled_x + oled_wire_slot_edge_gap;
oled_wire_slot_y = (W - oled_wire_slot_W) / 2;
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

    translate([L / 2 - usb_slot_w / 2, -0.70, usb_slot_z])
        rounded_slot_y(depth + 0.70, usb_slot_w, usb_slot_h, usb_slot_r);

    translate([
        L / 2 - (usb_slot_w + usb_mouth_extra * 2) / 2,
        -0.75,
        usb_slot_z - usb_mouth_extra / 2
    ])
        rounded_slot_y(
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
    }
}

module screw_bosses() {
    screw_positions(floor_H - boss_embed)
        cylinder(h=case_H - floor_H - boss_top_gap + boss_embed, d=boss_d);
}

module bottom_screw_pilots() {
    screw_positions(floor_H + 0.35)
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
        cube([board_L, board_W, tray_H + tray_embed], false);

    // Side rails.
    translate([board_x - rail_W, board_y, floor_H - tray_embed])
        cube([rail_W + rail_overlap, board_W, rail_H + tray_embed], false);

    translate([board_x + board_L - rail_overlap, board_y, floor_H - tray_embed])
        cube([rail_W + rail_overlap, board_W, rail_H + tray_embed], false);

    // Back rail.
    translate([board_x, board_y + board_W - rail_overlap, floor_H - tray_embed])
        cube([board_L, rail_W + rail_overlap, rail_H + tray_embed], false);

    // Front rail is split so it does not block the USB-C connector.
    front_segment_L = (board_L - front_usb_gap) / 2;
    if (front_segment_L > 0) {
        translate([board_x, board_y - rail_W, floor_H - tray_embed])
            cube([front_segment_L, rail_W + rail_overlap, rail_H + tray_embed], false);

        translate([board_x + board_L - front_segment_L, board_y - rail_W, floor_H - tray_embed])
            cube([front_segment_L, rail_W + rail_overlap, rail_H + tray_embed], false);
    }

    // Light retaining clips at the rear edge.
    translate([board_x + 3.0, board_y + board_W - 0.25, floor_H + rail_H - clip_overlap])
        cube([clip_L, rail_W + 0.70, clip_H + clip_overlap], false);

    translate([board_x + board_L - 3.0 - clip_L, board_y + board_W - 0.25, floor_H + rail_H - clip_overlap])
        cube([clip_L, rail_W + 0.70, clip_H + clip_overlap], false);
}

module bottom_case() {
    color([1.0, 0.76, 0.02])
    difference() {
        union() {
            bottom_shell();
            screw_bosses();
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

module glove_mount_raw() {
    color([1.0, 0.76, 0.02])
    difference() {
        chamfered_rounded_box(
            [mount_L, mount_W, mount_H],
            mount_r,
            mount_bottom_chamfer,
            mount_top_chamfer,
            mount_face_inset
        );

        velcro_under_channel_cutout();
        velcro_slot_cutouts();
        glove_sew_slot_cutouts();
        mount_plate_screw_cutouts();
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
    screw_positions(-lip_H - 0.15)
        cylinder(h=lid_H + lip_H + 0.40, d=lid_screw_d);

    screw_positions(lid_H - screw_head_H)
        cylinder(h=screw_head_H + 0.30, d=screw_head_d);

    screw_positions(lid_H - 0.18)
        cylinder(h=0.28, d=screw_head_d + 0.45);
}

module oled_recess_cutout() {
    translate([oled_pocket_x, oled_pocket_y, lid_H - oled_pocket_depth])
        linear_extrude(oled_pocket_depth + 0.25)
            rounded_rect_2d([oled_pocket_L, oled_pocket_W], oled_pocket_r);
}

module oled_wire_pass_cutout() {
    translate([oled_wire_slot_x, oled_wire_slot_y, -lip_H - 0.15])
        linear_extrude(lid_H + lip_H + 0.40)
            rounded_rect_2d([oled_wire_slot_L, oled_wire_slot_W], oled_wire_slot_r);
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
        }

        lid_screw_cutouts();
        oled_recess_cutout();
        oled_wire_pass_cutout();
    }
}

module lid_for_print(offset_y=0) {
    translate([0, offset_y + W, lid_H])
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
