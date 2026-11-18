# Wear drawing and animation: plan for a generic engine

Status: proposal, not started. Untracked file, not for commit.

## 1. What we have now (review summary)

About 5600 lines in `wear/.../watchfaces/animation`, plus `daynight/`, `colour/`, new `utils/` files and
~390 added lines in the upstream file `CustomWatchface.kt`.

Good parts, to keep:
- Designs are pure data. The formula language (`Formula.kt`) is close to Google's Watch Face Format
  expressions, is parsed once and folds constants.
- Generic layers already exist: image, shape, view sources, copies (count, slices, points), clip, mask,
  blend, glow, rest layers.
- The presets (rain, glitch, pulse, blink, sweep, slide, wobble, trace, glint) already expand to generic
  layers in `AnimPresets.kt`. So the core idea is right; it is the layering around it that grew special.

Problems found:

| Area | Problem |
|---|---|
| Presets | Each of 9 presets lives in 4 places: data class, `normalized()` limits, JSON reader branch, generator. |
| Feature flags | `"moon": true` layer source, `moonLight: true` view flag, `orbit` view key, `@time` token, `graphLineWidth`: each a special code path. |
| Looks | `dayLook`, `weatherLooks`, `colourLooks`, the `XDay` image suffix: three mechanisms and three identity caches for one idea ("patch the design when X"). |
| Values | Two variable systems: `ValueMap` keys (used by `dynData`, `orbit`) and formula variables `v_*`. Each value read builds a new `DynProvider(…, JSONObject(), …)` and scans `ValueMap` linearly. Moon geometry (`MoonLight.at`) is not exposed, so Aurora Town approximates it by hand. |
| Time | `t` and `ms` are the same value; `WakeTimer` clock vs wall clock; `restSeconds` is a third time; per-preset delay windows written in formula text. |
| Views | View effects only support rotation, X shift, on/off alpha and a right-edge clip. They work by changing `View.rotation`/`translationX` and hiding other views; per frame it builds maps, sets and `Triple` lists and scans `ViewMap` + `findViewById`. |
| Caches | `WakeFrameLayers`/`KeptLayer` (3 full-screen bitmaps) and `LayeredFrameCache` (2 more) do the same job with different keys. Background is redrawn every minute. Bitmaps stay after a wake ends. |
| Formula speed | Function calls box every result (`(DoubleArray) -> Double`). No shared sub-expressions; copy-invariant and time-invariant formulas are evaluated per copy per frame. |
| Per frame | Every layer does save + round clip + rings clip (+ `saveLayer` for masks) even when all copies are invisible. Rest path re-reads all values and calls `prepare()` on every normal frame. |
| Moon | Two `MoonPicture` objects, each decodes the photo; CPU per-pixel loop; new `BitmapDrawable` every minute. |
| Randomness | `r1..r8` come from `Random(seed)`, where the seed of a non-count layer is its index in the combined list: adding a preset changes the random values of other layers. |
| Design workarounds | Aurora: one formula copied into 4 layers only because points cannot carry a phase; the lit fraction formula written 3 times; moon turn approximated. D2: 192-entry `rotationOffset` tables to fake a log scale. Grand/Tech: second views on the same orbit to make a cloud follow the sun or moon. |

## 2. Target model

Same idea as Lottie, Watch Face Format and After Effects: a small set of primitives where every property is
an expression, and named derived operations that expand to those primitives at load time.

### 2.1 Primitives (the only things the renderer knows)

- **Layer** = source + transform + paint + clip + copies + time.
- **Sources**: `image` (zip resource, or an app-provided dynamic image such as `@moon`), `shape`
  (`rect`, `circle`, `line`; fill = colour, linear gradient or radial gradient), `view` (an existing face view).
- **Transform**: `x`, `y`, `rotation`, `scaleX`, `scaleY`, `pivotX`, `pivotY`.
- **Paint**: `alpha`, `colorIndex` (palette), `blend` (normal, add, screen), `glow`.
- **Clip**: a list of shapes (rect, circle, ring, rounded box) and/or a mask image.
- **Copies**: `count` + `seed`, `points` (each point may carry extra numbers `p0..`), `slices`.
- **Value** of every property: number, or formula. Formulas may use per-layer named sub-expressions
  (`"let": {"lit": "(1-cos(2*pi*v_moon_phase))/2"}`).
- **Visible window**: optional layer `visible` formula, checked before any canvas work.

What disappears as a special case:
- `BAND` = rect + horizontal linear gradient. `DOT` = circle + radial gradient with a solid core.
- `moon: true` = `"image": "@moon"`. `moonLight: true` on a view = the view shows `@moon`.
- `clipRight` on views = a view layer with a clip rect whose right edge is a formula.
- `rings`/`boxes` stay, but as clip shapes any layer can use.

### 2.2 One value registry

All data sources in one table, computed once per frame (fast ones) or once per minute (sky, weather):
upstream `ValueMap` keys + `watch_battery`, `moon_phase`, `sun_progress`, `night_progress`, `sun_cycle`,
`moon_cycle`, `weather_temperature`, `sun_next_time`, `sensor_left`, `pump_left`, and new ones:
`is_day`, `weather_kind`, `moon_lit`, `moon_limb`, `moon_north`, `moon_terminator`, `sun_altitude`.
`dynData.valueKey`, `orbit.valueKey`, formula `v_*` and look conditions all read from it.
The exact astronomy maths stays in `daynight/` (kept on the platform, as now), only exposed as values.

### 2.3 One time model

One frame context: `t` (seconds since wake), `len`, `env`, wall clock `sec/min/hour`, `bpm`, values.
Rest frames are the same path evaluated at a fixed `t` with `env = 0`. New formula helpers so designs stop
writing windows by hand: `window(t, start, dur)` (0..1), `pingpong`, `ease(u)`, keep `settle`, `pulse`.
`ms` stays as an alias (`t*1000`) so old formulas still parse.

### 2.4 Derived operations as data (templates)

A template is layer JSON with `$name` parameters, defaults and limits. Built-in templates ship as one JSON
file in the app; a design can also define its own `templates`. Use: `{"use": "rain", "count": 90, ...}`.
All 9 presets become built-in templates. The old preset keys (`"rain": {...}` etc.) are read as
`use` of the same template, so existing zips keep working. This removes the 9 data classes, readers,
`normalized()` copies and `AnimPresets.kt`.

### 2.5 Views as real layers, also outside a wake

A view layer gets the full transform, alpha and clip, drawn with a canvas matrix instead of changing
`View` properties. Key -> View maps are built once per design. View transforms may also apply in normal
frames (`"always": true`), which makes `orbit` just sugar for `x/y` formulas, and lets a cloud follow
the sun without a second orbit view.

### 2.6 One look mechanism

`looks: [{"when": "<formula>", "patch": {...}, "images": {...}}]`, applied in order, cached per
(design, condition result vector). `dayLook`, `weatherLooks`, `colourLooks` and the `XDay` suffix are
read as sugar for it.

### 2.7 Efficiency

- Formula: primitive function interface (no boxing); classify each formula as constant / per wake /
  per frame / per copy and evaluate it only that often; skip pivot maths for identity transforms.
- Random `rN` = `hash(seed, i, N)` (stateless, no index coupling). Needs a decision, see 4.
- One frame cache for normal and wake frames (merge `WakeFrameLayers` into `LayeredFrameCache`);
  background keyed on content only; bitmaps freed after a wake.
- Round-screen clip once per slot, not per layer; no `saveLayer` for invisible layers.
- One shared `MoonPicture`; the phone preview reuses bitmaps instead of one new bitmap per frame.
- Move logic out of the upstream `CustomWatchface.kt` into own files (smaller personal patch).

## 3. Phases

Each phase ends green on: AAPS wear + cwfrender + plugins/sync tests, and the aaps-watchfaces design
tests run against this worktree (`-PaapsDir=...`). Before phase 1 we record a pixel baseline: every
design rendered at fixed instants (rest frame and several wake frames). Each phase must match it.

1. **Core cleanup, no design change.** Value registry, time context, formula hot path, `let`,
   caches merged, per-frame allocations removed, view effect maps precomputed. Design JSON unchanged.
2. **Generic primitives.** Gradient fills, clip shapes on any layer, point extras, `@moon` dynamic
   image, view layers with full transform (+ always), new sky values, `looks`, templates. Old keys read
   as sugar. Pixel baseline must still match.
3. **Port the designs.** Generators in aaps-watchfaces emit the new form (templates, `let`, `@moon`,
   point phases, real moon values instead of hand approximations). Regenerate zips, check size limit
   and compare pixels. Some pixels change on purpose where a hand approximation is replaced; we show
   those side by side.
4. **Merge** to `local/personal` (`--no-ff`, own `local/wear-anim-generic` branch), after the user
   tests the preview and the watch.

Not touched: upstream `CwfFramePipeline`, the upstream CWF keys and the 14 bundled AAPS designs (they
use none of the new keys; their path stays as is).

## 4. Decisions (user, 2026-11-18)

1. **Port and delete.** Old personal keys (the 9 presets, `moon`, `moonLight`, `orbit`, `dayLook`,
   `weatherLooks`, `XDay` suffix) are removed from the engine; every design is regenerated in the new form.
   Zips already on the watch must be sent again after the update. Upstream CWF keys are not touched.
2. **TypeScript designer later**, as a separate task.
3. **Random = stateless hash** (`rN = hash(seed, i, N)`); new snow/rain/glitch positions are accepted.
4. **Do all phases**; no commits until the user asks; merge waits for the user.

## 5. Working setup

- AAPS: this worktree, branch `claude/animation-ops-refactor-58083b`, based on `local/personal`
  `ea3f656a24`.
- aaps-watchfaces: worktree `/Users/aseesing/Projects/aaps-watchfaces-anim-generic`, branch
  `anim-generic`, its `local.properties` points `aapsDir` to this AAPS worktree.
- Pixel baseline: rendered from the unchanged engine before phase 1 (see phase 0).
- Every Gradle build goes through the shared lock (user rule, 2026-11-18), run in the background:
  `sh ~/.claude/bin/gradle-locked.sh <project dir> <log> <tasks>` (adds only `--console=plain`; add
  `-PfirebaseDisable` yourself for AAPS builds; no `--no-daemon`).

## 6. Revised phases

0. **Baseline.** Snapshot test in aaps-watchfaces renders every zip in `cwf-designs/` at fixed instants
   (normal frames by day and night, wake frames at fixed `t`), plus a compare tool.
1. **Internals that survive the schema change**, no JSON change, must be pixel-identical:
   formula hot path (+ `let`, `window`), value registry, one frame cache, view key maps, one moon picture,
   per-frame allocations, preview bitmap reuse.
2. **New schema in AAPS**: templates (built-in JSON), gradient fills, point extras, clip props, view layers
   with full transform and `rest`, view `image` key + dynamic images (`@moon`), `looks`, hash randoms;
   old keys and classes deleted; AAPS tests updated.
3. **Port generators** to the new schema, equivalent first (pixels compared, only random layers may move),
   then improvements (real moon values instead of hand approximations, shared `let`, point phases).

## 7. New schema (phase 2), the contract between engine and generators

Units: 400x400 design space, as now. Only the `animation` key and the personal view keys change.

```jsonc
"animation": {
  "durationMs": 6000, "fps": 24, "fadeMs": 600,          // unchanged
  "let": { "mx": "200 + 120*cos(2*pi*v_moon_cycle)" },    // NEW: named formulas, per frame, usable in every layer
  "templates": { "snow": { "params": { "count": 120, "seed": 3 }, "layers": [ ... "$count" ... ] } }, // NEW, optional
  "layers": [
    { "use": "rain", "count": 90, "color": "#A6C8FF" },   // NEW: expand a template (built-in or design)
    { "image": "Halo", "rect": {...}, "x": "mx", ... }      // a plain layer
  ]
}
```

Removed top-level keys: `rain`, `pulse`, `glitch`, `trace`, `glint`, `blink`, `sweep`, `slide`, `wobble`
(now built-in templates with the same parameter names and defaults; array presets become one `use` per view).

**Templates.** `params` gives defaults (number, string, colour, object, array), optional limits as
`{"default": 90, "min": 0, "max": 300}`. Expansion at load time: a JSON string that is exactly `"$name"`
becomes the parameter value itself (any JSON type); `$name` inside a longer string is replaced by its text
(numbers as plain decimals). Templates may use other templates (depth limit 4, no recursion). Derived
numbers (sin of the angle, spans) are written as `let` formulas; constant folding makes them free.
Built-in templates are one JSON asset in `:wear` (also copied into cwfrender).

**Layer keys.**
- Source, exactly one: `image` (zip key, or `@name` = app-provided dynamic image; first one: `@moon`),
  `shape` (`rect` | `circle` | `line`), `view` (face view key). Removed: `moon`, `band`, `dot`.
- `gradient` (shapes): `{"type": "linear" | "radial", "stops": [[pos, alphaFactor], ...]}`, multiplies the
  layer colour's alpha. Linear runs along the shape's local x over its width; radial from centre to radius.
  BAND = rect + linear `[[0,0],[0.5,1],[1,0]]`; DOT = circle + radial `[[0,1],[0.25,1],[1,0]]` (check
  against the current shader code, pixels must match).
- `slot`: `back` | `views` | `front` (unchanged), `blend`, `glow`, `maxScale`, `clip` (unchanged; rings and
  boxes usable by any layer).
- `let`: per layer named formulas, evaluated once per copy (may use `i`, `r1..`, `px`, ...).
- Props as now, plus `clipLeft`, `clipTop`, `clipRight`, `clipBottom` (any layer; replace view-only
  `clipRight`), `visible` (checked before any canvas work). Removed prop: `glowAlpha` stays only if a template
  needs it.
- Copies: `count` + `seed`, `slices` (+ `slicesAlong`), `points` where each point is `[x, y, p0, p1, ...]`
  (extra columns readable as `p0`, `p1`, ...).
- `rest`: seconds; now allowed for every slot and for view layers (a view layer with `rest` also moves the
  view in normal frames, updated when the base frame is redrawn).
- View layers: `x`, `y` add to the view's place, `rotation` adds to its rotation, `scaleX/Y` and `alpha`
  multiply (real alpha, not on/off). Variables `rot0`, `dx0`, `cx0`, `cy0` = the view's own rotation, X
  offset and centre in design units.

**Variables.** `t`, `len`, `env`, `i`, `n`, `u`, `px`, `py`, `p0..`, `r1..r8` (= `hash(seed, i, k)`,
stateless; default seed 1), `sec`, `min`, `hour`, `bpm`, `rot0`, `dx0`, `cx0`, `cy0`, `v_<valueKey>`.
Removed: `ms` (use `t*1000`). New functions: `window(t, start, dur)`, `pingpong(x, len)`.

**Values** (`v_*`, `dynData.valueKey`, look conditions), from one registry. New keys only when a ported
design uses them, e.g. `is_day`, `weather_kind` (0 clear, 1 partly, 2 cloudy, 3 rain), `moon_limb`.

**Views (outside `animation`).**
- Removed `moonLight: true`; instead the generic view key `"image": "@moon"` (also accepts a zip image
  name): the view shows that picture.
- Removed `orbit`; instead a view layer with `rest: 0` and `x`/`y` formulas (shared `let` for things that
  follow the same body).

**Looks (top level).** `"looks": [{"when": "<formula over v_*>", "patch": {...}, "images": {"A": "B"}}]`,
applied in order. Replaces `dayLook`, `weatherLooks` and the `XDay` image suffix. `colourLooks` stays as the
declaration of the user colour settings (it is a setting, not a condition) but uses the same merge code.

Kept unchanged: `graphLineWidth`, `graphLineColor`, the `@time` text token, the log-time graph setting.

## 8. State at the pause (2026-11-18 17:00)

- Scope was cut (see `ANIMATION_TODO.md`): the translator for the old keys stays, the designs are not
  ported now, no new features that no design needs, few new tests.
- Phase 2a agent stopped at 17:00 for the usage pause, before its final report. Its last runs:
  pixel compare 161 of 161 frames equal to the baseline (`cwf-snapshots/diff-phase2a/report.md`, 15:46);
  AAPS tests green (`/private/tmp/claude-501/p2a-test4.log`, 16:49); design repo tests green
  (`/private/tmp/claude-501/p2a-design2.log`, 16:57; check whether it was the full set).
- Still to do: the agent's report (deviations from section 7, not built, ignored tests), one final run of
  all three checks on the final tree, section 5 of the TODO file. Nothing committed.
