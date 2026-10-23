# Varia Lanes for Hammerhead Karoo

Karoo extension that reads lane, vehicle size and lane-change data from a Garmin Varia RearVue 820 over Bluetooth and shows it as a top-down **Lane View** data field.

Bluetooth decoding is ported from [rale/radarble](https://github.com/rale/radarble). This is unofficial and reverse-engineered: Garmin firmware updates could break it. Keep the Karoo's own ANT+ radar running as your main alert.

## What you get

- **Lane View** (graphical field), laid out for riding on the left (Australia): kerb on the left, your lane, and the overtaking lane to your right. You're at the top with vehicles coming up from below by distance. A tall, narrow road, best on its own page. Settings for your distance from the road edge (0.5 / 1 / 1.5 m), lane width, and putting you at the bottom instead.
  - Block size follows vehicle length/width from the radar.
  - Red = closing fast (≥ ~40 km/h faster than you, or under 3 s away), amber = closing, grey = same speed.
  - **Moving in** (left, towards you): big red arrow, thick outline, red band across your lane at that distance, and a flashing red "« MOVING IN 60 m" banner at the top.
  - **Moving out** (right, pulling out to pass): big blue arrow, blue outline and a blue "MOVING OUT » 40 m" banner.
  - Lane changes are detected from the radar's sideways speed and from the vehicle's sideways position over the last 1.5 s, and the warning is held for 2 s so the once-a-second display can't miss it.
  - "+25" label = km/h faster than you (on taller fields).
  - Status line (top of the field): Clear / N behind / RADAR BLOCKED / connection status.
- **Moving-in pop-up alert:** when a vehicle starts moving in towards you within a set distance (default 40 m), the Karoo shows a red "CAR MOVING IN" pop-up with distance and speed, and beeps three times. Same vehicle won't re-alert within 8 s. On/off, distance (25/40/60 m), beep on/off and a Test button are in the app.
- **Cars Behind** (number field).
- **Varia Lanes app** on the Karoo: Bluetooth permission, settings, and a live table of raw radar values for checking left/right.

Limits: the Karoo accepts about one graphical update per second, so the view refreshes at 1 Hz. Lane lines come from your set distance from the road edge and lane width, so they're approximate. Vehicle lengths are exaggerated so they're visible, but kept in proportion (a truck is drawn longer than a car).

## Build (no Android Studio needed)

1. Put this folder in a **public** GitHub repo (public so the Karoo can download the APK).
2. GitHub Actions builds it on every push to `main`. The workflow uses the repo's built-in token to download Hammerhead's library, so no personal token is needed.
3. The APK appears under **Releases**. This link always points to the newest one:
   `https://github.com/Shawzus/karoo-varia-lane/releases/latest/download/varia-lanes.apk`

Local build instead: install Android Studio, add `gpr.user=<github user>` and `gpr.key=<token with read:packages>` to `local.properties`, then `./gradlew assembleRelease`.

## Install and pair

1. Sideload the APK with the Hammerhead Companion app (Karoo on Wi-Fi, Companion paired).
2. On the Karoo open **Varia Lanes** and allow Bluetooth.
3. Close Cadence/the Varia app on your phone, so the 820 isn't busy with another Bluetooth connection.
4. Turn the 820 off, then hold its button ~2 s to power on in pairing mode (purple LED).
5. Karoo **Sensors → Add sensor**, pick **Varia Lanes …**. The first connect pairs (bonds) with the radar.
6. Add **Lane View** to a ride page.

## Check left/right

The radar's sideways sign convention isn't documented. On your first ride, watch a car overtake on your right. If it appears on the left, turn on **Flip left/right** in the Varia Lanes app.

## Credits

- **Varia 820 Bluetooth protocol: [rale/radarble](https://github.com/rale/radarble) by rale.** All of the reverse-engineering of the 820's radar data (the Bluetooth service and characteristic IDs, packet layout, and how lane offset, distance, size and speed are encoded) is rale's work. `RadarProtocol.kt` is a Kotlin port of radarble's `radar.py` decoding, and its tests check the port gives the same results as the original.
- **[Hammerhead karoo-ext](https://github.com/hammerheadnav/karoo-ext)** (Apache 2.0): the extension library this app is built on. Its sample extension was the reference for the extension, sensor and data field structure.
- **[timklge/karoo-headwind](https://github.com/timklge/karoo-headwind)** (Apache 2.0): reference for the GitHub Actions build and graphical data fields. No code copied.

radarble doesn't include a licence file. This project credits it in full and is a personal, non-commercial project. If you're rale and would like anything changed, please open an issue.

## How this was made

Built by Shawzus with [Claude Code](https://claude.com/claude-code) (Anthropic's AI coding assistant). Apart from rale's protocol work credited above, the code was written with Claude Code: porting the decoder to Kotlin, the Bluetooth pairing and connection, the vehicle tracking, the Lane View drawing, the settings app and the GitHub build. It was tested by unit tests and by comparing the decoder with radarble on 3,000 generated packets. It has not been independently reviewed, so treat it as experimental.

## Files

- `radar/RadarProtocol.kt` – packet decoder (tested against radarble's logic)
- `radar/TargetTracker.kt` – keeps vehicles between packets, works out closing speed
- `VariaRadarDevice.kt` – Bluetooth pairing, connection, reconnects
- `LaneRenderer.kt` – draws the field
- `MainActivity.kt` – on-Karoo settings and live readout
