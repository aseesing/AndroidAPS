# Wear animation engine: work left for later

Cut on 2026-11-18 to finish the refactor sooner. The plan and the new design format are in
`ANIMATION_REFACTOR_PLAN.md` (section 7). Items are in the order that makes sense to pick them up.

## 1. The old design keys are gone from the watch

Done on 2026-11-19. The generators in `aaps-watchfaces` still write their short keys (`rain`, `dayLook`,
`orbit` and so on), and `DesignShorthand` there rewrites a design into the form the watch reads when a
zip is made. All zips in `cwf-designs` are in that form (a test checks it), with the same pixels as
before (175 snapshot frames). The watch code no longer has the translator (`LegacyDesign`).

- [ ] A zip made before 2026-11-19 that is still on a watch or a phone loses its animation, its looks and
      its moon with this build, without an error: send the new zip again. A night face is a zip too.
- [ ] `CwfPlaceNeeds` (phone) still knows the old keys next to the new ones. Take the old ones out, with
      their tests, once no old zip is in use.
- [ ] Write the generators in the new keys directly, and drop `DesignShorthand`, when a design needs
      something the short keys cannot say.
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
and the test of the translator, which now lives in the design repository (`DesignShorthandTest`).
`MoonPictureTest` lost its `moonLight` test (the key is read by the translator). Not added: a unit test for `FrameHandOff`; the light-path test of section 4.

The snapshot harness now renders each design in its own JVM (`-PsnapshotOut`), which seems to remove the
font flake; the item in section 4 can be dropped if it stays away.

## 6. After the merge with the reviewed night face (`local/personal` of 2026-11-18)

The watch now gets the phone's place as a rule the repository keeps (`storeDayNightRule`), no longer
from the `key_location_*` preferences. The engine reads it (`skyRule`, `skyPlace()` in `CustomWatchface`).

- [ ] `CwfPlaceNeeds.of(json)` (`shared/impl/.../weardata/CwfPlaceNeeds.kt`) decides from the design
      JSON whether a design needs the place. It knows the OLD keys only. Old zips are found, because the
      translator runs later, on the watch. Before a design is ported, teach it the new forms:
      `"image": "@moon"` (layer or view), a formula that reads `v_sun_progress`, `v_night_progress`,
      `v_sun_cycle`, `v_moon_cycle`, `v_sun_next_time`, `v_is_day`, `v_weather_kind` or
      `v_weather_temperature` (in a layer, a `let` or the `when` of a look). Or give the design
      `"needsPlace": true`.
- [ ] Designs should look sensible with no place (day look 07:00 to 19:00, moon north up, "--:--").
- [ ] `drawLayeredFrame` in `CustomWatchface` and `LayeredFrame.kt` are reviewed upstream code now. They
      are kept as they are, and one hook (`personalFrame`) draws the frame with `FrameLayers` first, so the
      upstream body does not run in this build. When upstream has merged that code and it is stable,
      decide: keep the hook, or give upstream the one frame cache.
- [ ] The phone preview draws a frame of the animation without the pipeline (`showInstant`) only while
      the views are up to date; the reviewed rules of the personal preview (a new face per size, new
      values kept until a frame reads them, `WatchLineSpacing`) run on every other frame. Not tried on
      a phone.
