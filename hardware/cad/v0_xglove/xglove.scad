$fn = 96;

// ==================================================
// ESP32-C3 Super Mini Wrist Enclosure
// Refined OpenSCAD source for print-ready case + lid
// ==================================================

// ---------- Board ----------
board_L = 22.52;
board_W = 18.00;
board_clearance = 1.10;

// ---------- Case ----------
wall = 2.80;
end_wall = 6.40;
floor_h = 2.10;
case_h = 10.80;
lid_h = 3.10;

strap_edge = 5.30;

bay_L = board_L + board_clearance*2;
bay_W = board_W + board_clearance*2;

L = bay_L + end_wall*2;
W = bay_W + wall*2 + strap_edge*2;
H = case_h;

// ---------- Surfacing ----------
corner_r = 4.30;
bay_r = 1.80;
body_bottom_chamfer = 0.70;
body_top_chamfer = 1.05;
body_face_inset = 0.72;
lid_bottom_chamfer = 0.42;
lid_top_chamfer = 0.72;
lid_face_inset = 0.55;
skin = 0.045;

// ---------- USB-C ----------
usb_w = 13.20;
usb_h = 5.80;
usb_z = floor_h + 0.85;
usb_r = 1.35;
usb_mouth_extra = 1.35;

// ---------- Apple Watch strap sockets ----------
strap_w = 22.40;
strap_h = 4.60;
strap_z = floor_h + 0.80;
strap_r = 1.20;
strap_socket_depth = end_wall - 0.60;
strap_mouth_extra = 1.35;

// ---------- Lid screws ----------
lid_screw_d = 2.25;
pilot_d = 1.70;
screw_head_d = 4.35;
screw_head_h = 1.28;
screw_inset = 5.35;
pilot_h = 4.60;
boss_d = 5.30;

// ---------- Lid lip ----------
lip_h = 1.55;
lip_clearance = 0.35;
lip_chamfer = 0.24;

// ---------- Board tray ----------
tray_h = 0.68;
rail_w = 1.05;
rail_h = 1.25;
clip_h = 0.56;
clip_w = 4.80;
tray_embed = 0.18;
rail_overlap = 0.18;
clip_overlap = 0.12;

// ---------- Layout ----------
gap = 9.00;
print_layout = true;
part = "all"; // "all", "case", "lid", "assembly"
show_tray = true;
show_bosses = true;

// ==================================================

module rounded_rect_2d(size=[10,10], r=2) {
    rr = max(0.01, min(r, min(size[0], size[1])/2 - 0.01));

    hull() {
        for (x=[rr, size[0]-rr])
        for (y=[rr, size[1]-rr])
            translate([x,y])
                circle(r=rr);
    }
}

module centered_profile(parent=[10,10], face=[8,8], r=1, z=0, h=skin) {
    translate([
        (parent[0]-face[0])/2,
        (parent[1]-face[1])/2,
        z
    ])
        linear_extrude(h)
            rounded_rect_2d(face, r);
}

module rounded_prism(size=[10,10,10], r=2) {
    linear_extrude(size[2])
        rounded_rect_2d([size[0], size[1]], r);
}

module chamfered_rounded_box(size=[10,10,10], r=2, bottom=0.6, top=0.8, inset=0.5) {
    face = [
        max(0.1, size[0] - inset*2),
        max(0.1, size[1] - inset*2)
    ];
    face_r = max(0.1, r - inset);
    mid_h = max(0.01, size[2] - bottom - top);

    union() {
        hull() {
            centered_profile([size[0], size[1]], face, face_r, 0, skin);
            centered_profile([size[0], size[1]], [size[0], size[1]], r, bottom, skin);
        }

        translate([0,0,bottom])
            rounded_prism([size[0], size[1], mid_h], r);

        hull() {
            centered_profile([size[0], size[1]], [size[0], size[1]], r, size[2]-top-skin, skin);
            centered_profile([size[0], size[1]], face, face_r, size[2]-skin, skin);
        }
    }
}

module rounded_slot_x(length=5, y_size=10, z_size=4, r=1) {
    rr = max(0.01, min(r, min(y_size, z_size)/2 - 0.01));

    hull() {
        for (y=[rr, y_size-rr])
        for (z=[rr, z_size-rr])
            translate([0,y,z])
                rotate([0,90,0])
                    cylinder(h=length, r=rr);
    }
}

module rounded_slot_y(length=5, x_size=10, z_size=4, r=1) {
    rr = max(0.01, min(r, min(x_size, z_size)/2 - 0.01));

    hull() {
        for (x=[rr, x_size-rr])
        for (z=[rr, z_size-rr])
            translate([x,0,z])
                rotate([-90,0,0])
                    cylinder(h=length, r=rr);
    }
}

module screw_positions(z=0) {
    for (x=[screw_inset, L-screw_inset])
    for (y=[screw_inset, W-screw_inset])
        translate([x,y,z])
            children();
}

module bay_origin() {
    translate([end_wall, wall + strap_edge, floor_h])
        children();
}

module board_origin() {
    translate([
        end_wall + board_clearance,
        wall + strap_edge + board_clearance,
        floor_h - tray_embed
    ])
        children();
}

module electronics_bay_cutout() {
    bay_origin()
        linear_extrude(H + 1)
            rounded_rect_2d([bay_L, bay_W], bay_r);
}

module usb_cutouts() {
    depth = wall + strap_edge + 1.20;

    translate([L/2 - usb_w/2, -0.60, usb_z])
        rounded_slot_y(depth + 0.60, usb_w, usb_h, usb_r);

    translate([
        L/2 - (usb_w + usb_mouth_extra*2)/2,
        -0.65,
        usb_z - usb_mouth_extra/2
    ])
        rounded_slot_y(
            1.25,
            usb_w + usb_mouth_extra*2,
            usb_h + usb_mouth_extra,
            usb_r + 0.32
        );
}

module strap_socket_cutouts() {
    y0 = W/2 - strap_w/2;
    z0 = strap_z;
    cut_len = strap_socket_depth + 0.60;

    translate([-0.60, y0, z0])
        rounded_slot_x(cut_len, strap_w, strap_h, strap_r);

    translate([L - strap_socket_depth, y0, z0])
        rounded_slot_x(cut_len, strap_w, strap_h, strap_r);

    translate([
        -0.65,
        W/2 - (strap_w + strap_mouth_extra*2)/2,
        z0 - strap_mouth_extra/2
    ])
        rounded_slot_x(
            1.35,
            strap_w + strap_mouth_extra*2,
            strap_h + strap_mouth_extra,
            strap_r + 0.34
        );

    translate([
        L - 0.70,
        W/2 - (strap_w + strap_mouth_extra*2)/2,
        z0 - strap_mouth_extra/2
    ])
        rounded_slot_x(
            1.35,
            strap_w + strap_mouth_extra*2,
            strap_h + strap_mouth_extra,
            strap_r + 0.34
        );
}

module bottom_screw_bosses() {
    screw_positions(floor_h)
        cylinder(h=H-floor_h-0.30, d=boss_d);
}

module bottom_screw_pilots() {
    screw_positions(H - pilot_h)
        cylinder(h=pilot_h + 0.45, d=pilot_d);
}

module board_tray() {
    board_origin()
        cube([board_L, board_W, tray_h], false);

    board_origin()
        translate([0, -rail_w, 0])
            cube([board_L, rail_w + rail_overlap, rail_h], false);

    board_origin()
        translate([0, board_W - rail_overlap, 0])
            cube([board_L, rail_w + rail_overlap, rail_h], false);

    board_origin()
        translate([-rail_w, 0, 0])
            cube([rail_w + rail_overlap, board_W, rail_h], false);

    board_origin()
        translate([board_L - rail_overlap, 0, 0])
            cube([rail_w + rail_overlap, board_W, rail_h], false);

    board_origin()
        translate([3.00, -rail_w, rail_h - clip_overlap])
            cube([clip_w, rail_w + 0.75, clip_h + clip_overlap], false);

    board_origin()
        translate([board_L - 3.00 - clip_w, -rail_w, rail_h - clip_overlap])
            cube([clip_w, rail_w + 0.75, clip_h + clip_overlap], false);

    board_origin()
        translate([3.00, board_W - 0.20, rail_h - clip_overlap])
            cube([clip_w, rail_w + 0.75, clip_h + clip_overlap], false);

    board_origin()
        translate([board_L - 3.00 - clip_w, board_W - 0.20, rail_h - clip_overlap])
            cube([clip_w, rail_w + 0.75, clip_h + clip_overlap], false);
}

module bottom_case() {
    color([1.0, 0.76, 0.02])
    union() {
        difference() {
            union() {
                chamfered_rounded_box(
                    [L,W,H],
                    corner_r,
                    body_bottom_chamfer,
                    body_top_chamfer,
                    body_face_inset
                );
                if (show_bosses)
                    bottom_screw_bosses();
            }

            electronics_bay_cutout();
            usb_cutouts();
            strap_socket_cutouts();
            bottom_screw_pilots();
        }

        if (show_tray)
            board_tray();
    }
}

module lid_lip() {
    translate([
        end_wall + lip_clearance,
        wall + strap_edge + lip_clearance,
        -lip_h
    ])
        chamfered_rounded_box(
            [
                bay_L - lip_clearance*2,
                bay_W - lip_clearance*2,
                lip_h + skin
            ],
            max(0.35, bay_r - lip_clearance),
            lip_chamfer,
            lip_chamfer,
            0.18
        );
}

module lid_screw_cutouts() {
    screw_positions(-lip_h - 0.20)
        cylinder(h=lid_h + lip_h + 0.55, d=lid_screw_d);

    screw_positions(lid_h - screw_head_h)
        cylinder(h=screw_head_h + 0.35, d=screw_head_d);

    screw_positions(lid_h - 0.22)
        cylinder(h=0.32, d=screw_head_d + 0.45);
}

module lid_top_recess() {
    panel_inset = 3.15;

    translate([panel_inset, panel_inset, lid_h - 0.22])
        linear_extrude(0.30)
            rounded_rect_2d(
                [L - panel_inset*2, W - panel_inset*2],
                corner_r - 1.10
            );
}

module lid_raw() {
    color([1.0, 0.76, 0.02])
    difference() {
        union() {
            chamfered_rounded_box(
                [L,W,lid_h],
                corner_r,
                lid_bottom_chamfer,
                lid_top_chamfer,
                lid_face_inset
            );
            lid_lip();
        }

        lid_screw_cutouts();
        lid_top_recess();
    }
}

module lid() {
    if (print_layout) {
        lid_for_print(W + gap);
    } else {
        lid_for_assembly(W + gap);
    }
}

module lid_for_print(offset_y=0) {
    translate([0, offset_y, lid_h])
        rotate([180,0,0])
            lid_raw();
}

module lid_for_assembly(offset_y=0) {
    translate([0, offset_y, 0])
        lid_raw();
}

if (part == "case") {
    bottom_case();
} else if (part == "lid") {
    if (print_layout) {
        lid_for_print(0);
    } else {
        lid_raw();
    }
} else if (part == "assembly") {
    bottom_case();
    lid_for_assembly(W + gap);
} else {
    bottom_case();
    lid();
}
