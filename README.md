# BetterETA

A [Hammerhead Karoo 3](https://www.hammerhead.io/karoo) extension that computes a **smarter ETA for the route you are navigating**: it learns how fast *you* ride at each gradient and uses that model to estimate the time for every remaining segment of the route — instead of a simple distance/average-speed guess. The learning is bike-agnostic; only the cold-start fallback factors (used until a gradient bucket has real samples) are tuned for e-bikes.

---

## Data Fields

The extension registers four data fields you can add to any ride profile:

| Field | Content |
|---|---|
| **BetterETA Dashboard** | Full-page view: arrival time, time remaining, km remaining, ascent remaining, predicted average speed, pace vs plan |
| **ETA (Better)** | Arrival time only (HH:mm) |
| **Time Remaining (Better)** | Time remaining only (Xh Ym) |
| **Distance to Farthest Point** | Distance left to the point geographically farthest from the start (the turnaround of an out-and-back) |

The dashboard keeps the bottom band of the page empty on purpose: the Karoo draws its turn-by-turn "next cue" ticket over the bottom of the screen without notifying extensions, so nothing important lives there.

---

## How It Works

### Adaptive speed model
- Five gradient buckets: descent (< -5%), flat (-5..+2%), climb (+2..+6%), steep (+6..+10%), wall (> +10%).
- Each bucket keeps an exponential moving average of your observed speed, sampled at 1 Hz while recording. Cumulative mean for the first 1200 samples, then an EMA with an equivalent window of **~20 minutes of riding at that gradient** — stable against traffic lights and short stops, still adaptive to how your legs feel today.
- The model is persisted across rides and shown in the extension's app page.
- Buckets without enough samples fall back to observed flat speed × a per-gradient factor (these factors are the only e-bike-specific tuning; on a regular bike the model simply learns different bucket speeds).

### Route & position tracking
- Routes arrive from the Karoo navigation state (`NavigatingRoute` / `NavigatingToDestination`); coordinate and elevation polylines are decoded, elevations smoothed over a 50 m window, and waypoints compressed into (distance, gradient) segments.
- Position on the route = **dead reckoning** (speed × time) corrected every second by **GPS snap**, searching a window centred on the estimated position and picking the candidate most coherent with it — so out-and-back roads and loops don't teleport the rider to the wrong branch.
- If the fix stays outside the local window (route loaded mid-ride, long detour), a **global rescue** re-anchors the position — but only after consecutive coherent proposals *and* only if the implied jump is physically plausible (elapsed wall-clock time × max credible speed). A 32 km "jump" after 40 seconds off-track gets rejected; a 2.5 km jump after a 20-minute stop with the Karoo auto-paused gets accepted.
- If GPS goes silent for more than 10 s the extension keeps dead reckoning and flags the state.
- Remaining ascent sums **every** positive elevation delta of the segments ahead. The 50 m elevation smoothing already removes the polyline noise, so the old "count only segments at ≥ 1%" rule was double protection that quietly dropped every false flat and under-reported the climbing left. Both figures go to the sample log, next to the Karoo's own `ASCENT_REMAINING` and its climb list.

### Display refresh
The engine recomputes once a second, but each field redraws only when the text it shows actually changes, only while it is on the ride page the user is looking at, and at most once per its own interval (20 s for the time fields, 10 s for the distance, 5 s for the dashboard). On e-ink every redraw costs battery and leaves ghosting. The visibility gate fails open: if the active page is unknown, everything draws — a gate that wrongly filtered a field out would freeze it for a whole ride and fail silently.

### Pace vs plan
At the first valid ETA of the ride (with GPS locked) the predicted riding time is frozen as a baseline. From then on the dashboard shows the delta between current prediction and baseline, **counting moving time only** — stops do not move it. Green = ahead of plan, red = behind.

### ETA quality tracking (vs the Karoo's native ETA)
Once per minute the extension samples its own remaining-time prediction alongside the Karoo's native `TIME_TO_DESTINATION`. When the route is completed, every sample is judged against the moving time that was actually needed from that point to the finish. The result — how many percentage points of error BetterETA saved (or added) compared to the stock ETA — is stored per ride. The extension's app page shows the average and the last 20 rides.

---

## Requirements

- Hammerhead Karoo 3 (Hardware ID: k24)
- Karoo firmware with extension support
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

## Diagnostics

The extension writes two logs on the device.

**Human-readable log** — rotating at 1 MB with one `.old` generation:

```bash
adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta.log
```

Tagged lines cover route loading (`ROUTE`), GPS snap/rescue (`GPS`), ride state (`RIDE`), model save/restore (`MODEL`), surface classification (`SURF`), which fields are on the visible page (`VIEW`), one ETA snapshot per minute (`ETA`) and end-of-ride comparison results (`STATS`).

**Structured sample log** — one CSV row per second while recording, rotating at 4 MB:

```bash
adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta_samples.csv
```

Every quantity the extension computes sits next to the Karoo's own answer for the same thing: our position along the route against `routeDistance - DISTANCE_TO_DESTINATION`, our remaining ascent against `ASCENT_REMAINING` and against the route's climb list, our ETA against `TIME_TO_DESTINATION`. That is what makes changes to the model judgeable after a ride rather than a matter of opinion. Columns for values not produced yet are present and empty, so files recorded weeks apart stay comparable and can be concatenated. Rides are separated by `# ride ...` marker lines. Numbers always use a dot as the decimal separator, whatever the device locale.

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
