# Varia Lanes for Hammerhead Karoo

> ⚠️ **BETA: still being tested.** This is an early, unofficial build that has only had limited real-world testing on one Karoo 3 and one Varia RearVue 820. Lane positions, lane-change detection and alerts may be wrong, late or missing. **Don't rely on it for your safety.** Keep the Karoo's normal radar alerts on and always look behind before moving across.

Karoo extension that reads lane, vehicle size and lane-change data from a Garmin Varia RearVue 820 over Bluetooth and shows it as a top-down **Lane View** data field.

The radar's Bluetooth service IDs come from [rale/radarble](https://github.com/rale/radarble). The data layout was then worked out from recorded rides and differs from radarble's (little-endian position bytes, speed in byte 7, updates split across packets); sideways 0.1 m and distance 0.1 m. The sideways scale was calibrated on a road-only section of a ride; see `RadarProtocol.kt`. This is unofficial and reverse-engineered: Garmin firmware updates could break it. Keep the Karoo's own ANT+ radar running as your main alert.

## What you get

- **Lane View** (graphical field), minimal, for riding on the left (Australia): two lanes only. Left = behind you, right = the lane next to you. Each vehicle is a plain marker at its distance, snapped into one lane (it has to be clearly past the lane line before it's redrawn in the other lane, so it doesn't flicker). A red arrow (and flashing red marker) = moving in towards you; a blue arrow = moving out. No numbers or other information. Vehicles beyond the next lane aren't shown.
- **Full-screen live radar:** radar (redrawn about 20 times a second, vehicles glide smoothly between radar updates) on the right half of the screen, with three big stat tiles on the left: 5 s power, cadence and heart rate, live from the Karoo. Open it by tapping the Lane View field, from a Karoo button action ("Open live radar"), or from the app. Back returns to your ride.
- **Close-pass warning:** a vehicle within 15 m, closing at least 10 km/h faster than you, less than about 2.7 m to your right (roughly a 1.5 m gap or less) and not pulling out, flashes red on screen and pops up "NOT MOVING OUT" with two long beeps. This is the only alert that beeps. Tested on the road section of the 8 Nov ride: caught 16 of 21 passes that ended up that close, with 11 false alarms among 24 wider passes, about 3 s warning. Drivers often move out very late, so it can't be perfect.
- **Moving in / pulling out** are shown on screen only (red / blue arrows), with no pop-up or beep.
- **Ride recording** (for troubleshooting, on by default): every raw radar packet and Bluetooth event is saved with a timestamp to `/sdcard/Android/data/dev.varialanes/files/rides/varia-*.csv` on the Karoo. Copy off with `adb pull /sdcard/Android/data/dev.varialanes/files/rides`. Keeps the newest 20 files.
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
