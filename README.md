# GNSS RTK Logger (Android)

An Android app for surveying-style point capture with an **external GNSS receiver** (USB OTG or Bluetooth), **real-time NTRIP corrections**, and a **live map**. Built and targeted for a Samsung Galaxy S8 (Android 7–9, `minSdk 24`), but it should run on any modern Android phone.

> **Status:** early version. The screens below are **illustrative mockups** drawn to show the layout, not real screenshots. Please test with your own receiver before relying on it for real work.

## Screens

| Main screen, recording | Main screen, after STOP | Settings |
|---|---|---|
| ![Recording](docs/screen_main_recording.svg) | ![After stop](docs/screen_main_average.svg) | ![Settings](docs/screen_settings.svg) |

### Main screen (map)
- OpenStreetMap background; zooms to your position on the first fix.
- **Dots** are recorded points: **green** = RTK fixed, **orange** = RTK float, **gray** = anything else (GPS/DGPS).
- **Ring color** around each dot = the **recording period** it belongs to. The color changes each time you start a new recording (cycles through 9 colors).
- **Diamond** = the weighted average of a recording where the antenna stayed put (see [Averaging](#averaging-stationary-recordings)).
- **Black outer ring** = a point you marked with MARK. The newest point is drawn larger while recording.
- DXF drawings you imported are drawn underneath the points.
- Top bar: fix type, satellites, HDOP, lat/lon (WGS84), ellipsoidal height, NTRIP status, point count, and easting/northing when a projected coordinate system is selected. The gear opens Settings.
- Bottom buttons: **MARK** (save a named point), **REC / STOP** (start or stop recording; it **vibrates** and **blinks** while recording), **⌖** (recenter on your position).

### Settings
Receiver, NTRIP login, coordinate system, averaging threshold, DXF overlays. Changes are saved automatically and apply the next time you press REC.

## Quick start

1. **Pair or plug in the receiver.**
   - *Bluetooth:* pair it in Android Settings first.
   - *USB:* connect with a USB-C OTG adapter. Use a powered hub if the receiver draws more than the phone can supply.
2. **Receiver setup:** enable **GGA** and **RMC** NMEA output on the port you use, and allow **RTCM3** input on the same port (on u-blox, enable these on the USB or UART protocol settings).
3. Open the app → gear → choose the receiver (tap *Refresh device list* after plugging in), enter the NTRIP host/port/mountpoint/user/password, and pick a coordinate system.
4. Back on the map, tap **REC**. The first time over USB, Android asks for permission to use the device. Tick "always".
5. Walk, or stay still on a point. Tap **MARK** to save named points (P1, P2, …). Tap **STOP** when done.

## Output files

Everything is saved in the app's folder: `Android/data/com.example.gnsslogger/files/` (visible over USB file transfer or the Files app; the exact path is shown at the bottom of Settings).

| File | Contents |
|---|---|
| `gnss_log_<UTC stamp>.csv` | Every valid fix of one recording |
| `gnss_log_<UTC stamp>_points.csv` | Points you marked (same columns plus `name`) |
| `gnss_averages.csv` | One row per averaged stationary recording (shared across recordings) |

**Log columns:** `utc_time, lat_wgs84_deg, lon_wgs84_deg, ellipsoidal_height_m, msl_altitude_m, geoid_sep_m, fix_quality, fix_name, satellites, hdop, diff_age_s, diff_station, crs, easting, northing`

**Average columns:** `start_utc, end_utc, n_fixes, duration_s, lat_wgs84_deg, lon_wgs84_deg, ellipsoidal_height_m, sd_horiz_m, sd_vert_m, pct_rtk_fixed, radius95_m, crs, easting, northing, log_file`

Latitude and longitude are always the receiver's WGS84 output. The ellipsoidal height is the MSL altitude plus geoid separation from the GGA sentence.

## Coordinate system

Settings → *Coordinate system (Esri WKID)*.

- Presets: WGS 1984 lon/lat (4326), WGS 1984 UTM with automatic zone, Web Mercator (102100), NAD 1983 UTM zones 10N–19N.
- **Custom:** type a WKID (`26917`, `2263`, `102629`), an `EPSG:`/`ESRI:` code, or a full `+proj=...` string. *Apply* validates it and shows the parameters.
- Effects: adds `crs`, `easting`, `northing` columns to the CSVs, shows E/N on the map screen, and is how imported DXF coordinates are interpreted.
- WKIDs that equal an EPSG code work directly. Esri-only codes depend on the projection library's built-in list; use a `+proj` string if one is missing.
- RTK positions are in the datum of your correction network (for example ITRF or NAD83 realizations), which can differ from WGS84 by up to a metre or two. Transform if that matters for your work.

## DXF overlays

Settings → *Import DXF file…*

- Supports **ASCII DXF**: LINE, POLYLINE, LWPOLYLINE, ARC, CIRCLE, POINT, TEXT/MTEXT, with layer and entity colors. Text labels appear when zoomed in.
- Not supported: binary DXF, DWG, block inserts (INSERT), splines, hatches, and arc bulges on polylines (drawn straight).
- Coordinates are read in the coordinate system selected in Settings (units such as US survey feet are handled by the projection). If the drawing lands in the wrong place or the import fails, remove it and re-import with the correct system.
- Overlays are saved inside the app and reload at startup. Use the checkbox to hide one, or *Remove* to delete it.

## Averaging (stationary recordings)

When you press **STOP** and the antenna stayed put, the app saves a **weighted average** position:

- "Stayed put" means 95% of the fixes are within the *stationary threshold* of the average (default **1.0 m**, set in Settings) and there are at least 5 fixes.
- Weights favor quality: RTK fixed counts most, RTK float much less, DGPS/GPS barely at all, each divided by HDOP².
- The result is appended to `gnss_averages.csv` and drawn as a **diamond** in the recording's color. The status bar reports the number of fixes and the standard deviation, or "Moved too much to average" with the measured radius.

## Building

Requirements: Android Studio (Hedgehog or newer) or JDK 17 with Gradle 8.x, Android SDK 34, and internet access for the first sync (dependencies come from Maven Central and JitPack).

```
# Android Studio: File > Open the GnssLogger folder, then Run
# Command line:
gradle assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` must contain `android.useAndroidX=true` (already included), because the USB serial library depends on AndroidX.

To install on the phone, enable Developer options and USB debugging and press Run, or copy the APK over and allow installs from unknown sources.

Libraries: [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), [osmdroid](https://github.com/osmdroid/osmdroid), [proj4j](https://github.com/locationtech/proj4j).

## Limitations

- Recording continues while you switch between the map and Settings, but there is **no background service**: the screen is kept on while the app is open, and locking the phone or leaving the app may stop recording.
- Map tiles need internet (previously viewed tiles are cached). Recording and logging work offline except for NTRIP.
- Bluetooth support is classic SPP only (not BLE receivers).
- To keep the map fast, a new dot is drawn only after you move about 1 cm or the fix type changes. The CSV still gets every fix.
- The app does not run on-device compile checks or unit tests yet. Treat it as a starting point.

## Project layout

```
app/src/main/java/com/example/gnsslogger/
  MainActivity.kt      map screen, REC/MARK buttons, point overlay
  SettingsActivity.kt  receiver, NTRIP, coordinate system, averaging, DXF import
  Session.kt           receiver link (USB/Bluetooth), NTRIP client, NMEA parsing, CSV logging, averaging
  Proj.kt              coordinate system handling (proj4j)
  Dxf.kt               ASCII DXF parser and map overlay
docs/                  mockup screens used in this README
```

## Troubleshooting

| Problem | Try |
|---|---|
| No devices listed | Plug in or pair the receiver, then tap *Refresh device list* |
| "USB permission denied" | Replug the receiver and accept the Android prompt |
| NTRIP "caster replied: 401" | Check username, password and mountpoint |
| Fix stays GPS, never RTK | Check the receiver accepts RTCM3 on the same port, and that NTRIP shows *streaming* |
| Network/VRS mountpoint gives no data | Make sure GGA output is enabled; the app sends it to the caster every 10 s |
| DXF in the wrong place | Remove it, set the correct coordinate system, re-import |
