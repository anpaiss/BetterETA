# BetterETA

A [Hammerhead Karoo 3](https://www.hammerhead.io/karoo) extension that computes a **smarter ETA for the route you are navigating**: it learns how fast *you* ride at each gradient and uses that model to estimate the time for every remaining segment of the route — instead of a simple distance/average-speed guess. The learning is bike-agnostic; only the cold-start fallback factors (used until a gradient bucket has real samples) are tuned for e-bikes.

---

## Data Fields

The extension registers five data fields you can add to any ride profile:

| Field | Content |
|---|---|
| **BetterETA Dashboard** | Full-page view: arrival time, time remaining, km remaining, ascent remaining, predicted average speed, pace vs plan, mandatory arrival time and margin |
| **ETA (Better)** | Arrival time only (HH:mm) |
| **Time Remaining (Better)** | Time remaining only (Xh Ym) |
| **Distance to Farthest Point** | Distance left to the point geographically farthest from the start (the turnaround of an out-and-back) |
| **Arrival Margin (MTA)** | Minutes of slack against the mandatory arrival time set at ride start (`+12 min` green, `-5 min` red, `--` when none is set) |

The MTA row only exists while a mandatory arrival time is set; without one the dashboard uses its original, larger layout. The Karoo draws its turn-by-turn "next cue" ticket over the bottom of the screen without notifying extensions; the compact layout is sized so its last row still clears that band on the Karoo 3.

---

## How It Works

### Adaptive speed model
- Five gradient buckets: descent (< -5%), flat (-5..+2%), climb (+2..+6%), steep (+6..+10%), wall (> +10%).
- Each bucket keeps an exponential moving average of your observed speed, sampled at 1 Hz while recording. Cumulative mean for the first 1200 samples, then an EMA with an equivalent window of **~20 minutes of riding at that gradient** — stable against traffic lights and short stops, still adaptive to how your legs feel today.
- The model is persisted across rides and shown in the extension's app page.
- Buckets without enough samples fall back to observed flat speed × a per-gradient factor (these factors are the only e-bike-specific tuning; on a regular bike the model simply learns different bucket speeds).

### Physics fallback (shadow, under evaluation)
A second fallback runs alongside the factors and produces a shadow ETA that is **never shown in-ride**. It uses the cycling power equation `P = ½·CdA·ρ·v³ + m·g·(s + Crr)·v`, but the power is not measured: it is inferred from the speed and gradient we already sample every second, so on an e-bike it already contains the motor. Two averages are kept, split at the ~25 km/h assist cutoff, so the pedelec's piecewise behaviour falls out of the data without any "is it an e-bike" setting. Mass and CdA are fitted parameters and cancel within a regime; they only matter when extrapolating flat↔climb, which is exactly what the hand-tuned factors do today. Descents stay with the learned buckets — the equation returns a coasting speed there, and riders brake.

Both fallbacks share the same learned buckets, so the fallback is the only variable. The shadow ETA is judged at the end of every ride by the same harness as the Karoo comparison below, and the app page shows the result. The promotion criterion was fixed before any data was collected: at least 5 rides, mean error at least 2 pp lower than the factors, never more than 3 pp worse on a single ride.

### Route & position tracking
- Routes arrive from the Karoo navigation state (`NavigatingRoute` / `NavigatingToDestination`); coordinate and elevation polylines are decoded, elevations smoothed over a 50 m window, and waypoints compressed into (distance, gradient) segments.
- Position on the route = **dead reckoning** (speed × time) corrected every second by **GPS snap**, searching a window centred on the estimated position and picking the candidate most coherent with it — so out-and-back roads and loops don't teleport the rider to the wrong branch.
- If the fix stays outside the local window (route loaded mid-ride, long detour), a **global rescue** re-anchors the position — but only after consecutive coherent proposals *and* only if the implied jump is physically plausible (elapsed wall-clock time × max credible speed). A 32 km "jump" after 40 seconds off-track gets rejected; a 2.5 km jump after a 20-minute stop with the Karoo auto-paused gets accepted.
- If GPS goes silent for more than 10 s the extension keeps dead reckoning and flags the state.
- Remaining ascent sums **every** positive elevation delta of the segments ahead. The 50 m elevation smoothing already removes the polyline noise, so the old "count only segments at ≥ 1%" rule was double protection that quietly dropped every false flat and under-reported the climbing left. Both figures go to the sample log, next to the Karoo's own `ASCENT_REMAINING` and its climb list.

### Display refresh
The engine recomputes once a second, but each field redraws only when the text it shows actually changes, only while it is on the ride page the user is looking at, and at most once per its own interval (20 s for the time fields, 10 s for the distance, 5 s for the dashboard). On e-ink every redraw costs battery and leaves ghosting. The visibility gate fails open: if the active page is unknown, everything draws — a gate that wrongly filtered a field out would freeze it for a whole ride and fail silently.

### Mandatory time of arrival (MTA)

When recording starts with a route loaded (or a route is loaded while already recording), a popup asks for a mandatory arrival time: two wheels, hours and minutes in 15-minute steps, preset to the current ETA rounded up to the quarter hour, with **SET** and **DISMISS** (auto-dismiss after 45 s = no MTA). It is asked once per route — never again on the Karoo's pause/resume churn or after the extension restarts mid-ride, but the same route on a later day asks again. A recalculation towards the same destination keeps the MTA; a new destination clears it and asks again.

The margin is `MTA − (now + time remaining)`, recomputed every second **including while paused**: the ETA is moving time, so a stop that drags on eats the margin exactly as it eats the clock. The dashboard shows MTA and margin side by side in a last row that exists only while an MTA is set — dismiss the popup and the page keeps its original, larger layout (the label reads AHEAD MIN / LATE MIN, the value is coloured in every style); the standalone field shows the same. An in-ride alert fires the first time the margin turns negative and re-arms only once it climbs back above two minutes.

The popup is an overlay window, so it needs the "draw over other apps" permission: grant it from the app page (**Allow popup**). Without it no popup is shown and the log says so.

### Surface classification

Each waypoint is classified PAVED / UNPAVED from the OpenStreetMap ways along the route (`surface=*`, falling back to `highway=*`), fetched from the Overpass API in a 25 m corridor around the route. The speed model keeps per-surface gradient buckets on top of the global ones, so the same 6 % climb on gravel and on tarmac get their own learned speeds once each has a few samples.

The cache is **geographic, not per route**: it stores the fetched ways (geometry + surface) and the stretches of route they were fetched for. A new route is first matched against every cached way; only the stretches never queried go to Overpass, as one request. So a recalculated polyline, or a new route that shares a climb with an old one, reuses everything already known and, when offline, only the genuinely new stretch stays unknown (the classification is then marked partial and retried when the Karoo re-emits the route). Oldest fetches are dropped past ~200k geometry points (a few MB).

Overpass needs connectivity at route load (Wi-Fi or phone tethering); nothing in the ride depends on it afterwards. The Karoo's own offline maps do carry surface tags, but the karoo-ext SDK does not expose them and Android 12's scoped storage blocks reading the map files directly.

### Pace vs plan
At the first valid ETA of the ride (with GPS locked) the predicted riding time is frozen as a baseline. From then on the dashboard shows the delta between current prediction and baseline, **counting moving time only** — stops do not move it. Green = ahead of plan, red = behind.

### ETA quality tracking (vs the Karoo's native ETA)
Once per minute the extension samples its own remaining-time prediction alongside the Karoo's native `TIME_TO_DESTINATION`. When the route is completed, every sample is judged against the moving time that was actually needed from that point to the finish. The result — how many percentage points of error BetterETA saved (or added) compared to the stock ETA, and how the shadow physics fallback would have done on the same samples — is stored per ride. The extension's app page shows the averages and the last 20 rides.

---

## Requirements

- Hammerhead Karoo 3 (Hardware ID: k24)
- Karoo firmware with extension support

Two RemoteViews details learned on the Karoo 3: the ride app ignores `setViewVisibility` and `setTextViewTextSize`, so a view that needs a different shape has to switch to a different layout resource; and after several rapid reinstalls the ride app may stop re-attaching the extension's views (it keeps showing the last picture) — a reboot fixes it.
- A route with elevation data (e.g. Komoot) for ascent-aware estimates; routes without elevation still get distance-based ETA

---

## Installation

Build and install via ADB:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then add the BetterETA fields to a ride profile from the Karoo page editor.

---

## Releases & CI

Every push runs the unit tests and builds a debug APK (downloadable from the workflow run). Pushing a tag `vX.Y` builds a **signed** release APK and attaches it, together with the Karoo extension manifest, to a GitHub release.

**This repository is private, so the Karoo cannot fetch anything from it.** The `MANIFEST_URL` meta-data (`.../releases/latest/download/manifest.json`) and the screenshot URLs in the manifest are inert: the Karoo gets a 404 and offers no install or update. Installation stays manual — download the release APK while logged in and `adb install -r` it. Everything is wired so that the extension-library flow starts working by itself if the repository is ever made public; nothing else would need to change.

Repository secrets for signing: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Create the keystore once and keep it — a different key means the Karoo will refuse the update:

```bash
keytool -genkeypair -v -keystore bettereta.keystore -alias bettereta -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 bettereta.keystore   # → KEYSTORE_BASE64
```

The `karoo-ext` library is served from GitHub Packages, which always needs a token: the workflow uses `GITHUB_TOKEN`, and falls back to a `GPR_TOKEN` secret (a personal access token with `read:packages`) if that turns out not to be enough. Locally, `gpr.user` / `gpr.key` in `~/.gradle/gradle.properties` do the same job.

`versionCode` is `100 + run number` in CI and `versionName` comes from the tag; local builds keep `3` / `1.0`.

---

## Diagnostics

The extension writes two logs on the device.

**Human-readable log** — rotating at 1 MB with one `.old` generation:

```bash
adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta.log
```

Tagged lines cover route loading (`ROUTE`), GPS snap/rescue (`GPS`), ride state (`RIDE`), model save/restore (`MODEL`), surface classification (`SURF`), the mandatory arrival time popup, value and alert (`MTA`), which fields are on the visible page (`VIEW`), one ETA snapshot per minute (`ETA`) and end-of-ride comparison results (`STATS`).

**Structured sample log** — one CSV row per second while recording, rotating at 4 MB:

```bash
adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta_samples.csv
```

Every quantity the extension computes sits next to the Karoo's own answer for the same thing: our position along the route against `routeDistance - DISTANCE_TO_DESTINATION`, our remaining ascent against `ASCENT_REMAINING` and against the route's climb list, our ETA against `TIME_TO_DESTINATION`. That is what makes changes to the model judgeable after a ride rather than a matter of opinion. Columns for values not produced yet are present and empty, so files recorded weeks apart stay comparable and can be concatenated. `eta_physics_s` is the shadow ETA and `model_rung` the share (0..1) of the remaining distance whose speed came from the fallback rather than a learned bucket — if that share is small, no fallback can move the ETA much. Rides are separated by `# ride ...` marker lines. Numbers always use a dot as the decimal separator, whatever the device locale.

---

## Development Notes

- **Extension ID**: `better-eta`, package `com.anpaiss.bettereta`.
- **SDK**: `io.hammerhead:karoo-ext` 1.1.9.
- Model and ride position are deliberately **never reset on `RideState` transitions**: the Karoo cycles Recording↔paused dozens of times per ride (auto-pause), and transient `Idle` navigation states keep the route in memory. Resets happen only when a genuinely different route polyline arrives.
- The Karoo SDK exposes **no safe-area/inset API** and no event for the navigation cue overlay — verified against karoo-ext 1.1.9. Layouts must keep the bottom of full-page fields expendable.
- Unit tests cover the speed model, the ETA engine, polyline decoding and the segment pipeline (including GPS snap on out-and-back routes, loop rescue coherence and the plausibility guard): `./gradlew :app:testDebugUnitTest`.

---

## Screenshots

<table>
  <tr>
    <td><img width="240" alt="BetterETA dashboard" src="docs/dashboard.png" /></td>
    <td><img width="240" alt="App page — learned speed model" src="docs/app-info-1.png" /></td>
    <td><img width="240" alt="App page — ETA vs Karoo history" src="docs/app-info-2.png" /></td>
  </tr>
</table>
