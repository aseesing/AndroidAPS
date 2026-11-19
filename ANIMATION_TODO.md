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
- [x] The moon values `moon_lit`, `moon_limb` and `moon_north` (2026-11-19, see section 8).
- [ ] More values for formulas where a design wants them: `sun_altitude`. Aurora Town still works out
      the turn of its moon light by hand from `moon_phase` and `moon_cycle`; it can read `v_moon_limb` now.
- [ ] Keyframes as sugar over formulas.
- [ ] Anything the phase 2a report lists as "not built" (see below).

## 3. Efficiency left on the table

- [ ] The phone preview makes a new bitmap per animation frame again, if the reuse was taken out
      (see the phase 2a report): a safe reuse needs to know which frame Compose still shows.
- [ ] `LayerAnimator` keeps its pictures (images, glow, masks) after a wake ends.
- [x] The moon picture was shaded on the CPU, pixel by pixel: a template of the design draws it with
      plain layers now (section 8).

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
- [x] The app has no built-in templates any more, and the formula functions `f32`, `settle` and `pulse`
      are gone (done on 2026-11-19, branch `local/wear-no-builtin-templates`). A design brings every
      template it uses in `animation.templates`; the nine old ones are in chapter 7 of the manual, written
      with plain formulas. The zips of `aaps-watchfaces` carry the templates they use (`DesignTemplates`).
      A zip made before this change loses its effects on a watch with this build until it is sent again.
- [ ] Remove the variable `ms` (use `t*1000`). The nine templates of the manual still use `ms`.
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
page's own player. The page has no templates of its own: each example carries the templates it uses.
There is no test for the page: the check is an instruction for the agent, the skill
`watchface-animation-manual` (`.claude/skills/`).

Found while the manual was written, by reading the code, not tried:
- [ ] `FaceFrame.clockText` and `secondHand` take no set of views to leave out. So a wake view layer on
      `time`, `hour`, `minute`, `second` or `second_hand` may draw that view twice (once by the face, once
      by the layer). Check in the preview; the manual warns about it.
- [ ] The manual follows the code where the plan (`ANIMATION_REFACTOR_PLAN.md`, section 7) says something
      else: `ms` still exists, `r1..r8` come from `Random(seed)` and not from a hash, the easing
      function is `easeInOut`, and there are no built-in templates at all (a design brings its own).

## 8. The moon from plain layers (branch `local/wear-moon-template`, 2026-11-19)

A template draws the moon with six plain layers. The watch does not have that template (the user's
decision of 2026-11-19: no built-in templates): it is in the manual, chapter 13, as JSON to copy (the
example `ex-moon-template`), and a design brings it in `animation.templates`. The design repo embeds it
(`MoonTemplate`, `DesignShorthand`), and the tests here read it from the manual (`ManualMoon`), so keep
that example valid JSON under that id. `@moon` is only the plain photo (`MoonPhoto`). New generic keys:
`group` with the blends `erase` and `inside`, and a template as the picture of a view
(`"image": {"use": "moon"}`, `ViewTemplates`).

- [x] The user compared the old and the new moon on 2026-11-19 and said yes: `MoonPicture.kt` and its
      test are deleted. `MoonTemplateTest` tests the template alone and writes its moons to
      `wear/build/moon-template/`. The design repo renders the template itself (`MoonTemplate.draw`) for
      the moon it bakes into the stock Aurora Town background.
- [ ] A zip made on 2026-11-18 or 2026-11-19 holds `"image": "@moon"` and shows the plain full moon with
      this build. An older zip (`"moon": true`, `"moonLight": true`) is no longer read at all (the
      old keys are gone), so it shows no moon. The zips of the design repo are rebuilt with the
      template; send them again.
- [ ] A group draws on a sheet of its own in every frame of a wake. Not measured on a watch.
