# LaserPlan – native Android app

Native Kotlin app for LaserPlan. It keeps the package `io.github.thankids_sudo.laserplan` and the signing key of
the installed 12.0 app, so the release APK installs as an update.

## What is native and what is reused

| Part | How it runs |
|---|---|
| Drawing, CAD, rooms, plumbing, electrical, furniture, estimates, Excel/DXF/JSON export | The existing web engine (`index.html` at the repository root) bundled into the APK and served from `https://appassets.androidplatform.net/assets/www/` by `WebViewAssetLoader`. Works offline. |
| Bluetooth laser meters | Native Android Bluetooth (BLE GATT, Classic RFCOMM/SPP, keyboard/HID) – no Web Bluetooth |
| Saving exported files | Android "Save as" (Storage Access Framework) |
| AR room capture | Native ARCore (`ar/ArRoomActivity`), optional: phones without ARCore still use manual drawing and the laser |

The web app talks to native code only through one message channel, `window.LPNative`
(`androidx.webkit` WebMessageListener, allowed origin `https://appassets.androidplatform.net`, main frame only).
Messages are JSON and are checked against an allow-list in `bridge/Bridge.kt`:

| Web → native | Native → web |
|---|---|
| `hello`, `laser.open`, `laser.reconnect`, `laser.disconnect`, `save {name, mime, b64}` | `state {state: on/busy/off, device, adapter, transport, status, error}`, `reading {v, u, m, adapter, device, id, t}`, `toast`, `saved` |

Readings enter the web app through the same `lzReading()` path as before. That path applies the arming, stale-reading,
duplicate, conflict and lock rules, so a reading is applied only to the wall that is armed when it arrives. Readings
are sent to the plan only while the plan is on screen. A reading taken while the Laser meters screen is open is
shown there as a test reading and never queued for later.

## Native AR room capture (ARCore)

Open it from **Capture room shape → Start AR** in the plan. It runs the camera inside the app (GLSurfaceView + ARCore), not in Chrome.
1. Move the phone over the floor until ARCore finds the floor plane.
2. Aim the centre reticle at each floor corner and tap **Add corner**. Corners are anchored and labelled C1, C2…, and live wall lengths are shown.
   - Only hits on the first floor plane are accepted: an upward-facing plane, or a depth point at that floor height (±8–12 cm).
3. Edit corners as needed:
   - **Undo** removes the last corner.
   - **Delete point** removes the corner under the reticle.
   - To move a point, delete it and add it again.
4. For the room height, tap **Height** and aim at the ceiling, then **Set height**. If the ceiling is too plain for AR, use **Type height**.
5. Tap **Finish**. It is blocked when there are fewer than 3 corners, corners under 5 cm apart, crossing walls, or an area under 0.5 m².

The result is a versioned JSON payload `laserplan.ar-room` v1 (`geometry/RoomGeometry.kt`). It holds:
- the plan points in metres;
- the raw 3D corners, with their source (plane or depth) and time;
- the floor height, and the room height with its source (`ar-ceiling-plane`, `ar-ceiling-depth`, `ar-ceiling-point` or `manual`);
- whether the Depth API was used, and how many times tracking was lost;
- wall, angle, area and perimeter metrics.

The web engine checks the payload, then builds the room with the same `capToRoom()` used by WebXR. Its walls are marked **AR (≈ estimate)** until measured with the laser, and the raw capture is kept in `room.capture.native`.

Phone AR is sketch-grade. Treat AR lengths as estimates until they are confirmed with the laser.

## 3D survey recording (v13.2, milestone 1)

These controls are on the AR screen, above the corner-capture buttons, and were added without removing any existing control.

| Control | What it does |
|---|---|
| **● Record 3D** | Starts a session. If the phone has no ARCore Raw Depth, it says so and records nothing; no depth is ever invented. |
| **■ Stop 3D** | Stops safely. Frames still being saved are finished, then the processed cloud and manifest are written. |
| **View 3D** | Opens the point-cloud viewer. |
| **Export ZIP** | Saves the session with Android's Save-as screen. No storage permission is needed and nothing is uploaded. |

**How keyframes are recorded**
- Rate: about 1 keyframe per second, at most 90 per session.
- Frames are taken only while ARCore is tracking. After tracking returns, recording waits 0.7 s before the next frame.
- What each keyframe holds:
  - a 16-bit raw depth image, in millimetres along the camera's optical axis;
  - an 8-bit confidence image;
  - the camera sensor pose (`Camera.getPose`);
  - camera texture intrinsics, plus the same intrinsics scaled to the depth image size;
  - the depth and camera timestamps;
  - an RGB JPEG on every 3rd keyframe.
- The AR render thread only copies image buffers. JPEG encoding, unprojection, voxel reduction and all file writes run on one background thread.
- When that thread is 4 frames behind, new frames are skipped and counted, so memory stays bounded.

**How depth becomes points** (`survey/DepthMath.kt`)
- Each pixel is converted with: x = (u−cx)·z/fx, y = −(v−cy)·z/fy, camera point = (x, y, −z).
- The camera point is then moved into ARCore world space by the pose matrix.
- Points are rejected for: zero depth, confidence below 128/255, range over 5 m, or non-finite values.
- A frame whose depth and pose timestamps differ by more than 50 ms keeps its raw files but adds no points (marked `valid:false`).
- Accepted points are averaged into 2 cm voxels, with a cap of 600,000 voxels. The viewer shows up to 40,000 points.
- Every limit above is a constant in `SurveyConfig`, and is written into each manifest.

**Viewer:** drag to rotate, pinch to zoom, double-tap to reset. Points are coloured by height and an XYZ axis marker is shown. With no data it shows an empty-state message. It displays measured points only; it is not a mesh or a BIM model.

**ZIP layout** (`laserplan.ar-survey` v1): `manifest.json`, `frames/NNNN.json`, `depth/NNNN.raw16le`, `confidence/NNNN.raw8`, `rgb/NNNN.jpg`, `cloud.xyzc` (ASCII: x y z confidence).
- The manifest records the device, timestamps, units, coordinate conventions, settings, depth support, frame and point counts, and the processing method.
- Sessions are stored in the app's private cache. Only the 3 newest are kept.
- None of this goes through the web bridge.

**Accuracy:** phone AR depth is estimate-grade (centimetres, worse at range and on plain, dark or shiny surfaces). The S24 Ultra has no LiDAR, so depth comes from ARCore's motion stereo. Compare against laser readings before trusting any dimension.

## Universal Laser Connectivity Engine (ULCE)

```
laser/
  model/          DiscoveredLaserDevice, LaserMeasurement, SupportStatus, ConnState …
  transport/      BleTransport (serialised GATT ops, CCCD, notifications), ClassicSppTransport
  protocol/       BoschMtProtocol, LeicaDistoProtocol (pure decoders)
  adapters/       LaserMeterAdapter interface; BoschGlmAdapter, LeicaDistoAdapter, SppTextAdapter, HidKeyboardAdapter
  measurement/    MeasurementNormalizer (mm/cm/m/ft/in/fractions → metres), ReadingGate (duplicates, reconnect replay)
  registry/       AdapterRegistry (evidence-based matching), CompatibilityRegistry (assets/compatibility/devices.json)
  diagnostics/    Diagnostics (pseudonymised log, share on request)
  LaserManager    scan, connect, one active meter, preferred meter, automatic reconnect (3 tries)
ui/LaserActivity  Laser meters screen
```

An adapter is chosen only from evidence in the advertisement (service UUID or a model-name pattern). For an unknown
device, Inspect is read-only: it connects, lists the device's services and disconnects without sending any command.
After that the user can share a diagnostics report.

### Adding a new meter
1. Get evidence: the maker's documentation, or a diagnostics report plus a few packets with the screen values.
2. Add a decoder in `protocol/` with unit tests that use those real packets.
3. Add an adapter in `adapters/`, extending `BleAdapter` for BLE. Send only documented commands.
4. Register it in `LaserManager.init` and add a row to `devices.json` with the right status.
5. Change the status to `VERIFIED` only after a test with the physical meter.

### Support status (from `devices.json`)

| Status | Meaning | Meters |
|---|---|---|
| Verified | Tested with the meter in this app | none yet |
| Protocol supported | Adapter built from a documented or published protocol | Bosch GLM 50-27 CG (reference meter), Leica DISTO |
| Experimental | May work; needs a test report | Other Bosch GLM/PLR models, keyboard mode, Classic SPP text stream |
| Not evaluated | No adapter yet | Stabila, Hilti, DeWalt, Stanley, Mileseey, Laserliner, Würth, SOLA, Johnson, CEM, ADA, Precaster, Suaoki, Nivel, REEKON |

## Build

GitHub Actions → **Build native Android APK** runs on every push to `feature/native-arcore-universal-laser`, or on demand.
- Steps: unit tests, then the debug APK and lint, then the release APK.
- The release APK is signed with the `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` secrets, alias `laserplan`. The run log prints its certificate SHA-256.
- Artifact `LaserPlan-native-<version>` contains:
  - `LaserPlan-<v>-DEBUG.apk`: package `….laserplan.debug`, installs beside the main app, for testing only.
  - `LaserPlan-<v>-release.apk`: installs over 12.0.

To build locally with Android SDK 36 and JDK 17: `cd android-native && gradle testDebugUnitTest assembleDebug` (Gradle 8.13).

## Permissions
- `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT` on Android 12 and newer.
- Location only on Android 11 and older, where BLE scanning needs it.
- `INTERNET` for web fonts and the optional online spreadsheet library.

App data is excluded from cloud backup. The app uploads nothing.

## Before installing over the 12.0 app
The 12.0 app stored projects inside Chrome; the native app stores them in its own WebView. **Export each project
as JSON in 12.0 before updating**, then import it in the native app.
