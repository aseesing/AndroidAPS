# Wear animation engine: work left for later

Cut on 2026-11-18 to finish the refactor sooner. The plan and the new design format are in
`ANIMATION_REFACTOR_PLAN.md` (section 7). Items are in the order that makes sense to pick them up.

## 1. Port the designs, then delete the old keys

The engine core only knows the new generic model. A translator (`watchfaces/design/LegacyDesign.kt`)
rewrites the older design keys at load time, so every existing zip keeps working unchanged.

- [ ] Port the generators in `aaps-watchfaces` to emit the new keys: presets as `{"use": ...}` layers,
      `"image": "@moon"` for `moon: true` and `moonLight`, view layers with `rest` for `orbit`, `looks`
      for `dayLook` / `weatherLooks`. Regenerate the zips; compare pixels with `cwf-snapshots/baseline`.
- [ ] A test in the design repo that no zip in `cwf-designs/` holds an old key.
- [ ] Delete `LegacyDesign.kt` and its tests. Zips on the watch must then be sent again.
- [ ] Update the TypeScript designer (`aaps-watchfaces/designer`) to the new model.

## 2. Generic features not built yet (no current design needs them)

- [ ] Random numbers `r1..r8` as a stateless `hash(seed, i, k)`; today the default seed is the place of
      the layer in the list, so adding a layer moves the randoms of the others.
- [ ] Points with extra columns (`p0..`), so one layer can give each point its own phase or colour
      (Aurora Town uses 4 copies of one layer for its chasing lights).
- [ ] View layers with `y`, scale and real alpha; a `visible` formula per layer.
- [ ] More values for formulas where a design wants them: `moon_lit`, `moon_limb`, `moon_north`,
      `sun_altitude` (Aurora Town approximates the moon's turn by hand).
- [ ] Keyframes as sugar over formulas.
- [ ] Anything the phase 2a report lists as "not built" (see below).

## 3. Efficiency left on the table

- [ ] The phone preview makes a new bitmap per animation frame again, if the reuse was taken out
      (see the phase 2a report): a safe reuse needs to know which frame Compose still shows.
- [ ] `LayerAnimator` keeps its pictures (images, glow, masks) after a wake ends.
- [ ] The moon picture is shaded on the CPU, pixel by pixel.

## 4. Tests and tooling

- [ ] Tests removed or marked `@Ignore("TODO animation refactor: ...")`: search for that text.
- [ ] A test that an animation frame of the preview's light path equals the same frame through the
      pipeline (`CwfPreviewRenderer.showInstant` copies steps of the upstream `CwfFramePipeline`).
- [ ] The snapshot harness sometimes draws complication slot text in the font of another design
      (Robolectric native fonts on macOS): render each design in its own JVM.
- [ ] The text-fit test at real screen sizes (added for Aurora Town) for the other designs.
- [ ] Nothing of this was run on a watch: check the wake animation, seconds on and off, ambient, and
      the phone preview on real devices before trusting it.

## 5. From the phase 2a report

Built, but not used by any design yet (so only covered by unit tests): point extras `p0..p7`, `visible`,
`clipLeft/Top/Bottom`, view `y`, scale and real alpha, design-defined `templates`, `pingpong`, `window`.
So in section 2 only these are really open: hash randoms, more sky values, keyframes.

Not built:
- [ ] The view key `image` works on text views only, not on image views.
- [ ] Values beyond `is_day` and `weather_kind`.

To clean up when the designs are ported (section 1):
- [ ] Remove the formula function `f32` (it copies the 32-bit float rounding of the old presets, so the
      built-in templates give the same pixels as before).
- [ ] Remove the variable `ms` (use `t*1000`).
- [ ] `CwfDesignBase` in the design repo keeps its own old-form `lookOf` and `lookImages`.

Known limits:
- [ ] Rest layers of the `views` and `front` slots: with seconds shown they are drawn between the right
      parts; in the face drawn in one go (`onDraw`, no seconds) they are drawn over the whole face, also
      over the second hand.
- [ ] A rest view layer moves its view in whole-pixel steps through the layout margins.

Must be checked on real devices (reasoned out, not tested):
- [ ] The phone preview hands frames to Compose through a ring of 3 bitmaps (`CwfPreviewRenderer.frame`,
      `FrameHandOff` and `PreviewFramePainter` in plugins/sync). A bitmap is used again only after the
      screen drew a newer frame. If the preview tears or stutters: go back to one new bitmap per frame.
      `FrameHandOff` has no unit test.

Tests: none ignored. `DayOverlayTest` and `OrbitTest` were removed; their cases moved to `LooksTest`
and `LegacyDesignTest`. `MoonPictureTest` lost its `moonLight` test (the key is read by the translator
now). Not added: a unit test for `FrameHandOff`; the light-path test of section 4.

The snapshot harness now renders each design in its own JVM (`-PsnapshotOut`), which seems to remove the
font flake; the item in section 4 can be dropped if it stays away.

## 6. After the merge with the reviewed night face (`local/personal` of 2026-11-18)

The watch now gets the phone's place as a rule the repository keeps (`storeDayNightRule`), no longer
from the `key_location_*` preferences. The engine reads it (`skyRule`, `skyPlace()` in `CustomWatchface`).

- [x] `CwfPlaceNeeds.of(json)` (`shared/impl/.../weardata/CwfPlaceNeeds.kt`) knows the new forms
      (`"image": "@moon"`, a formula that reads a sky or weather value, `"needsPlace": true`) next to
      the old keys. Done on 2026-11-19.
- [ ] Designs should look sensible with no place (day look 07:00 to 19:00, moon north up, "--:--").
- [ ] `drawLayeredFrame` in `CustomWatchface` and `LayeredFrame.kt` are reviewed upstream code now. They
      are kept as they are, and one hook (`personalFrame`) draws the frame with `FrameLayers` first, so the
      upstream body does not run in this build. When upstream has merged that code and it is stable,
      decide: keep the hook, or give upstream the one frame cache.
- [ ] The phone preview draws a frame of the animation without the pipeline (`showInstant`) only while
      the views are up to date; the reviewed rules of the personal preview (a new face per size, new
      values kept until a frame reads them, `WatchLineSpacing`) run on every other frame. Not tried on
      a phone.

## 7. The manual

`docs/watchface-animation.html` is the manual of the design format, with a live picture for every
example (a small JavaScript copy of the rules draws them). A change to the engine's logic changes the
page in the same commit: the text, the key table at its end and, when a drawing rule changed, the
page's own player (the built-in templates are copied into it).
There is no test for the page: the check is an instruction for the agent, the skill
`watchface-animation-manual` (`.claude/skills/`).

Found while the manual was written, by reading the code, not tried:
- [ ] `FaceFrame.clockText` and `secondHand` take no set of views to leave out. So a wake view layer on
      `time`, `hour`, `minute`, `second` or `second_hand` may draw that view twice (once by the face, once
      by the layer). Check in the preview; the manual warns about it.
- [ ] The manual follows the code where the plan (`ANIMATION_REFACTOR_PLAN.md`, section 7) says something
      else: `ms` and `f32` still exist, `r1..r8` come from `Random(seed)` and not from a hash, the easing
      function is `easeInOut`, the built-in templates are a Kotlin string and not a JSON asset.
