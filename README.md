# LaserPlan GLM — site measurement & estimation for existing buildings

Measure each room of an existing building with a **Bosch GLM laser over Bluetooth** (tested target: GLM 50-27 CG). Mark wall types, doors/windows and electrical points on site, and take away a **complete estimate in Excel** with live formulas, plus DXF/SVG plans.

Quantities produced (all from site measurements):

| Group | Items |
|---|---|
| Masonry | Main walls (230 mm), partition walls (115 mm), parapet |
| Concrete | RCC slab: room area + bearing on walls |
| Plaster | Internal walls 12 mm, ceiling 6 mm, external walls + plinth + parapet 20 mm |
| Painting | Internal walls, ceiling, external surfaces |
| Terrace | Waterproofing over roof area and wall tops, plus upturn |
| Floors | Flooring by type (vitrified, Kota, marble …), skirting less door widths |
| Doors & windows | Schedule by code (D1, W1, V1 …) with sizes, numbers and locations |
| Electrical | Points by type (light, fan, 6A/16A socket, AC, geyser, DB …), wiring system and run class |

---

## 1. Install on your phone (one time)

Web Bluetooth **and AR** need an **HTTPS** page. Opening the downloaded `index.html` from phone storage (address starts `content://` or `file://`) cannot use Bluetooth or AR. Chrome blocks both there, and the app now says so.

Quickest option (no account setup, from a PC): go to **app.netlify.com/drop**, drag the unzipped folder onto the page, and open the `https://….netlify.app` address it gives on the phone.

Free, permanent option: GitHub Pages.

1. Create a **public** GitHub repository, e.g. `laserplan`.
2. Upload every file in this folder: `index.html`, `xlsx.full.min.js`, `three.min.js`, `manifest.webmanifest`, `sw.js`, `icon-192.png`, `icon-512.png`, `README.md`.
3. Repo **Settings → Pages → Deploy from branch → `main` / root → Save**.
4. After about a minute: `https://<username>.github.io/laserplan/`
5. On Android open it in **Chrome → ⋮ → Add to Home screen / Install app**. It then works offline, including the Excel export.

| Device | Browser |
|---|---|
| Android phone / tablet | Chrome or Edge ✅ |
| Windows / Mac / ChromeOS | Chrome or Edge ✅ |
| iPhone / iPad | Safari ❌. Use the **Bluefy** browser ✅ |

## 2. Connect the GLM 50-27 CG

1. Press the **Bluetooth key** on the laser.
2. **Close the Bosch MeasureOn app.** The laser accepts only one connection.
3. Do **not** pair the laser in Android Bluetooth settings.
4. In LaserPlan, select a wall and tap **Laser** (or tap the laser chip at the top). Choose **Bosch GLM 50-27 CG → Search & connect**, pick the laser in Chrome's list and wait for **Connected**.
5. Phone Bluetooth **and Location** must be on.

## 3. Site workflow (per building)

1. **☰ menu**: enter the *Name of work* and *Location*. Check **Settings → Building specification**: wall thicknesses, slab, plinth, parapet, upturn, skirting, IS 1200 rule.
2. **Room selector** (top left, amber) → **+ Add room…** → pick a name (Office, Hall, Lab …) and floor.
3. **Draw** the room (freehand sketch, line, or rectangle; see 3A). Walls straighten and the first wall is highlighted. Shoot each wall with the laser; readings land on the highlighted wall and move to the next one.
4. **Details → Room**: set the height. Tap the height field and shoot the ceiling with the laser to fill it. Tick the works: ceiling plaster, RCC slab over room, open terrace above, skirting, flooring type.
5. **Details → Walls**: tap the chips on each wall:
   * **Main 230 / Partition 115**: wall type. A thickness override is available in the wall editor.
   * **EXT**: the outer face gets external plaster and paint, plus a parapet if the terrace is open.
   * **½ shared**: the wall is between two rooms you are measuring, so its masonry counts half in each.
   * **+ door/win**: place doors, windows and ventilators in that wall.
6. **Details → Openings**: edit the door/window schedule. Tap *Width*, shoot the opening, tap *Height*, shoot again.
7. **Details → Electrical**: use quick add (Light, Fan, 6A, 16A, AC, Geyser …), then pick the wiring system and run class from the dropdowns. Use **Copy points from another room** for similar rooms.
8. **Details → Estimate**: enter rates (₹ per unit). Tap **Details** on an item to see every measurement row. Then **Export Excel**.

Any input with a green edge accepts the laser directly: tap it, then press measure on the laser.

### Rules used (also printed in the Excel "Assumptions" sheet)
* Wall lengths are inner faces. External walls add one wall thickness to the length (corner allowance) for masonry and the outer face.
* Enter an opening **once**, in one room. Masonry deducts openings larger than 0.1 m².
* IS 1200 plaster/paint deductions: ≤ 0.5 m² not deducted; 0.5–3 m² deducted on one face (the room face); > 3 m² deducted on both faces. Jambs are not added. You can switch this off in Building specification.
* External height = room height + slab thickness; ground-floor rooms add the exposed plinth.
* Terrace rooms: parapet over external walls (plastered and painted on both faces and the top), waterproofing over area + wall bearing + upturn.
* The app ships with **no rates**. Enter the current Gujarat R&B / CPWD / JAU SOR rates. **Rates… → Save as my default rates** pre-fills them in every new estimate, and **Export/Import rates .json** shares one office rate set with colleagues.

## 3A. Drawing & editing tools (main window)

The bottom bar has three modes: **Draw**, **Edit** and **Trace**. Draw and Edit each show a tool strip on the left of the drawing. Keyboard shortcut letters (for a PC) are in brackets.

**Draw**
| Tool | Use |
|---|---|
| Freehand (F) | Rough sketch, straightened automatically (as before). |
| Line / polyline (L) | Tap corner after corner. A live length and angle shows while your finger is down. Tap the first corner to close the room. Or use the bar at the bottom: choose → ↓ ← ↑, then **type a length or shoot the laser**, and each value adds the next wall. **Close** adds the closing wall; **Finish** leaves the outline open. |
| Rectangle (R) | Drag from one corner to the opposite corner. |
| Rectangle by size | Enter or laser-shoot length and breadth; the room is created exactly to size and marked measured. |
| Dimension (D) | Drag, or tap two points, to add a reference dimension (column to column, a niche, a beam). Dimensions export to DXF and SVG. They do not change quantities. |

**Edit**
| Tool | Use |
|---|---|
| Pick (V) | Drag a **corner** to move it. Drag a **wall** to offset it parallel; the walls on either side stretch, and the offset distance is shown. Tap a wall to select it; tap it again (or its label) for the wall editor. |
| Add corner (A) | Tap a wall to add a corner there, then drag it with Pick to make a bay, jog or offset. |
| Remove (X) | Tap a corner: its two walls merge into one. Tap a dimension to delete it. |
| Move room (M) | Drag the whole room. It snaps corner-to-corner with other rooms on the same floor, so adjoining rooms line up. |
| Rotate 90° · Mirror ↔ · Mirror ↕ | Turn or flip the current room (dimensions follow). |
| Duplicate room | Copies the room with its walls, openings and electrical points next to it. Useful for identical rooms such as a row of offices. |

**Snapping chips** (top right): **Grid** = 50 mm steps (10 mm when zoomed in close); **Ortho** = walls at 90° (or 45°, set in Settings); **Snap** = corners and walls of other rooms. Tap a chip to switch it off.

**Zoom**: + / − / fit buttons on the right, pinch with two fingers, or the mouse wheel. Drag on empty space to pan.

A wall marked *measured* that you lengthen or shorten by editing turns back into an *estimate* (dashed label). Re-shoot it with the laser to confirm. **Undo/Redo** cover every edit. On a PC: Ctrl+Z / Ctrl+Y, Delete removes the selected corner or wall, Esc cancels.

## 3B. Version 4 additions (learned from leading laser-CAD apps and Indian measurement practice)

| Feature | Idea taken from | How to use |
|---|---|---|
| **True shape by diagonals** | Leica DISTO Plan "Smart Room", Bosch MeasureOn PRO diagonals | Details → Room → shoot diagonal C1→C3 (and C1→C4 … for rooms with more corners) → **Apply true shape**. Real corner angles are shown (e.g. 88.2° / 91.8°) and used for area and all quantities. |
| **Defect / repair markers** | BOLDR area maps, MeasureOn markups | Draw → ⚠ Defect (K): tap the spot, choose the type (damaged plaster, dampness, crack, spalled RCC, leakage, tiles, paint, door, electrical, plumbing) and shoot its size. Each becomes a **repair item** in the estimate and the Defects sheet. |
| **Text notes on plan** | DISTO Plan text & marker tools | Draw → Note (N): "Column 300×300", "Beam soffit 2.85 m" … exported to DXF (layer NOTES) and the report. |
| **Site photos with measured arrows** | MeasureOn photo documentation, BOLDR photo annotation | Details → Room → **+ Photo** (camera). Drag on the photo to draw an arrow; the last laser reading is attached, or tap the value box and shoot. Photos are kept on the phone and travel inside Project .json. |
| **Wall elevation view** | MeasureOn wall view | Open any wall: a B&W elevation shows its doors/windows at their sill heights, so you can check the counts visually. |
| **Printable site report** | DISTO Plan PDF, MeasureOn PDF export | ☰ → **Site report (.html)** or Estimate → Site report. Open the file and print to PDF: area statement, plans, room schedule with corner angles, abstract, defects, checks, photos, basis of measurement, signature block. |
| **Checks before submission** | BOLDR required fields & tolerances | Estimate tab → Checks: unmeasured walls, closure error over tolerance, open outlines, missing external walls, openings wider than the wall, missing diagonals, missing rates. Also in the Excel "Checks" sheet. |

### Measurement methods built in (estimation practice)
* **Centre-line method, room by room.** Lengths at an offset from the measured inner face use exact corner geometry, L + d·(k₁+k₂) with k = tan(φ/2): +1 at a 90° convex corner, −1 at a re-entrant corner (L-shaped rooms). Masonry uses the centre line (d = t/2), external plaster and paint the outer face (d = t), and the parapet its own centre line and faces. This equals the textbook rule *centre line = inner perimeter + 4 × 2 × ½ breadth* for a rectangle, and stays correct for L, T and other shapes. Checked against an independent geometry library.
* **IS 1200 Part 3**: masonry deducts openings > 0.1 m². **IS 1200 Part 12/13**: plaster and paint ≤ 0.5 m² no deduction; 0.5–3 m² one face; > 3 m² both faces, with jambs, soffit and sill added. **IS 1200 Part 1**: measured dimensions rounded to 0.01 m (specified thicknesses such as 115 / 125 / 230 mm stay exact). Each rule can be switched off in Building spec.
* **Painting of doors, windows and grills** by CPWD / IS 1200 Part 13 equivalent-area coefficients (flush 1.2, panelled 1.3, part glazed 1.0, fully glazed 0.8, louvred 1.8, corrugated 1.25 each side; grill 1.0, trellis 2.0 all over). Set per door/window type.
* **IS 3861 area statement**: give every room a *Use*. Carpet area counts usable rooms only; kitchens/toilets/stores, corridors/stairs/lifts and verandahs are shown separately. Plinth area = floor areas + walls at floor level (shared walls ½) + 50 % of uncovered verandahs.
* **Approximate estimate by plinth-area method**: enter the plinth-area rate (₹/m²) in Building spec. The Estimate tab and Excel show plinth area × rate next to the detailed estimate.

## 3C. Version 5 additions (from the official written tutorials of MeasureOn, DISTO Plan, magicplan and BOLDR)

| Feature | Taken from | How to use |
|---|---|---|
| **Readings bar** | DISTO Plan stack bar, MeasureOn measurement list | The last 6 laser readings sit under the hint. **Tap** one to put it on the selected wall, or **drag** it onto any wall. |
| **On-screen Measure button** | DISTO on-screen ON/DIST, MeasureOn remote measurement | The green ◉ button (right side, when the laser is linked) asks the laser to measure. If your laser ignores remote trigger, press its own button. |
| **3D view** | DISTO Plan 3D tab | **3D** button on the right: walls (external walls tinted), doors, windows, columns and parapets. Views: 3D / Top / Front / Side; this floor only; roof slab on or off. Drag to rotate, pinch to zoom. |
| **DXF 2D + height** | DISTO Plan "2D+Height DXF" | ☰ → DXF 2D + height: adds 3DFACE wall surfaces (layer WALLS3D) at each floor's level. |
| **Area tool** | magicplan Affected Area, DISTO Area | Draw → Area (G): tap the corners of an irregular patch, then tap the first point again. Link it to tiles, waterproofing, re-plaster, damp-proofing, repainting or your own item. |
| **Columns / pillars** | Leica circle tool; IS 3861 pillar rule | Draw → Column (C): rectangular or circular, free-standing or attached. Deducted from flooring, ceiling and carpet area; exposed faces added to internal plaster and paint. |
| **Split room** | magicplan split room | Edit → Split (S): tap a point on one wall, then on another wall. Two rooms result with a partition of standard thickness between them. |
| **Square up** | DISTO angle snapping | Edit → Square up: all walls of the room snap to 90° (or 45°) and estimates re-solve. |
| **Exact corner shift** | DISTO point-shift tool | Edit → Pick: tap a corner, then tap it again to see its coordinates and angle, and move it by an exact Δx / Δy. |
| **Background plan tracing** | magicplan Import & Draw, DISTO Sketch on Photo | Draw → Background plan: load a photo or scan of an old drawing, tap two points of known distance, and enter or shoot that distance. Trace with Line or Rectangle. One background per floor. |
| **Item library with formulas** | magicplan Estimator item libraries | Estimate → My items: e.g. dado tiles = (perimeter − doors) × 1.2 m in toilets and kitchens, false ceiling = ceiling area of offices, wall putty = wall area less openings, anti-termite = plinth share, fixed LS items. "Save to my library" adds them to every new estimate. |
| **Add-ons on total** | magicplan cost rules (tax, markup) | Estimate → Add-ons: contingencies, work-charged establishment, GST, labour cess. Tick and set %. Shown in the abstract, report and Excel (formula rows and grand total). |
| **Copy floor** | magicplan floors | Room tab → Copy floor: duplicates all rooms to another floor for typical floors (walls marked estimate until re-shot); optionally moves the terrace up. |
| **Project details & logo** | MeasureOn project details, magicplan report logo | ☰: client/department, work/file no., address, measured by, checked by, logo. Printed in the site report and the Excel header. Saved estimates can be searched. |
| **Wall labels, room complete tick** | DISTO line description, BOLDR completion checkbox | Wall editor → label; Room tab → "Room measurement complete ✓" (shown in the room list). |

Not included: arc (curved) walls, tilt/angle readings from the laser, PDF import (use a screenshot of the PDF page), cloud sync and multi-user editing (use Project .json to move work between devices).

## 3D. Version 6: CAD workspace and multi-room drawing

**New rooms no longer replace old ones.** When the current room is already closed, any new Sketch, Line or Rectangle starts a **new room** ("Room 3", "Room 4" …) on the same floor, copying height and works settings. To redraw an existing room, use Details → Room → **Clear outline and redraw this room**, or reshape it with Edit.

**CAD mode** (bottom bar → CAD) is a full drawing window that works alongside the rooms.

| Group | Tools (command-line alias) |
|---|---|
| Draw | Line (L), Polyline (PL), Rectangle (REC), Polygon (POL), Circle (C: centre-radius / diameter, 2-point, 3-point), Arc (A: 3-point, centre-start-end/angle), Ellipse (EL), Point (PO), Text (T), Dimension (DIM) |
| Modify | Move (M), Copy (CO), Rotate (RO), Scale (SC, with **Reference**: shoot the old length and the new length with the laser), Mirror (MI), Offset (O), Erase (E), drag **grips** of selected objects |
| Inquiry | Distance (DI; the result also goes to the readings bar, so you can drag it onto a wall), Area (AA, by points or by object) |
| Convert | Make room (ROOM): turns any closed polyline, rectangle or polygon into a room for the estimate |
| UCS | World, Origin, 2-point, Rotate, Align to a room wall. Grid, Ortho, typed coordinates and the Draw/Edit tools all follow the UCS |

**Entering points and lengths (AutoCAD style):**
* Tap on the drawing. Object snaps show a green marker: endpoint □, midpoint △, centre ○, quadrant ◇, intersection ×, perpendicular ⊥, nearest.
* Type in the command line: `x,y` (absolute in the UCS), `@dx,dy` (relative), `@length<angle` (polar, angle anticlockwise from the UCS X axis), or **just a length**. A bare length is placed along the direction you are pointing (direct distance entry).
* **Laser:** whenever a tool is waiting for a length, radius, distance, offset or scale reference, pressing measure on the GLM enters the reading automatically.
* Enter or Space repeats or finishes, Esc cancels, and Undo/Redo cover every CAD action.
* Select objects by tapping them, or drag a window: left-to-right selects objects fully inside; right-to-left selects anything crossing.

**Details → CAD tab:** properties of the selected object. You can set the exact length of a line, the radius of a circle, or the axes of an ellipse; tap the box and shoot the laser. It also has the UCS buttons and the layer manager (current layer, colour, show/hide; default layers 0, Survey, Services, Furniture).

CAD objects export to DXF on their own layers (lines, circles, true arcs, text, points; ellipses as segments), and to the SVG/PNG plan and the site report.

## 3E. Version 7: mouse controls (PC / laptop)

| Mouse action | Result |
|---|---|
| **Hover** | Walls, corners, CAD objects, markers and other rooms highlight. A tooltip gives length, type, external/shared, measured/estimate, radius or area. The cursor shows what a drag will do (move, grab, crosshair). |
| **Right-click** | Context menu for whatever is under the cursor. **Wall:** edit, use last reading, mark external/internal, main/partition, shared, add corner here, add door/window, delete. **Corner:** exact position, remove. **Inside a room:** add defect, note or column here; room details. **Empty space:** start a new room here, rectangle by size. **CAD object:** properties, move, copy, rotate, scale, mirror, offset, copy, make room, send length to readings, erase. **CAD empty:** repeat last command, paste here, select all, quick draw tools, UCS origin here. Always: zoom to fit / in / out, 3D, undo, redo. Arrow keys move through the menu. |
| **Double-click** | Wall → wall editor. Another room → open it. Inside the current room → Room details. Defect/note/column → edit. CAD object → its properties. Empty space → zoom to fit. While drawing a line → finish. |
| **Wheel** | Zoom at the cursor (smooth). **Shift + wheel** pans sideways; trackpad two-finger swipe pans; trackpad pinch zooms. |
| **Middle or right button drag** | Pan. **Middle double-click** zooms to everything (AutoCAD style). |
| **Hold Shift** | Flips Ortho while held (free angle when Ortho is on, locked angles when it is off). |
| **Typing in CAD** | Keys go straight to the command line; what you type also shows next to the cursor (dynamic input). |
| **Ctrl+A / C / V / D** | In CAD: select all, copy, paste at the cursor, duplicate. |
| **Arrow keys** | Nudge the selected CAD objects or the selected corner by one grid step; with Shift, 10 steps (in UCS directions). |
| **Drag a file onto the drawing** | A JPG/PNG becomes the background plan for this floor and calibration starts. A LaserPlan .json opens the project; a rates .json loads the rates. |
| **Status bar** (bottom) | Mode and tool, live X/Y in the UCS, zoom in px/m, and clickable Grid / Ortho / Snap switches. |

Fit and zoom buttons now animate smoothly. The animation is switched off if the computer is set to reduce motion.

## 3F. Version 8: measuring walls with the laser, with locks

**Workflow**
1. In **Edit**, tap a wall. The bar under the plan shows **Laser**. Tapping a dimension label opens the wall window, which also has **Laser**.
2. The first time, choose a meter: **Bosch GLM 50-27 CG**, **Other Bosch GLM (not verified)** or **SIMULATED METER**. The bar shows *Connecting… / Connected / Disconnected*, plus **Reconnect** when the link drops.
3. The selected wall is now **armed** (green dashed outline). Put the meter's **rear edge** on the starting wall face, aim along the wall at the opposite face and press the meter's measure button. The reading goes only to the armed wall.
4. The length is applied and **locked**: a padlock appears beside the dimension. Tap the next wall to arm it. The meter stays connected, so there is no picker to reopen.
5. **Remeasure** arms a locked wall again. **Unlock** frees it. **Lock** locks a wall you typed in yourself.

**Safeguards (implementation choices)**
* A reading is used only if a wall is armed when it arrives. Applying a reading clears the pending assignment, so one reading can never fill two walls.
* If you change the selection while a reading is pending, the pending assignment is discarded. Any reading arriving in the next **4 s** is then ignored, and you are asked to measure again. 4 s is the GLM's longest measuring time (manual p.17). Bluetooth frames carry no identifier, so a late reading for the previous wall cannot be told apart from a new one in any other way.
* A reading that arrives within **0.8 s** of arming is ignored as too early.
* A reading with the same value as the one just applied, arriving within 3 s, is ignored as a duplicate.
* Units: the GLM sends metres (float32). The simulated meter can send m, cm, mm, ft, in or yd. All are converted to metres, rounded to 0.001 mm, which removes float32 noise but no real precision. The original value and unit are kept on the wall (`lz` record) and in the laser log.
* Locked lengths are never changed by auto-solve, corner or wall drags, corner insert or delete, or other edits. Any such edit is undone with a message naming the locked wall, and the wall flashes red while you drag.
* If a new reading would leave the room outline open by more than 2 mm, a conflict window lists the locked and measured walls holding it. You choose one of three options: **keep the locked lengths** (the reading is discarded and the wall re-armed), **apply and keep the other locks** (the gap stays visible), or **apply and unlock those walls**.
* A remeasured value that differs from the locked one by more than 2 mm asks **keep** or **replace**.
* Locks, the original reading, the unit, the time, the meter and the declared reference are saved in the project, in the .json export and in the Excel Walls sheet ("measured · locked"). Plans from older versions open with no locks.
* Manual entry is unchanged: tap a selected wall again and type a length. Locked walls show the length field read-only until you Unlock.

**What is documented and what is not**

| Behaviour | Status | Source |
|---|---|---|
| GLM 50-27 CG sends measurements over Bluetooth 4.2 LE (GATT) to mobile devices | Documented | Bosch manual pp.35–36; Bosch tech data page |
| Measuring takes about 0.5 s, at most about 4 s | Documented | Bosch manual p.17 |
| Distance is measured from the reference level chosen on the meter (rear edge, tripod thread or front edge). For wall-to-wall work with the meter's back on the wall, choose the rear edge | Documented | Bosch manual p.18 |
| Units m / ft / in on the meter | Documented | Bosch manual p.21 |
| A reset is needed between measurements | **Not required.** No reset step is documented; "delete" only removes stored values. The app therefore shows no reset instruction | Bosch manual p.35 (deleting values) |
| Bosch's documented Bluetooth path is the Bosch app; third-party apps are not described | Documented limit | Bosch manual pp.35–36 |
| BLE service and characteristic UUIDs, frame `C0 55 10 06`, float32 metres at bytes 7–10 | **Not documented by Bosch.** Community-decoded (§5) | community sources |
| The meter's reference setting is transmitted | **Unverified.** The app cannot read it; you declare it in **Meter…** | — |
| magicplan: connect a laser from the Change Measurement → Laser screen; supported list includes GLM 50-27 CG | Documented (magicplan) | magicplan Help Center |
| magicplan: manually entered dimensions get a lock icon; locked dimensions do not move when connected elements change; unlock via Change Measurement → Unlock | Documented (magicplan) | magicplan Help Center |
| Laser readings lock automatically | **Implementation choice** (your specification). magicplan documents locks for manual edits only | — |
| Arming, stale/duplicate guards, conflict choices | **Implementation choices** | — |
| Web Bluetooth needs https, a user tap, and is blocked inside cross-origin frames unless allowed by Permissions-Policy | Documented | MDN; Chrome developers |
| Web Bluetooth in Chrome on Android 6+, Windows 10+, macOS, ChromeOS | Documented | Chrome developers |

**v8.1 additions (from the November 2026 comparison of magicplan, Bosch MeasureOn, Leica DISTO Plan, MeasureSquare and Home Planner)**
* **Guide** (Home Planner-style next-wall prompts, on by default): after each reading the next unmeasured wall **clockwise** is armed. When the walls are done, the guide asks for the **diagonal(s)** from C1, but only if a corner is more than 0.5° off square, or if *squareness check* is ticked under **Meter…**. The true shape is then applied from the diagonals. Last, it asks for the **room height** (meter flat on the floor, aim at the ceiling). **Skip** moves on. Locked walls are never re-armed by the guide.
* **Unassigned readings** (MeasureOn-style): readings with no armed target, and readings held back by the stale or too-early guards, go to an orange list in the laser bar instead of being lost. Tap a reading and then a wall, or drag it onto a wall. Each one is applied with the same lock and conflict checks and then removed from the list. The list is saved with the project.
* **Collect** mode (MeasureOn "collect a batch"): take all the readings first, then press **Fill**. Fill puts them, oldest first, on the unmeasured walls clockwise from the selected wall, and stops at the first conflict.
* The existing reading chips under the LCD now also lock the wall and check for conflicts.
* The padlock (magicplan-style) also appears in the SVG plan export.
* Not adopted: an IFC export for BOQ handoff (magicplan) is a separate piece of work. DXF in this app already keeps dimensions on the DIMS layer, unlike magicplan's DXF.

**Where real Bluetooth works:** the installed LaserPlan (https, GitHub Pages or any web host) in Chrome on Android with Bluetooth and Location on. The Claude artifact preview runs inside a frame without Bluetooth, and the artifact platform has no Bluetooth capability. Use **SIMULATED METER** there.

**Sources consulted**
* Bosch GLM 50-27 CG original instructions (PDF): https://media.bosch-pt.com.ph/binary/manualsmedia/o416915v21_160992A8KF_202305.pdf
* Bosch GLM 50-27 CG technical data: https://bosch-pt.co.in/manuals/professional/in/en/online-manual/200424695/en-GB/13820869387.html
* magicplan, Laser distance meters: https://help.magicplan.app/laser-distance-meters
* magicplan, How to lock and unlock dimensions: https://help.magicplan.app/how-to-lock-and-unlock-dimensions
* magicplan, Why can't I connect my laser: https://help.magicplan.app/why-cant-i-connect-my-laser
* magicplan, Change dimensions of your floor plan: https://help.magicplan.app/change-dimensions-of-your-floor-plan
* MDN Web Bluetooth API: https://developer.mozilla.org/en-US/docs/Web/API/Web_Bluetooth_API
* MDN Permissions-Policy bluetooth: https://developer.mozilla.org/docs/Web/HTTP/Headers/Permissions-Policy/bluetooth
* Chrome, Communicating with Bluetooth devices over JavaScript: https://developer.chrome.com/docs/capabilities/bluetooth

**Still to check on a real meter:** (1) the GLM connects from Chrome on your phone; (2) one press gives exactly one frame, or the duplicate guard catches repeats; (3) the value equals the meter display when the meter shows ft or in, i.e. the frame is always in metres; (4) whether continuous mode (p.25) streams frames; (5) the remote trigger `C0 40 00 EE`.

## 3G. Version 8.2: room shape from phone AR or a 3D scan

Open **Menu → Room shape from AR or 3D scan…**, the **AR/Scan** button in the laser bar, or right-click an empty part of the plan. You can also drop an `.obj` file on the plan. There are three ways in:

1. **Phone AR (Android).** This uses WebXR `immersive-ar` with hit-test, in Chrome on an ARCore-supported Android phone, from the installed app (https).
   * Walk around the room, aim the centre dot at each floor corner and tap **Add corner** (tapping the screen also works).
   * The live readout shows the distance from the last corner.
   * **Height**, then **Set height** with the dot on the ceiling, records the floor-to-ceiling height. Ceiling hits depend on the phone finding the ceiling surface, which is not verified.
   * **Finish**, or adding a corner near C1, closes the room.
2. **AR SIMULATION.** The same capture screen runs on a hidden 4.20 × 3.60 m test room with one corner 2° off square and ±15 mm noise. Use it to try the workflow where AR is not available, e.g. in the Claude preview.
3. **3D scan import (.obj).** This is the route for iPhone/iPad LiDAR, and also for Android photogrammetry scans.
   * Export the scan as OBJ from your scanning app, then choose the up axis (Y for ARKit-based apps), the units and the cut height (0.7 m by default: below window sills, above beds and tables).
   * The mesh is cut horizontally and the outline traced. Gaps up to 1.5 m (doors, unscanned glass) are bridged, small closed loops (furniture) are ignored, and the largest outline becomes the room. Room height = mesh height range.

**Installed APK and AR:** on a real phone the AR session started inside the installed app, but the camera picture stayed black, while the same page in Chrome worked. So inside the app, **Start AR** opens LaserPlan in Chrome with the capture screen ready. After **Finish**, switch back to the app: it reloads the project when it comes to the front and shows the new room, because the app and Chrome share this website's storage. **Try here anyway** still attempts AR inside the app.

**Result:** a new room whose walls are **estimates (≈)**, marked `src: AR` or `src: scan`. Corners within 1.5° of square are squared up to remove AR noise; larger skews are kept, so the laser guide then asks for a diagonal. The raw capture (points, source, file, cut height) is saved on the room as `capture`. If a laser session is connected, W1 is armed straight away, so the laser confirms and locks each wall.

**What runs where (checked November 2026)**

| Device / route | Status | Source |
|---|---|---|
| Android phone, Chrome, ARCore-supported device | WebXR hit test, anchors and depth sensing shipped; plane detection only as a developer trial | Google ARCore WebXR comparison page |
| iPhone / iPad Safari | WebXR **not supported** on iOS (only visionOS), so LiDAR cannot be used from a web app | caniuse "WebXR Device API"; WebKit bug 208988 |
| iPhone / iPad LiDAR via Apple RoomPlan | Native Swift API (camera + LiDAR); outputs USD/USDZ with wall dimensions. Not reachable from a web app. **USDZ import is not implemented**: export OBJ instead | Apple RoomPlan page |
| Claude preview | AR is blocked inside the preview frame; use AR SIMULATION and OBJ import | tested |
| A plain video recording | Not supported. A video has no scale, so lengths cannot be derived without a reference or depth data | — |

**Accuracy:** neither Google nor this app publishes an accuracy figure for phone AR hit tests or for scan meshes. Treat AR and scan lengths as sketch-grade, and lock lengths only from laser readings.

**Tests (simulated / synthetic):**
* AR simulation: 4 corners plus height → 4 walls within 40 mm of the true room; height 3.05 m ±30 mm; the 2° skew kept; diagonal then requested.
* Synthetic OBJ room 4.20 × 3.60 × 2.80 m with door gap, window, table and wardrobe → area 15.12 m², 4 walls exact, height 2.80 m. Same result with Z-up in cm, and with a 1.5 m cut through the window.
* Noisy L-shaped mesh (±5 mm, door gap) → 6 corners, area 23.746 m² against 23.75 m² true.
* Not yet tested: a real ARCore phone, and OBJ exports from real scanning apps.

**Sources**
* Google, WebXR vs ARCore feature comparison: https://developers.google.com/ar/develop/webxr/arcore-comparison
* caniuse, WebXR Device API: https://caniuse.com/webxr
* WebKit bug 208988 (WebXR in WebKit): https://bugs.webkit.org/show_bug.cgi?id=208988
* Apple, RoomPlan: https://developer.apple.com/augmented-reality/roomplan

## 3H. Android APK (built by GitHub Actions)
The APK is a Trusted Web Activity: it opens the https site using Chrome's engine, so the Bosch Bluetooth link and AR keep working. A WebView wrapper would lose Bluetooth.
Workflow file: `.github/workflows/build-apk.yml`; step-by-step guide: `APK-HOW-TO.md`. Run **Actions → Build Android APK → Run workflow** and download the APK from the run's Artifacts.

## 3I. Version 9: whole floor plan, doors and windows, stairs, slabs, electrical layout

Everything below starts from the **＋ Add** button at the top right of the plan, in Edit mode.

**Whole floor in one plan**
* All rooms of the floor are drawn together, with their wall thickness and lengths. **Tap inside, or on a wall of, any other room** to work on it; the view stays where it is.
* **Room attached to a wall:** select a wall, then **＋ Add → Room attached to a wall** (or right-click the wall → *Add room on this wall…*). Enter the depth, the width along the wall, the start offset from the corner, the name and the use.
  * The new room is drawn on the far side of the wall, beyond its thickness.
  * Both walls are marked *shared*, so masonry is counted ½ in each room.
  * Its walls start as estimates (≈); measure them with the laser to lock them.

**Doors and windows at their real position**
* **＋ Add → Door / window**: pick the type (D1, W1 …) and tap the wall. You can place several in one go; **Done** ends.
* Drag a placed opening along its wall to move it (5 cm steps when Grid is on).
* Tap it twice to type exact distances: *from corner Cn to the near edge* and *from the far edge to the next corner* (laser-ready fields). Doors also get hinge side and swing direction.
* Width, height and sill belong to the type, so editing them changes every opening of that type.
* Each placed opening also counts in the wall's schedule, so masonry, plaster, paint deductions and the door/window schedule follow automatically. Older plans keep their counted, unplaced openings.

**Stairs and stair rooms**
* **Stair**: tap where it starts. Set straight or dog-legged, floor-to-floor height, maximum riser, tread, flight width, gap, landing and waist slab.
  * Risers = ⌈height ÷ max riser⌉, split into two flights for dog-legged.
  * Warnings appear for risers above 190 mm or treads below 250 mm (the NBC 2016 residential limits).
  * Drag the stair to move it; the arrow and **UP** mark show the direction.
* **Stair room**: creates a room (use = stair) sized for a dog-legged stair plus a bottom floor landing, with the stair inside it.
* Estimate items:
  * *RCC in stair* (waist slab along slope + ½·tread·riser per step + mid landing, m³)
  * *Shuttering* (soffit, landing, riser faces, m²)
  * *Finishing* (treads, risers, landing, m²)
  * *Handrail* (along slope + landing turn, m)

**Slabs**
* **Slab – tap corners**: corners snap to wall corners. Tap the first corner, or **Finish**, to close.
* **Slab over rooms…**: one slab per chosen room, out to the outer face of its walls. It can untick "RCC slab over this room" for those rooms, so nothing is counted twice.
* Types: roof, floor, sunshade/chajja, loft, landing, other; each slab has its own thickness.
* Estimate: RCC volume per type (m³), and shuttering for soffit and edges (m²).

**Electrical layout and wiring**
* Place a distribution board (DB), switch boards (SB) and points:
  * Points: light, fan, LED batten, 6A/16A sockets, AC, geyser, exhaust, bell, TV, data, telephone, CCTV, outdoor light.
  * Wall items snap to the nearest wall.
  * Default heights: SB 1.20 m, sockets 0.30 m, AC 2.10 m, DB 1.80 m, lights and fans at the ceiling.
* Tap an item twice to edit its type, label, height, wiring size and feeder, and its **position: distance along the wall from corner Cn, and out from the wall**. When selected, the plan shows the distances to both corners and the height.
* **Auto wiring:**
  * Points connect to the nearest SB in the same room; SBs connect to the nearest DB.
  * Route = up to ceiling + along the ceiling parallel to the axes + down to the point + 0.30 m. Wire = route × conductors (3 by default).
  * **＋ Add → Wiring** lists every route and the totals, and re-connects everything on request.
* Estimate items:
  * Point wiring rows, short/medium/long chosen from the actual route (≤3 / ≤6 / >6 m)
  * Switch boards (Nos)
  * PVC conduit (m)
  * Wire per size (m)
  * Points you also entered as counts in the Electrical tab are counted separately, so avoid entering the same point both ways.

**Show / hide:** ＋ Add → Show lets you hide doors/windows, stairs, slabs, electrical, wiring or wire lengths.
**DXF:** new layers OPENINGS (jambs, door leaves, windows), STAIRS, SLABS, ELECTRICAL and WIRING.

## 3J. Version 10: wall elevations, inside and outside

Open a wall (tap it twice). The wall window now shows the wall as an elevation you can edit.

* **Inside view / Outside view.**
  * Inside: the wall as seen from inside the room. The corner names (C2, C3 …) show which end is left.
  * Outside: mirrored, using the **outer-face length** (inner length + wall thickness at the corners) and the height up to the top of the slab. Electrical points are inside items, so they are hidden in this view.
* **Doors and windows at their positions.** A dimension chain under the wall runs corner → opening edge → opening edge → corner; window sills are dimensioned.
  * Openings counted in the wall but not yet placed show dashed with "(not placed)". Tap one to place it in the largest free gap.
  * Drag an opening sideways to move it; drag a window up or down to change its sill. The sill is set for that window only, and the estimate area is unchanged.
  * Tap an opening to type **From Cx (left)** / **From Cy (right)** and the sill (laser-ready), flip the door hinge and swing, or remove it.
* **Electrical points on the wall.** Switch boards, sockets, AC, geyser and other wall points appear at their real position and height.
  * Drag them in both directions.
  * Tap one to type its distance from either corner, its height, type and label. **Wiring & feeder…** opens the full point settings.
* **＋ Add on this wall…** puts a door, window or wall point straight into the free space of this wall.
* Everything you change here also changes the plan, the wiring lengths and the estimate. The counts in the wall window stay in step.

## 3K. Version 11: plumbing, general room items, frame (columns, beams, lintels), 3D corners

**Plumbing – water supply and drainage** (＋ Add → Plumbing)
* Items:
  * Supply sources: overhead-tank riser, sump/main inlet.
  * Fixtures: EWC, IWC, wash basin, kitchen sink, shower, bib tap, health faucet, washing machine, geyser.
  * Drainage: floor trap (nahani), soil/waste stack with vent cowl, gully trap, inspection chamber, rainwater pipe.
* Wall items snap to the nearest wall. Stacks and rainwater pipes can sit outside the wall (negative "out from wall").
* Tap an item to edit its type, label, distance along the wall from a corner, offset from the wall and inlet/outlet height. Wall items also appear in the wall elevation: inside view, or outside view for stacks. Drag them there, or type exact distances.
* **Predict plumbing for toilets & kitchens** (rooms with use Toilet/bath or Kitchen):
  * Places the fixtures. The fixture wall is chosen non-shared, external and door-free first, then longest.
  * Adds a floor trap and a stack (gully trap for a ground-floor kitchen) outside that wall, plus a supply riser within 8 m of each wet room.
* **Pipes are routed automatically**, parallel to the axes:
  * *Supply*: every fixture joins the nearest point of the network already connected to a source (shortest-connection tree). Runs are at the supply run height (0.60 m, editable), plus vertical drops to each inlet and the riser drop.
  * *Drainage*: basin, shower, bath tap, washing machine and sink discharge to a floor trap in the same room. Floor traps and WCs join the tree that ends at a stack, gully trap or inspection chamber. Gradient: 1:40 up to 75 mm, 1:60 for 110 mm; the fall is shown.
* Pipe sizes come from the fixture units carried by each run:
  * Supply: ≤3 LU 15 mm, ≤10 LU 20 mm, ≤30 LU 25 mm, ≤70 LU 32 mm. Riser at least 32 mm.
  * Drainage: any WC downstream → 110 mm, otherwise by DFU (40 / 50 / 75 / 110).
  * These are **simplified, indicative tables**; check against IS 2065, IS 1742 and NBC 2016 Part 9 before issue.
* Estimate items:
  * fixtures (Nos), with their accessories counted separately (angle valves, pillar/sink cocks, bottle/P-traps, waste couplings, ball/gate valves, vent cowl, clean-out, gully and chamber)
  * CPVC pipe per size (m), including riser
  * UPVC SWR pipe per size (m), including the stack and its vent above roof
  * bends and tees/junctions (Nos) counted from the routes
* **Pipes, sizes & settings…** shows the totals per size and lets you change the run height, drop allowance and vent height.
* **Concealed or open pipes** (v11.1): set a project default for water-supply pipes and for drain pipes in **Pipes, sizes & settings…**, and override any item in its window or in the wall elevation (Project default / Concealed / Open).
  * *Concealed water supply* runs in the wall chase at the concealed run height (0.60 m). The estimate adds **cutting chases in masonry and making good** (m).
  * *Open water supply* runs on the wall surface at the open run height (2.10 m by default, so the vertical drops are longer). The estimate adds **saddle clamps @ 1.0 m** (Nos).
  * *Concealed drainage* runs under the floor, in a sunken slab or in a shaft. The estimate adds **cutting / making good floor** (m).
  * *Open drainage* is exposed, at the open drain height (0.15 m). The estimate adds **clamps @ 1.2 m** (Nos).
  * A stack fixed outside a wall counts as open unless you change it.
  * Pipe items are split by installation, e.g. *CPVC 15 mm – concealed in wall chase* and *CPVC 15 mm – open on wall surface*.
  * On the plan, open pipes are drawn dotted / dash-dot and labelled *open*; concealed pipes are labelled *conc.*
* **Pipe diameter selection** (v11.2):
  * **Material** – in **Pipes, sizes & settings…** choose the water-supply pipe (CPVC SDR 11 IS 15778, PPR PN16 IS 15801 sized by OD, GI medium IS 1239, UPVC Sch 40 IS 4985) and the drainage pipe (UPVC SWR IS 14735, cast iron IS 3989, HDPE IS 4984). Each offers only the sizes that material is sold in; calculated sizes snap to the nearest available size (e.g. SWR 110 → CI 100, CPVC 15 → PPR 20).
  * **Minimum diameter** – a project floor for supply and for drainage (e.g. no drain below 75 mm).
  * **Per-run diameter** – the *Pipe diameters – each run* table in the same window lists every run (from → to, load in LU / DFU, mode, length) with a size dropdown; *Auto* keeps the calculated size. The same dropdowns are in each fixture's window and in the wall-elevation editor (the size applies to the run feeding / draining that item; on a tank or stack it sets the riser / stack).
  * Choosing a size **smaller than calculated** is allowed but flagged: *!* on the plan label and in the table, and a warning in the item's info line.
  * The estimate splits pipes by material, installation and diameter, e.g. *PPR PN16 pipe, 25 mm – water supply, concealed in wall chase*; fittings are named after the chosen material.

**General items in this room** (Room tab, below Flooring)
* Wall putty, primer, OBD distemper, texture paint, white/colour wash, POP punning, gypsum false ceiling, POP cornice, tile dado (height editable), granite kitchen platform (length), dismantling flooring, hacking plaster, IPS/CC flooring, Kota stone, anti-termite, sunken toilet waterproofing (floor + 300 mm upturn), MS window grills, chemical seepage treatment, cleaning/debris, and a lump-sum item (quantity).
* Quantities are computed live from the room:
  * walls net of openings
  * ceiling / floor net of columns
  * perimeter
  * (perimeter − doors) × dado height
  * window area

**Building specification → Frame**
* Column size (used for new columns), beams over all main walls (width, depth including slab), RCC lintels (depth, bearing each side), plinth beam on ground floor (depth). All three are off by default, so existing estimates do not change until you switch them on.
* Estimate items:
  * RCC columns (area × floor height to top of slab)
  * beams (portion below slab, ½ on shared walls)
  * lintels (opening width + 2 bearings)
  * plinth beam
  * shuttering for columns, beams and lintels
* Beam and lintel volumes are **deducted from masonry**. Lintels under a beam are skipped.
* Fix: Save in Building specification read its fields after closing the window, so nothing was saved; it saves correctly now.

**3D view**
* Walls are now mitred solids, so outer corners close properly (no notches); parapets the same.
* Placed doors and windows appear at their real positions and sills.
* Stairs (steps and landing) and drawn slabs are shown.

## 3L. Version 12: kitchen platform and furniture

**Kitchen platform.** Open it from **＋ Add → Kitchen platform in <room>…**, from the button in the Room panel of a kitchen, or by tapping the platform on the plan.
* Tick the walls it runs along. The app suggests the longest wall without a door plus its longer door-free neighbour (an L shape).
* Set the depth (default 600 mm), the height of the top (850 mm), and the dado tile height above the top (600 mm).
* **Top stone:** granite, marble, engineered quartz, Kota or kadappa, 16 / 18 / 20 / 25 / 30 mm thick.
* **Construction** – the four types and their estimate rows:

  | Type | Base | Supports | Notes |
  |---|---|---|---|
  | Kadappa | 25 mm kadappa slab (m²) | Half-brick masonry (m²) | Top stone laid in CM |
  | RCC | 75 mm RCC slab (m³) | Half-brick masonry (m²) | Top stone laid in CM |
  | **Sandwich** | 25 mm kadappa base with the top stone epoxy-bonded over it (m²) | Half-brick masonry (m²) | Front edge is double thickness; the edge switches to *double / sandwich* automatically |
  | Modular | SS 304 frame, front and back rails (m) | None | No masonry |

* **Front edge:** single nosing, double / sandwich edge (40 mm laminated strip), or bullnose. Measured in metres along the front plus the exposed ends at doors.
* **Cut-outs and extras:**
  * Sink and hob cut-outs (Nos).
  * Kadappa shelf under the platform (m²).
  * Modular shutters under the platform (m², front length × height).
  * Stone back-splash strip (m).
* **How it is measured:**
  * Doors on a ticked wall are deducted, and corners are counted once.
  * Supports are spaced at 750 mm or less.
  * The dialog shows the clear walkway between parallel platform runs and flags anything under 900 mm.
* Drawn hatched on the plan with its size and stone; shown in 3D as a body with a dark (granite) or light (marble / quartz) top.

**Furniture.** In **＋ Add**, choose an item from the Bedroom, Kitchen, Living, Dining or Study group, then tap the plan. Wall items snap with their back to the nearest wall. Drag an item to move it; it slides along the wall and jumps to another wall near a corner. Tap it twice to edit.

| Group | Items (default W × D × H, mm) |
|---|---|
| Bedroom | Single 910×1980, double 1370×1980, queen 1520×1980, king 1830×1980 beds; bedside table 450×400×550; hinged wardrobe 1200×600×2100; sliding wardrobe 1800×650×2100; loft 1200×600×600 (bottom at 2100); dresser 900×450×750 |
| Kitchen | Base cabinets 1200×560×720; wall cabinets 1200×350×700 (bottom at 1450); tall unit 600×560×2100; loft 1200×350×600 |
| Living | Sofas: 3-seater 2000×850, 2-seater 1550×850, 1-seater 900×850, L-shaped 2600×1700; tipoi / centre table 1000×550×420; TV cabinet 1800×450×450; TV back panel 2100×1200; shoe rack 900×350×900 |
| Dining | 4-seater 1200×800, 6-seater 1800×900, 8-seater 2100×1000 tables (chairs drawn and counted) |
| Study | Study table 1200×600×750 with chair; bookshelf / overhead unit 900×300×1800 |

* **Editing an item.** The item window has:
  * Type, label, width, depth, height, and height of the bottom above the floor.
  * Distance from the wall corner, or rotation for free-standing items.
  * Material and a specification note that goes into the estimate.
  * Rotate 90°, Duplicate (places the copy next to it along the wall) and Delete buttons.
* **How it is estimated:**
  * Carpentry items (wardrobes, lofts, kitchen cabinets, TV units and panels, shoe racks, bookshelves, dresser) are measured on face / shutter area in m². This follows the usual per-m² or per-sq-ft carpentry rates.
  * Carcass: BWP ply IS 710, MR ply IS 303, HDHMR, or pre-laminated particle board IS 12823. Finish: laminate, acrylic, PU or veneer. Set the project default in **Carpentry materials…** and change it per item if needed.
  * Loose furniture is counted in Nos, with its material (sheesham, teak, engineered wood, fabric, leatherette, or as per approved sample). Beds also have a storage option (none / box / hydraulic). Dining chairs are a separate item.
* **Show / hide:** the Furniture and Kitchen platform layers are in the **＋ Add** menu.

Sizes were checked against: HouseYog – kitchen platform height, depth and walkway (https://www.houseyog.com/blog/kitchen-platform-height-depth-walkway/); Studio Matrx furniture size chart (https://www.studiomatrx.org/utilities/furniture-size-chart); Stone Galleria granite thickness guide (https://www.stonegalleria.in/granite-thickness-guide/); Comaron kitchen platform stone comparison (https://www.comaron.com/blog/kitchen-platform-stone-comparison-2026). The construction of a "sandwich" platform varies between contractors. The definition above is the common local meaning; edit the item description in the Excel file if your schedule words it differently.

## 3M. Version 13: native Android app (in development)

The native Kotlin app is in `android-native/`; see `android-native/README.md` for details. It is built by the **Build native Android APK** workflow on the `feature/native-arcore-universal-laser` branch.
* It runs this same web engine offline inside the app.
* Laser meters connect through native Bluetooth: Bosch MT protocol, Leica DISTO, keyboard mode, and an experimental Classic SPP mode.
* The web app gained a small bridge (`window.LPNative`) that does nothing in a normal browser.
* Native ARCore room capture works inside the app: tap the floor corners, set or type the height, and the room arrives in the plan as AR estimates for laser confirmation (v13.1).
* 3D survey recording (v13.2): **Record 3D / Stop 3D / View 3D / Export ZIP** on the AR screen record ARCore raw depth, confidence, poses and RGB keyframes. The app shows a rotatable point-cloud preview and exports a documented survey ZIP.

## 4. Excel workbook

| Sheet | Contents |
|---|---|
| Abstract | Sr, item, quantity (linked to Measurement), unit, rate, amount, total. All formulas. |
| Measurement | PWD form: Item, Description, No, L, B, D/H, Quantity (= No × L × B × D formula), item totals. Deductions are negative rows. |
| Rooms | Floor-wise rooms, height, perimeter, carpet area, volume, closure error, works ticked. |
| Walls | Every wall: length, measured/estimate, type, thickness, external, shared, openings. |
| Door-Window | Schedule: code, kind, description, size, area, total numbers, where used. |
| Electrical | Points by room, plus a summary by point type. |
| Area statement | Floor-wise carpet / service / circulation / verandah / wall / plinth areas, plinth-area cost, room-wise list. |
| Defects | Every defect marker with size, quantity formula, remarks and repair item. |
| Checks | Site checks to clear before submitting. |
| Laser log | Every laser reading with time and room (audit trail). |
| Assumptions | Basis of quantities and the building specification used. |

You can change rates or measurement rows in Excel and the totals update. Other exports: **DXF** (mm; layers WALLS, DIMS, ROOMS, OPENINGS; floors laid side by side), **SVG/PNG** floor plan (B&W, hatched walls), **CSV**, and **Project .json** to move an estimate to another phone or PC.

## 5. Bluetooth protocol (maintainers)
* Service `02a6c0d0-0451-4000-b000-fb3210111989`, characteristic `02a6c0d1-0451-4000-b000-fb3210111989`.
* The app writes `C0 55 02 01 00 1A` to switch on auto-sync. Measurement frames start `C0 55 10 06`; bytes 7–10 are a float32 little-endian distance in metres.
* Readings enter through `onDistance()` → `lzReading()` (v8 arming and locks; `laser_v8` block in `index.html`). **Laser → Meter… → Raw packets & diagnostics** shows raw packets. If a firmware changes the frame, adjust `parseGlmPacket()`.
* Community sources: Nordic DevZone "Sniffing a Bosch laser tape", gist ketan/054e9f4173aa53a04218fc545241f634, B4X forum "Bosch Distance Laser GLM Serie".

## 6. Editing the app
Everything is in `index.html`; there is no build step. Edit the dropdown lists near the top of the script under **Catalogues**: `FLOORS`, `FLOORING`, `OP_KINDS`, `EL_POINTS`, `EL_WIRING`, `EL_CLASS`. Default item descriptions are in `itemDesc()`, and quantity rules are in `estimate()`. After changing files, bump `laserplan-v2` in `sw.js` so installed phones refresh.
