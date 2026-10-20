# Varia Lanes for Hammerhead Karoo

Karoo extension that reads lane, vehicle size and lane-change data from a Garmin Varia RearVue 820 over Bluetooth and shows it as a top-down **Lane View** data field.

Bluetooth decoding is ported from [rale/radarble](https://github.com/rale/radarble). This is unofficial and reverse-engineered: Garmin firmware updates could break it. Keep the Karoo's own ANT+ radar running as your main alert.

## What you get

- **Lane View** (graphical field): you at the top, vehicles coming up from below by distance and placed by sideways offset. One lane drawn to your left, three to your right. A setting puts you at the bottom instead.
  - Block size follows vehicle length/width from the radar.
  - Red = closing fast (≥ ~40 km/h faster than you, or under 3 s away), amber = closing, grey = same speed.
  - Arrow beside a vehicle = moving sideways (changing lanes).
  - "+25" label = km/h faster than you (on taller fields).
  - Status line: Clear / N behind / RADAR BLOCKED / connection status.
- **Cars Behind** (number field).
- **Varia Lanes app** on the Karoo: Bluetooth permission, settings, and a live table of raw radar values for checking left/right.

Limits: the Karoo accepts about one graphical update per second, so the view refreshes at 1 Hz. Lane lines assume you're mid-lane and a set lane width, so they're approximate.

## Build (no Android Studio needed)

1. Put this folder in a **public** GitHub repo (public so the Karoo can download the APK).
2. GitHub Actions builds it on every push to `main`. The workflow uses the repo's built-in token to download Hammerhead's library, so no personal token is needed.
3. The APK appears under **Releases**. This link always points to the newest one:
   `https://github.com/<you>/<repo>/releases/latest/download/varia-lanes.apk`

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

## Files

- `radar/RadarProtocol.kt` – packet decoder (tested against radarble's logic)
- `radar/TargetTracker.kt` – keeps vehicles between packets, works out closing speed
- `VariaRadarDevice.kt` – Bluetooth pairing, connection, reconnects
- `LaneRenderer.kt` – draws the field
- `MainActivity.kt` – on-Karoo settings and live readout
