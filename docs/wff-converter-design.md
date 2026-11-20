# Design: converting a Custom watch face design into a Watch Face Format face

Status: design only, nothing is built. Written 2026-11-19 on `local/personal`.

This document describes a converter in AAPS that takes one of our design zips (the layer and
animation engine plus the classic Custom watch face keys) and turns it into a Watch Face Format
(WFF) face. AAPS builds that face on the phone, validates it and pushes it to the watch with
Watch Face Push.

Why: watches that launched with Wear OS 5 or later, and all watches on Wear OS 6, no longer offer
code-based faces. Today these watches get our design as one full-screen picture in an image
complication (`CwfFaceComplication`, upstream PR nightscout/AndroidAPS#5123). That loses the wake
animation, taps per region and the design's own complication slots, and in always-on the picture
freezes because our process is frozen.

Contents

1. What the design rests on (facts and where they come from)
2. Where it runs, and the pipeline
3. How formulas become WFF expressions
4. The tiers, feature by feature
5. The AAPS complication providers to add
6. Hints in the design JSON
7. Budgets and checks, and the lint
8. Worked example: Aurora Town
9. Check against a data-heavy design: Cyberpunk Tech (and Grand Complication)
10. Testing
11. Open questions and risks
12. A build order

---

## 1. What the design rests on

Every WFF detail below was checked against something. The column "Source" says what:

- **watch**: ran on a real watch (the owner's OnePlus, Wear OS 5, WFF 2) in the prototype session
  of 2026-11-19.
- **emulator**: ran on the Wear OS 6 emulator (AVD `Wear_OS_6`) in that session.
- **repo**: measured and written down in our own code, mostly `WatchFacePushHelper` and the
  `cwf` face in `wear/watchfacepush`.
- **schema**: in the WFF schemas inside `validator-push-cli-1.1.0-alpha01.jar`, versions 1 to 5.
- **probe**: a throwaway WFF file checked with the validator jar today
  (`WatchFaceXmlValidator.validate(file, version)`). The validator checks structure only, not
  expression names, and not runtime behaviour.
- **unproven**: an idea that needs a test on the emulator or a watch before code depends on it.

| Fact | Source |
|---|---|
| Watch Face Push needs Wear OS 6 (API 36). So every pushed face can use **WFF 4** at least; Wear OS 7 brings WFF 5. Versions 1 to 3 only matter for sideloaded test faces. | repo (`WatchFacePushHelper.MIN_SDK = 36`) |
| One face slot per app. Switching the slot to another package resets that face's editor settings. | repo |
| Updating the same package keeps the wearer's stored choices: a changed `ListConfiguration` default does not reach an install that stored a choice. | watch |
| `DefaultProviderPolicy` applies only the first time the runtime binds a `slotId`. A new provider needs a new `slotId`. | repo (comments in the `cwf` face) |
| In always-on our process is frozen. Anything WFF draws by itself (clock, hands, complication data it already has) keeps working. (Dynamic complication values should too; not proven.) | repo |
| A phone app can build and validate a face at run time: `validator-push-android` (`DwfValidatorFactory.create().validate(file, packageName)` gives `validationToken()`), Google's Androidify builds the APK on the phone with Pack. | owner's earlier session |
| Packaging needs only resources, a manifest with `com.google.wear.watchface.format.version`, `hasCode=false`, package `<app id>.watchfacepush.<name>`, zipalign and apksigner. The token is bound to the exact APK bytes and to the package name of the app that pushes. | owner's earlier session, repo (`EmbedWatchFaceTask`) |
| Static layers baked into one picture, time as `DigitalClock` with the zip's fonts, tower hands as `AnalogClock`, options as `ListConfiguration`, moon moved by a clock expression, user complication slots at the design's places, `Condition` and `Transform` inside a `Complication` reading `[COMPLICATION.RANGED_VALUE_VALUE]`, a 30 frame loop (`PartAnimatedImage`, `play="ON_VISIBLE"`, `frameRate="15"`, `loopCount="3"`), `Launch` taps and complication taps. | watch, emulator |
| At most 8 complication slots per face. | owner's earlier session |
| `[COMPLICATION.*]` can only be read inside its own `Complication`. | owner's earlier session |
| WFF has no "time since wake", no seeded noise or hash (it has `rand(a, b)`, not seeded), no loops, no sunrise, sunset or moonrise. | schema |
| WFF expression functions: `round floor ceil fract sin cos tan asin acos atan abs clamp rand log log2 log10 sqrt cbrt exp expm1 deg rad pow numberFormat icuText icuBestText subText textLength colorArgb colorRgb extractColorFromColors extractColorFromWeightedColors`. Operators: `+ - * / % ~ ! \| \|\| & && ( ) > >= < <= == != ? :`. There is **no** `min`, `max`, `mod`, `sign`, `atan2`, `if`, `step`, `mix`, and no text to number. | schema (`arithmeticExpressionType.xsd`) |
| `Reference` (publish an attribute of one element as `[REFERENCE.name]`) exists from WFF 4. The probe placed a `Reference` inside a part in a `Complication` and read it in a `Transform` outside: valid for 4 and 5, invalid for 1 to 3. Whether the runtime really updates it across slots is not known. | schema, probe; runtime **unproven** |
| `blendMode` on parts (`PartImage`, `PartDraw`, `PartText`): WFF 3 and later. On `Group` and `ComplicationSlot`: WFF 5 only. Modes include `SCREEN`, `PLUS`, `DST_OUT`, `SRC_IN`, `SRC_ATOP` and more. | schema, probe |
| `renderMode` (`SOURCE`, `MASK`, `ALL`) on parts and groups: all versions. Meant for masks. | schema, probe; behaviour **unproven** |
| `Transform` may hold an `Animation` (`duration`, `interpolation`, `repeat` up to -1 for ever, `fps`). It animates a change of the value; it has no "on wake" trigger. | schema |
| `AnimationController play=` takes `TAP ON_VISIBLE ON_NEXT_SECOND ON_NEXT_MINUTE ON_NEXT_HOUR`, plus `delayPlay`, `repeat`, `loopCount`, `beforePlaying`, `afterPlaying`. `SequenceImages frameRate` is 1 to 60. | schema |
| Text: `Font letterSpacing` from WFF 2, `isAutoSize` from 3, `verticalAlign`, `lineSpacing` and `minSize` from 5. Decorations `OutGlow`, `Shadow`, `Outline`. Formatters `Upper`, `Lower`, `Template`. | schema |
| Fills can be `LinearGradient`, `RadialGradient`, `SweepGradient` (`colors` and `positions`). | schema, probe |
| `ComplicationSlot` cannot hold a `Transform`: a slot cannot move. | schema |
| WFF 5: `ListOption complicationSlotIds` (slots that exist only for one choice), `childSettingIds`. WFF 4: `PhotosConfiguration`. | schema |
| Memory limits used by the validator: **100 MB active, 10 MB always-on** (defaults `100.0` and `10.0` in its `EvaluationSettings`, checked by its `ActiveMemoryFootprintCalculator` and `AmbientMemoryFootprintCalculator`). | validator jar |
| The validator does not know `[COMPLICATION.*]` names; only the names used in the prototype are proven: `TEXT`, `TITLE`, `RANGED_VALUE_VALUE`, `RANGED_VALUE_MIN`, `RANGED_VALUE_MAX`, `SMALL_IMAGE`. | validator jar, watch |
| Our engine already runs on the phone: `:wear:cwfrender` builds the watch's own renderer (`CwfPreviewRenderer`) for the phone preview, with the wake animation. | repo |
| Prototype sizes: the Aurora face APK was 585 KB. The baked sky as a 400 x 400 RGBA PNG was 350 KB (as JPEG it would be close to the zip's 111 KB background). 30 snow frames of 400 x 400 RGBA PNG were 4.7 KB each, 140 KB together. | prototype files |

---

## 2. Where it runs, and the pipeline

### 2.1 The parts and the sides

```
PHONE (AAPS)                                              WATCH (AAPS wear app)
-------------------------------------------------------   ------------------------------------------
design zip ---------+                                     same design zip (already sent today)
wearer settings ----+--> 1 read and normalise             |
(units, thresholds, |   2 plan (tiers, slots, budgets)    |
 place, options,    |      -> lint report (shown in UI)   |
 watch prefs,       |   3 bake with our engine            |
 watch facts:       |      (:wear:cwfrender)              |
 screen size,       |   4 write watchface.xml             |
 WFF version)       |   5 package APK (template APK)      |
                    |   6 zipalign + sign (apksig)        |
                    |   7 validate -> token               |
                    |      (validator-push-android)       |
                    +-> 8 send APK + token + face plan ---+--> 9 WatchFacePushManager
                          (Channel API, not a DataItem)        add / update the one slot
                                                              10 providers read the face plan:
                                                                 glucose, age, blocks, values
```

Recommendation: **the phone builds, the watch installs and feeds the data.**

Why the phone:
- It has the inputs: the zip, units, thresholds, the place (only the phone has it, and only for a
  design that needs it), and the options.
- It has the CPU and memory for baking, the validator (its CLI jar is 8.7 MB; the Android library
  is likely a few MB too) and signing.
- The wear APK is embedded in the phone APK, so a heavy validator in the wear app would make both
  bigger.

What it costs: the APK travels over Bluetooth. The design zip path has a limit near 320 KB (a
bigger zip never arrives, without an error), so the APK must go through the Channel API (a stream),
not the DataItem path that carries designs today. Transfer time is not measured yet (see 11).

The other way, building on the watch, avoids the transfer and bakes at the exact screen size. It
needs Pack or the template APK, apksig and the validator inside the wear app, and the watch's
slower CPU. Keep it as plan B if the transfer turns out slow or unreliable.

The watch tells the phone two facts the converter needs: the **screen size in pixels** (to bake at
the right resolution) and the **WFF version** it runs (see 7.5).

### 2.2 The steps

1. **Read and normalise.** Use the engine's own readers, not a second parser: expand templates
   (`WakeAnimationJson`), own functions and `let` names (they are inlined the way the engine already
   does), apply `colourLooks`, resolve `dynPref` with the watch's preference values, and resolve
   `twinView` offsets. Keep `looks` and `options` open: they become WFF conditions or variants.
2. **Plan.** Give every layer, view and value a tier (section 4), honour the hints (section 6), fit
   the slots and the budgets (section 7). The plan is a plain data object. It is also the lint
   report, and it is what goes to the watch (step 10). The planner is a JVM library, so the design
   tests in `~/Projects/aaps-watchfaces` can run it too.
3. **Bake.** Draw with our engine (`:wear:cwfrender`) at the watch's pixel size:
   - static runs (section 4.1) into opaque or transparent pictures, cropped to their bounding box;
   - wake loops into frame sequences, cropped;
   - pre-baked variants (one picture per state of a value with few states);
   - the preview picture of the face (`CustomWatchface.jpg` can be the editor preview).
   This needs one new entry in the renderer: draw a chosen set of layers and views at a chosen
   `t`, `env` and set of values into a transparent bitmap. Today it can only draw the whole face.
4. **Write `watchface.xml`** from the plan, in paint order.
5. **Package.** Two ways:
   - **Template APK (recommended first).** Gradle builds, once per app id, a face APK with a fixed
     binary manifest (package `<app id>.watchfacepush.design`, `hasCode=false`, the format version
     property), fixed `res/xml` files, and a resource table with a fixed set of placeholder names:
     `raw/watchface`, `drawable/img_000` to `img_255`, `font/font_0` to `font_7`, `drawable/preview`.
     The phone only replaces file contents and writes a new zip. Unused placeholders hold a 1 x 1
     picture. This avoids a resource compiler on the phone. Limits: the number of placeholders, and
     strings must be written into the XML as literal text (check that literal `displayName` works,
     see 11).
   - **Pack** (as in Androidify) if the template limits bite. Check its licence and size first.
6. **Align and sign.** Zip alignment of stored entries is a few lines of code. Signing uses
   `apksig` (already inside the validator) with a key the phone creates once and keeps (Android
   Keystore if apksig's JCA path accepts such a key; else an app-private key file). The key must
   stay the same across rebuilds (see 11: does Push care?).
7. **Validate** with `validator-push-android` for the wear app's package name. No token, no push:
   the build stops and the lint shows the validator's message.
8. **Send** the APK, the token and the face plan (which provider draws which block, the slot ids,
   the thresholds) to the watch.
9. **Push** with the existing `WatchFacePushHelper` path (`addWatchFace` or `updateWatchFace`), with
   a third `PushedFace` entry whose APK and token come from a file instead of the assets. The helper
   already skips a push when the token is the same as the last one.
10. **Feed.** The new providers (section 5) read the face plan and the zip and send their data.

If any step fails, the watch keeps the face it has. When the wearer has chosen the converted face
and no converted face was ever installed, the phone falls back to the picture face (`CWF`), so the
wearer is never left without glucose.

### 2.3 When it runs again

Build and push only when the result changes. The phone keeps a hash of the inputs; the helper
compares tokens before pushing.

| Change | What happens | Effect on the wearer's stored face settings |
|---|---|---|
| New design zip loaded | Full rebuild, `updateWatchFace` on the same slot | Kept, as long as ids stay the same (see the rules below). Options with the same `name` keep their choice. |
| Option changed in AAPS (phone or watch row) | Nothing, if options live in the system editor (default). Rebuild only for an option marked `"baked"` (6.3). | An editor option cannot be set by AAPS: Push has no call for it. AAPS's own choice becomes the `defaultValue`, which only counts for a fresh install. |
| Units or glucose thresholds | Rebuild (they are constants in the face) | Kept |
| Place moved more than about 50 km | Rebuild, only when the design reads sky values that the face computes itself (4.3) | Kept |
| Watch preferences used by `dynPref` (`key_show_iob`, `key_units`, ...) | Rebuild | Kept |
| Wake animation length setting on the watch | Rebuild (frame counts change), or nothing when it maps to an editor switch (see 4.2) | Kept |
| AAPS update with a new converter version | Rebuild (the version is part of the input hash) | Kept |
| Wearer switches between the picture face and the converted face | Other package in the one slot | Reset: that is how Push works today |

Rebuilds wait a little (for example 30 seconds) so that a burst of changes gives one push.

Rules that keep settings across an update:
- User complication slots keep `slotId` 1 to 5 (`complication1` to `complication5`).
- Data slots get ids from a fixed role table (for example glucose 10, age 11, arrow 12, blocks
  20 to 27). When the provider of a role changes, the role gets a new id (for example +100),
  because `DefaultProviderPolicy` is applied only at the first bind.
- `ListConfiguration id` is the option `name`; `ListOption id` is the choice number. A
  `ColorConfiguration id` is `colour1` or `colour2`.

---

## 3. How formulas become WFF expressions

Most of the tier decisions follow from one question: **which names does a formula read?** The
engine already asks this question to decide how often to work a formula out (`Formula.reads()`,
manual "What costs time"). The converter asks it to decide where the formula can live.

| The formula reads | Where it goes |
|---|---|
| only numbers, `pi`, `let` names and own functions of those | a constant, folded at build time |
| build-time facts: units, thresholds, `dynPref` values, `rot0` / `cx0` / `cy0` of a fixed view, `i n u px py p0..p7 r1..r8` of a copy | a constant per copy (copies are unrolled, 4.2) |
| the clock: `hour`, `min`, `sec` | a live WFF expression (`[HOUR_0_23]`, `[MINUTE]`, `[SECOND]`, ...) |
| values WFF has itself: `v_watch_battery`, `v_steps`, `v_heart_rate`, `v_day`, `v_month`, `v_day_name`, `v_week_number`, `v_moon_phase`, `bpm` | a live expression with the WFF source, after checking the exact meaning (numbering, ranges) |
| `v_opt_<name>` | `[CONFIGURATION.<name>]` in a `Condition`, or a `ListConfiguration` element that holds one branch per choice |
| AAPS values: `v_sgv`, `v_iob`, ... | only inside the `Complication` of the slot that carries the value (5), or through `[REFERENCE.x]` on WFF 4 if the runtime test passes |
| sky values that need the place | fitted constants (4.3) or a complication |
| `t`, `ms`, `len`, `env` (the wake) | no WFF equivalent: a baked loop, or "clock motion", or the still pose (6.2 `motion`) |
| `noise`, `hash` of anything that is not a constant | baked |

The printer turns the engine's syntax tree into WFF text. It needs read access to the tree (today
the nodes are private to `Formula`), so the engine gets a small visitor. Function mapping:

| Ours | WFF |
|---|---|
| `sin cos tan abs floor ceil round fract sqrt pow exp log clamp` | same name (radians, as in the moon expression that ran on the watch) |
| `if(c, a, b)` | `c ? a : b` (both sides must be numbers; texts go to `Condition` or `Template`) |
| `min(a, b, ...)`, `max(...)` | nested `a < b ? a : b` |
| `mod(a, b)`, `a % b` | `((a % b) + b) % b` unless both sides are known to be positive (WFF `%` sign rule is not documented) |
| `sign(x)`, `step(e, x)`, `mix(a, b, u)`, `smoothstep`, `window`, `easeInOut`, `pingpong` | written out with `? :` and arithmetic |
| `atan2(y, x)` | written out with `atan` and `? :` |
| `missing(x)` | inside a slot: the `EMPTY` or no-data branch of the complication; else a constant |
| `noise`, `hash` | not translated: the layer is baked |
| `&` (text join), `concat` | a `Template` with several `%s` parameters |
| `upper`, `lower` | the `Upper` / `Lower` formatter elements |
| `left`, `right`, `mid`, `len` | `subText`, `textLength` |
| `fixed`, `text(x, fmt)` | `numberFormat` where the format fits (to prove), else baked or built into the provider's text |
| `clock()`, `clockHour()`, `clockMinute()`, `ampm()` | `TimeText` in a `DigitalClock`, `[AMPM_STRING]` |
| `date(pattern)` | `icuText(pattern)` (to prove: same letters as Java for `EEE d MMM`) |
| `glucose(v_sgv)` | `[COMPLICATION.TEXT]` of the glucose slot; the provider formats it in the wearer's unit |
| `rept`, `trim`, `substitute`, `find`, `padLeft`, `padRight`, `value` | only when the result is a build-time constant; else baked or moved into a provider text |

The same name table (our name to WFF source, with the exact meaning) is what the parallel session
`wff-aligned-names` builds in the other direction: it teaches our engine to accept WFF names. The
converter should use that table and not keep its own copy. Points where the meanings differ must be
written in that table: `v_day_name` (ours: 1 is Monday; check WFF `DAY_OF_WEEK`), `v_week_number`
(week rules), `v_moon_phase` (ours 0 to 1; check the range of `MOON_PHASE_POSITION`), `sec` with
its part after the dot (`[SECOND]` plus `[MILLISECOND]`?).

A "live" expression that reads `[SECOND]` or `[MILLISECOND]` makes WFF redraw that part all the
time. That is fine for a second hand and costly for a big picture. The planner warns when a big
part reads them.

---

## 4. The tiers, feature by feature

The tiers:

| Code | Tier | Meaning |
|---|---|---|
| **L** | live | WFF draws and updates it: clock, date, hands, options, user slots, watch battery and steps, transforms with clock expressions |
| **E** | expand | resolved at build time into plain WFF or into constants: `let`, functions, templates, copies, looks, `dynPref`, `twinView`, the wearer's settings |
| **B** | bake | drawn by our engine on the phone into a picture, a set of variant pictures, or a frame loop |
| **C** | complication | drawn or sent by an AAPS provider on the watch: AAPS data, and blocks of the design that WFF cannot draw |
| **O** | omit | left out, and said so in the lint |

General rules, before the tables:
- **Exactness first.** Our engine draws the design; WFF is used where it draws the same thing, or
  where only WFF can do it (live in always-on, live without our process). So static art is baked
  (pixel-exact), and live things are translated.
- **Glucose is never omitted.** The glucose value, its range colour, the "old reading" mark and the
  arrow are safety views. If one of them cannot be placed, the build fails and the wearer keeps the
  picture face. All other losses are allowed, but listed in the lint.
- **Visibly absent beats silently dead.** A layer that cannot work on WFF is left out with a lint
  line; it never ships as an element that looks right and does nothing.

### 4.1 Paint order and static runs

WFF draws elements in document order. The converter walks the face in our paint order (background,
complication slots, graph and data views, back / views / front slots, clock text, hands, second
hand; manual chapter 1) and cuts it into **runs**. A run is a stretch of consecutive static items
(pictures, shapes, static text, rest poses). A run ends where a live element (a slot, a clock text,
a loop) lies in between **and their boxes overlap**. Each run becomes one baked picture, cropped to
its bounding box: the first one opaque (JPEG or lossy WebP), later ones transparent (PNG or WebP
with alpha). Items whose boxes do not overlap any live element can move into the run below, so
most designs end with one or two pictures.

### 4.2 The layer and animation engine

| Feature | Default | Other tiers (hint) | Why |
|---|---|---|---|
| `durationMs`, `fps`, `fadeMs` | B: length and frame rate of the baked loops (6.4) | | WFF has no wake time; a loop is the closest match. `env` is baked into the frames. |
| Wake triggers (start, out of always-on, visible again, tap) | L: `AnimationController play="ON_VISIBLE TAP"` | `"wake": "visible"` | Same events. "Only while no animation plays" for a tap is not known for WFF (11). |
| Watch setting "Wake-up animation" (off, 3 to 30 s) | E: length baked in, rebuild on change | an editor switch `BooleanConfiguration` for on and off | WFF cannot read our setting. |
| `let`, own `functions` | E: inlined | | The engine inlines them too. |
| `templates`, `use` | E: expanded by the engine's reader | | The converter works after expansion. |
| `slot` (`back`, `views`, `front`) | E: decides the document position | | |
| `rest` | E: the rest pose is the normal look of the WFF face | | |
| `image` (zip picture) | B: part of a static run | L as `PartImage` with `Transform` when its props are live | |
| `image: "@moon"` | C: the sky block (5.4) | B: N phase pictures picked by `[MOON_PHASE_TYPE]` or position (`"steps": 8`) | The photo turns with `v_moon_north` and is cut by `v_moon_limb`; both need the place. |
| `shape` rect, circle, line, static | B | L as `PartDraw` (`Rectangle`, `Ellipse`, `Line` with round `Stroke`) | Baked is exact; live costs elements. |
| `shape` with live size or colour | L: `PartDraw` with `Transform` on `width`, `height`, `endAngle`, ... | B as variants | Which shape attributes take a `Transform` must be proven per attribute (11). |
| `text`, static | B | L as `PartText` with a zip font | |
| `text` reading the clock | L: `DigitalClock` / `TimeText`, or `PartText` with `Template` | | Must stay right in always-on. Proven for the time. |
| `text` reading AAPS values | C: `PartText` inside the slot | | |
| `font`, `fontSize` | L: zip fonts go into `res/font` | | Proven with Aurora's fonts. |
| `align` | L: `Text align` `START`, `CENTER`, `END` | | |
| `valign` | E: y moved by the font's metrics, which the phone can measure | L `verticalAlign` on WFF 5 | WFF 4 has no vertical align. |
| `letterSpacing` | L: `Font letterSpacing` (em, the same unit as ours) | | |
| text `gradient` | B | O | WFF text has no gradient. |
| text `glow` | L: `OutGlow` (fixed strength) | B when `glowAlpha` moves | `OutGlow` has `color` and `radius`; looks must be compared (10). |
| `view` layer | see 4.4 | | |
| `x`, `y`, `rotation`, `scale*`, `alpha` | L: `Transform` on `x`, `y`, `angle`, `scaleX`, `scaleY`, `alpha` when the formula is live; folded when constant | B | |
| `pivotX`, `pivotY` | L: `pivotX`, `pivotY` of the part (converted to the part's own frame; check whether WFF wants 0 to 1) | | |
| `visible` | L: `Condition` when it reads options, dates, or the battery; `alpha` 0 when it changes fast | | A hidden branch of a `Condition` is not drawn at all. |
| `color`, `colors` + `colorIndex` | constant: folded. Live: L with `Condition` branches, or `extractColorFromColors` | B variants | |
| shape `gradient` | L: `Fill` with `LinearGradient` / `RadialGradient`; alpha stops become colours with alpha | B | The direction rules (along the width, oval on a rectangle) must be mapped by hand. |
| picture `glow` + `glowAlpha` | B: the glow is a second picture | L: the glow picture with an `alpha` `Transform` | |
| `blend` `add`, `screen` | inside a run: B (the engine blends). Between a live part and what is below: L `blendMode="PLUS"` / `"SCREEN"` | | `blendMode` on parts exists from WFF 3; whether `PLUS` equals our `add` must be compared. |
| `group` + `erase` / `inside` | B | L with `renderMode="MASK"` or `blendMode="DST_OUT"` / `"SRC_IN"` (needs WFF 5 `Group` blend for an own layer) | Group compositing in WFF is not proven. |
| `maxScale` | E: the size of the exported picture | | |
| `clip` (`rect`, `round`, `rings`, `boxes`, `cornerRadius`, `mask`, `maskRect`) | B | L with a `Group renderMode="MASK"` holding the mask picture or shapes | Masks are the most uncertain WFF area. |
| clip props (`clipLeft` ...) | constant: B. Live: B as a loop | L with a moving mask shape | |
| `count` + `seed`, `points` | E: unrolled into separate elements, with `r1..r8` from the engine's own generator, when the props are live and the copies are few (default up to 24) | B | 186 tree lights as 186 elements would cost more than one picture. |
| `slices`, `slicesAlong` | B | | 100 moving strips are only practical as frames. |
| Formula variables `t`, `ms`, `len`, `env` | B: loop | L "clock motion" (6.2), or the still pose | |
| `bpm` | B: baked at `fallbackBpm` | L with `[HEART_RATE]` and clock motion | A loop has one fixed rate. |
| `sec`, `min`, `hour` | L | | |
| `noise`, `hash` | B (or E when the arguments are constants) | | No seeded noise in WFF. `rand` is not seeded. |
| Text functions | see section 3 | | |
| `looks` | see 4.5 | | |
| `options` | L: `ListConfiguration` per option, choices as `ListOption` | E per option: `"options": "baked"` | Proven on the watch. |
| `colourLooks` | L: `ColorConfiguration` (the wearer picks in the editor) | E: AAPS's colour baked in, rebuild on change | Reading a chosen colour in a `color` attribute is not proven. |
| `needsPlace` | E: the phone uses the place while building | | |
| Limits (40 layers, 1500 copies, 16 values) | n/a | | The converter gets the design after the engine's limits. |

### 4.3 Values

| Value | Default | Other | Why |
|---|---|---|---|
| `watch_battery` | L `[BATTERY_PERCENT]` | | WFF has it, also in always-on. |
| `steps` | L `[STEP_COUNT]` | | |
| `heart_rate` | L `[HEART_RATE]` | | Check that "too old to show" behaves alike. |
| `day`, `month`, `day_name`, `week_number` | L `[DAY]`, `[MONTH]`, `[DAY_OF_WEEK]`, `[WEEK_IN_YEAR]` | | Numbering checked in the shared name table. |
| `moon_phase`, `moon_lit` | L from `[MOON_PHASE_POSITION]` / `[MOON_PHASE_TYPE]` | C sky block | Date only, the same everywhere. Range to check. |
| `sgv`, `sgvLevel`, `direction`, `delta`, `avg_delta` | C glucose slot | | AAPS data. `sgvLevel` is computed in the slot from the thresholds, which are constants of the build. |
| `timestamp` (reading age) | C age slot with a dynamic value | | Keeps counting while our process sleeps. |
| `loop`, `tempTarget`, `reservoir`, `reservoirLevel`, `iob`, `iob_bolus`, `iob_basal`, `cob`, `basalRate`, `bgi`, `uploader_battery`, `rig_battery`, `sensor_left`, `pump_left` | C: in a block picture, or a value slot when a live expression needs the number (a gauge hand) | | |
| `_Ext1`, `_Ext2` | C: blocks | | They would eat the slot budget as separate numbers. |
| `sun_progress`, `night_progress`, `sun_cycle`, `is_day` | E: **fitted sun times**. The phone knows the place, so it fits sunrise and sunset over the year as a few cosine terms of `[DAY_OF_YEAR]` and writes the formula with `[SECONDS_IN_DAY]` | C: a value slot | Live without rebuilds. The error of a two-term fit is estimated at a few minutes; to be measured against `SunTimes`. Wearer's "fixed times" setting gives plain constants. |
| `moon_cycle`, `moon_limb`, `moon_north` | C: the sky block | E: constants refreshed by a rebuild every few days | Moonrise moves about 50 minutes a day; no short fit. |
| `sun_next_time` | C: a text in a block | | |
| `weather_kind`, `weather_temperature` | C: a block, or a value slot | L: WFF system weather `[WEATHER.CONDITION]` (`"weather": "system"`) | WFF weather comes from the watch's own source, not from our phone. It may be missing or differ. |
| `v_opt_<name>` | L `[CONFIGURATION.<name>]` | E | |

### 4.4 View layers (moving a view of the face)

| Case | Default | Why |
|---|---|---|
| `rest` place that reads only constants | E: the view is simply at the new place | |
| `rest` place that reads the clock or WFF values | L: a `Group` with `Transform` around the view's elements | A `ComplicationSlot` cannot move, so this works only for views that are not slots. |
| `rest` place that reads place-based sky values (a sun or moon on an arc) | C: the view goes into the sky block, which the watch draws at the right place | |
| a move only during the wake (hop, blink, sweep, trace on a data view) | O: the view stays at its rest place | Moving live data in a loop would need frames of live data. A frozen view is correct, only less lively. |
| the same on a WFF-native view (watch battery) | O, or L with clock motion: for example a blink while low becomes `Transform alpha` with `[SECOND] % 2` inside `Condition [BATTERY_PERCENT] <= 20` | It then blinks all the time while low, not only on wake. That is the designer's call (6.2). |

### 4.5 Looks

| Case | Default | Why |
|---|---|---|
| `when` reads options, date, watch battery | L: a `Condition` around each element the look changes, one branch per combination of active looks that touch that element | Only the elements that a look patches get branches. |
| `when` reads AAPS values | inside the slot that has the value, or in a block | |
| `when` reads `is_day` | L with the fitted sun times (4.3) | |
| `when` reads weather | the elements go into a block | |
| `images` swaps | B: one picture per variant, chosen by the same `Condition` | |
| a look that patches `animation` | B: one loop per look, each in its own `Condition` branch | Each costs frames; the lint shows the total. |

### 4.6 The classic Custom watch face views

| View | Default | Other | Notes |
|---|---|---|---|
| `background` (picture or colour) | B: the first static run | | |
| `cover_plate`, `cover_chart` | B: a transparent run, at their place in the paint order | | They lie over data, so they cut the runs. |
| `chart` | C: a graph block picture | | Our graph code draws it on the watch, with `graphLineWidth`, `graphLineColor`, `pointSize` and the range colours. The provider's tap action switches the time frame (today a double tap on the chart). |
| `complication1` to `complication5` | L: `ComplicationSlot` ids 1 to 5, `isCustomizable="TRUE"`, at the design's rect | | `complicationStyle` and the slot keys become parts per type: `color` and `borderRadius` a filled `RoundRectangle` / `Ellipse`, `borderColor` / `borderWidth` a `Stroke`, `ringWidth` / `ringPrimaryColor` / `ringSecondaryColor` two `Arc`s with an `endAngle` `Transform` for `RANGED_VALUE`, `iconColor` the tint of `MONOCHROMATIC_IMAGE`, `imageFit` the size of the image part, `font`, `textsize`, `fontColor`, `fontTitle`, `titleSize` the texts. Proven for the round style. |
| `sgv` | C: glucose slot, text `[COMPLICATION.TEXT]`, colour by thresholds | | Safety view. Today a double tap opens the menu; WFF only has a single tap (`Launch`). |
| `direction` | C: the arrow picture from the glucose slot (5.1) | C own arrow slot; L glyph from the text | Safety view. The design's own `Arrow*.png` are drawn by the provider. |
| `timestamp` | C: age slot | | Safety view (the "old reading" mark). |
| `delta`, `avg_delta` | C: glucose slot `TITLE`, or a block | | |
| `iob1`, `iob2`, `cob1`, `cob2`, `basalRate`, `bgi`, `reservoir`, `tempTarget`, `status`, `loop`, `uploader_battery`, `rig_battery`, `patient_name`, all `_Ext1` / `_Ext2` views | C: a block picture | C value slot when a live gauge needs the number | They change only when data arrives, which wakes our app anyway. |
| `time`, `hour`, `minute`, `second`, `timePeriod` | L: `DigitalClock` / `TimeText`, `[AMPM_STRING]` | | `enableSecond` adds the seconds; they hide in always-on. |
| `day_name`, `day`, `month`, `week_number` | L: `PartText` with `[DAY_OF_WEEK_S]` / `_F`, `[DAY]`, `[MONTH_S]` / `_F`, `[WEEK_IN_YEAR]` | | `dayNameFormat`, `monthFormat` pick short or long; `allCaps` becomes `Upper`. |
| `hour_hand`, `minute_hand`, `second_hand` | L: `AnalogClock` with `HourHand`, `MinuteHand`, `SecondHand` (sweep or tick) | | Proven for hour and minute. |
| `freetext1` to `freetext4`, static text | B | | |
| a view `image` (zip picture) | B | | |
| a view `image` `{"use": template}` that reads values | C: in a block, or B variants when it reads only date values | | The moon template is the usual case. |
| `dynData` on a WFF-native key (`watch_battery`, `day`, ...) | L: `Condition` with one `Compare` per range for `image*`, `color*`, `fontColor*`, `textsize*`; `Transform` for `topOffset`, `leftOffset`, `rotationOffset` (a linear map with `clamp`); `textvalue*` as `Template` with `numberFormat` | | 21 battery pictures are 21 small resources and 21 branches: fine. |
| `dynData` on an AAPS key | C: the same elements, inside the slot that has the value, or in a block | | Gauge hands rotated by glucose live in the glucose slot. |
| `dynPref` | E: resolved with the watch's preference values | | Rebuild when those preferences change. |
| `twinView` | E | | Depends on visibility, known at build. |
| `visibility`, `rotation`, `gravity`, `fontStyle` | E / L | | |
| Range colours (`highColor`, `midColor`, `lowColor`) | E: colours of the glucose branches | | |
| `metadata` | E: face name in the APK | | |
| `CustomWatchface.jpg` | E: the editor preview (or a fresh render) | | |

Taps: today a double tap on the glucose opens the AAPS menu and a double tap on the chart changes
its time frame. WFF has only single taps. Default: a single tap on the glucose region launches the
menu (`Launch`), a tap on the graph block is that provider's tap action. A tap elsewhere replays the
loops (`play="... TAP"`).

### 4.7 Always-on

- Baked pictures, loops and blocks get `Variant mode="AMBIENT" target="alpha" value="0"`, unless a
  hint asks to keep them.
- Clock, hands, the glucose and age slots stay visible. They are drawn by WFF, so they stay right
  while our process sleeps, and the age keeps counting.
- The design's "Always-on look" setting becomes the `Variant` values.
- Everything visible in always-on must fit in 10 MB (7.3).

---

## 5. The AAPS complication providers to add

All are watch-side services in `wear`, next to the existing ones. Each data slot in the face names
its provider in `DefaultProviderPolicy` and is `isCustomizable="FALSE"`. Today all AAPS providers
send `SHORT_TEXT` or `LONG_TEXT` (only the uploader battery has `RANGED_VALUE`), so nearly all of
this is new.

### 5.1 Glucose: `RANGED_VALUE`

- `value`: the glucose in mg/dl (unit free, the same as `v_sgv`), `min` 39, `max` 400.
- `text`: the glucose formatted in the wearer's unit (what `glucose(v_sgv)` gives), without the
  arrow.
- `title`: the delta in the wearer's unit.
- `monochromaticImage`: the design's own arrow picture for the current direction, drawn from the
  zip. Not `smallImage`: the emulator does not draw `[COMPLICATION.SMALL_IMAGE]` inside a
  `RANGED_VALUE` complication, but it draws `[COMPLICATION.MONOCHROMATIC_IMAGE]`, and without a
  `tintColor` the picture keeps its own colours (11.1, answer 2).
- No data: `NoDataComplicationData`, so the face shows its "no reading" branch (`---`).
- Updates: when a reading arrives.

In the face: the colour comes from a `Condition` on `[COMPLICATION.RANGED_VALUE_VALUE]` with the
wearer's thresholds as constants. A gauge hand is a `Transform angle` on the same value.

Proven on the emulator: the arrow rides in this slot as the monochromatic image, so it needs no
slot of its own. A real watch may tint monochromatic images; if it does, the arrow gets its own
`SMALL_IMAGE` slot (one slot more).

### 5.2 Reading age: `RANGED_VALUE` with a dynamic value

- `value`: a `DynamicFloat` of the minutes since the reading, made from the platform time source, so
  the system keeps it right while our process is frozen. `min` 0, `max` 60. Fallback value for
  systems that cannot work out dynamic values.
- `text`: a `TimeDifferenceComplicationText` ("5m"), which also ticks by itself (the existing
  glucose provider's title already does this, proven on the watch).

In the face: the "old reading" mark (`v_timestamp >= 12` in Aurora) is a `Condition` on the value.

To prove: that a dynamic `RANGED_VALUE_VALUE` drives a `Condition` in a WFF face, also in
always-on.

### 5.3 Value slots: `RANGED_VALUE`, a few fixed components

`Value1` to `Value4` (four manifest entries with the same code). The face plan says which value each
one carries (`iob`, `uploader_battery`, `sun_cycle`, ...). `value` is the number, `text` the
formatted number. Used only when a live element needs the number (a gauge hand, a battery icon
picked by level, a colour by level). Fixed component names are needed because a provider cannot
learn which slot asks; the face plan maps component to value.

### 5.4 Blocks: `SMALL_IMAGE`, a few fixed components

`Block1` to `Block4`. Each draws a named part of the design with our engine on the watch, as a
transparent picture at the block's rect, at the screen's pixel size: for example "status" (iob,
cob, basal, reservoir, loop), "graph" (the chart) or "sky" (the moon with its phase, limb, north
and halo, a sun on an arc, a cloud by weather). This reuses the code of the picture face
(`CwfFramePipeline`), only for a smaller area and a chosen set of views and layers.

- Updates: when data arrives; the sky block also every few minutes (moon position).
- The picture is transparent where the block draws nothing, so blocks may cover other elements'
  areas. Their tap areas should not overlap other slots.
- Ages inside a block ("LOOP 3'") go stale in always-on until the next data. The planner warns.

To prove: the largest picture a `SMALL_IMAGE` may carry. The full-screen picture face already sends
450 x 450, so a block is smaller than what works today.

### 5.5 What the existing providers keep doing

The current providers stay for the wearer's own slots (`complication1` to `complication5`): they
show in the system picker as today.

---

## 6. Hints in the design JSON

### 6.1 Where they go, and why the code-based engine ignores them

One key, `wff`, in three places:

- **top level** of the design JSON: settings for the whole face (6.3);
- **on a layer** in `animation.layers` (also inside a template's layers): how that layer goes to WFF;
- **on a classic view** (`"chart": {..., "wff": ...}`): how that view goes to WFF.

`wff` is either a word (the mode) or an object:

```json
"wff": "bake"
"wff": {"mode": "bake", "motion": "loop", "fps": 10}
```

How the engine ignores it:
- The layer reader and the classic view reader read only the keys they know. `wff` is never read
  by them, so the watch draws exactly the same. A design test must prove this: the same design with
  and without its `wff` keys gives the same frames.
- A `{"use": ...}` entry reports unknown keys as an error (they are template parameters). So the
  template reader must treat `wff` as a reserved key that it passes on to the layers the template
  makes. That is a one-line engine change, outside this design (the engine files belong to other
  sessions now).
- `looks` may patch `wff` like any other key; the converter reads the design after the looks.
- Hints never name WFF versions or WFF element names. The converter picks those, so a design stays
  valid when the converter learns more.

### 6.2 Keys of a layer or view hint

| Key | Values | Default | Meaning |
|---|---|---|---|
| `mode` | `auto`, `live`, `bake`, `complication`, `omit` | `auto` | `auto` follows the tables of section 4. `live` must become WFF elements, or the lint gives an error. `complication` puts it in a block (`block`). `omit` leaves it out without a warning. |
| `motion` | `loop`, `clock`, `still` | `loop` | For a layer that reads `t`, `ms`, `len` or `env`. `loop`: baked frames that play on wake. `clock`: a live expression where `t` becomes the clock time in seconds and `env` becomes `env` below; it moves all the time while the screen is on. `still`: only the rest pose. |
| `env` | number 0 to 1 | `1` | The value of `env` for `motion: "clock"`. |
| `seconds` | number | see 6.4 | Length of the baked sequence. |
| `fps` | 1 to 30 | top-level `fps`, else 15 | Frames per second of the loop. |
| `loops` | whole number | enough to fill the wake | How often the loop plays per wake (`loopCount`). |
| `variants` | `condition`, `bake` | `condition` | For a look that depends on a value with few states: live `Condition` around WFF elements, or one baked picture per state chosen by a `Condition`. |
| `steps` | 2 to 64 | none | Turns a slowly changing value (moon phase, a gauge) into that many baked variants. |
| `block` | a block name | none | With `mode: "complication"`: which block draws it. |
| `options` | `all`, `default` | `all` | For baked parts that read options: bake every choice, or only the default (the other choices then show the default's motion). |
| `ambient` | `hide`, `show` | `hide` for baked parts, `show` for live ones | |

### 6.3 Keys of the top-level hint

```json
"wff": {
  "fps": 15,
  "frames": 150,
  "sizeKb": 1200,
  "wake": "visible tap",
  "options": {"aurora": "editor", "santa": "editor", "moon": "editor"},
  "weather": "aaps",
  "sky": "fit",
  "arrow": "glucose",
  "blocks": {
    "status": {"update": "data"},
    "sky": {"update": 5}
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `fps` | 15 | Default frame rate of loops (WFF's own default). |
| `frames` | 150 | All baked wake frames together. |
| `sizeKb` | 1200 | Soft limit for the APK. Above it the planner lowers `fps`, then drops loops, lowest priority first (front before back, small effect before big), and says so. |
| `wake` | `visible tap` | When loops play. `none` keeps the face still. |
| `options` | every option `editor` | Per option: `editor` (a `ListConfiguration`, chosen in the system face editor) or `baked` (AAPS's choice is built in; a change means a rebuild and a push). |
| `weather` | `aaps` | `aaps`: our phone's weather through a block or value slot. `system`: WFF's `[WEATHER.*]`. |
| `sky` | `fit` | `fit`: sun values from fitted sun times (4.3). `complication`: from a value slot. |
| `arrow` | `glucose` | `glucose`: the arrow picture rides in the glucose slot. `slot`: its own slot. `glyph`: the arrow as a sign in the glucose text. |
| `blocks` | none | Settings per block. `update`: `data` (when data arrives) or minutes. A block can also give `rect` to override the bounding box the planner finds. |

### 6.4 Defaults that need no hint

A design without any `wff` key converts like this:

- static art: baked into as few runs as possible;
- clock, date, hands, user slots, options: live;
- AAPS views: glucose and age slots; everything else in blocks, grouped by nearness (views whose
  boxes are close go in one block) until the slot budget fits;
- wake effects: one sequence per run of animated layers, `fps` 15, cropped, played on wake and
  tap, then hidden; trimmed to the frame and size budgets. The planner samples `visible` and
  `alpha` over the wake to find when a layer shows:
  - a layer that shows the whole wake (snow, rain) becomes a loop of `min(wake, 2 s)` (the prototype's snow loop length) played until
    the wake is over. A seam can show where the loop starts again; the lint says so.
  - a layer that shows only for a part of the wake (Santa) is baked over that part and played once,
    with `delayPlay` for its start;
- `@moon` and place-based sky: the sky block;
- view layers that only move during the wake: still.

### 6.5 Examples

A cheaper aurora: wake motion at 8 frames per second, only for the default choice of the option.

```json
{"image": "Aurora", "slices": 100, "rest": 0, "...": "...",
 "wff": {"motion": "loop", "fps": 8, "seconds": 3, "options": "default"}}
```

Stars that stay still on WFF, on purpose (no warning about the lost twinkle):

```json
{"shape": "circle", "count": 45, "...": "...",
 "wff": {"mode": "bake", "motion": "still"}}
```

(45 copies stay part of one baked picture. `motion: "clock"` would need 45 live elements, over the
default copy limit of 24, so the lint would refuse it.)

A battery that blinks while low, all the time instead of only on wake:

```json
{"use": "blink", "valueKey": "watch_battery", "atOrBelow": 20, "view": "delta",
 "wff": {"motion": "clock", "env": 1}}
```

The graph and the status texts in named blocks:

```json
"chart": {"width": 340, "height": 140, "...": "...", "wff": {"mode": "complication", "block": "graph"}},
"iob2":  {"...": "...", "wff": {"mode": "complication", "block": "status"}}
```

A moon from baked phase pictures instead of the sky block, live position from the clock:

```json
{"image": "@moon", "...": "...", "wff": {"mode": "bake", "steps": 8}}
```

---

## 7. Budgets and checks, and the lint

### 7.1 Complication slots: at most 8

The planner counts: user slots (the design's visible `complication1` to `5`) + glucose (1) + age (1)
+ arrow (0 or 1) + value slots + blocks. When the count is over 8 it merges blocks (nearest
first), then moves value slots into blocks (a gauge that needed a number becomes a picture in the
block), and fails only when glucose, age, arrow and the user slots alone are over 8. WFF 5's
`ListOption complicationSlotIds` can hide option-only slots in the editor, but the slots are still
in the face; the planner counts them.

### 7.2 APK size (sent over Bluetooth)

Soft limit 1200 KB (hint `sizeKb`), hard limit to be set after measuring the transfer (11).
Measures, in order: JPEG or lossy WebP for opaque pictures; PNG or WebP with alpha for transparent
ones; crop every picture to its box; fewer frames; drop loops by priority. The prototype shows the
size is mostly in pictures: a 400 x 400 sky as PNG was 350 KB, sparse snow frames 4.7 KB each.

### 7.3 Face memory: 100 MB active, 10 MB always-on

A picture costs width x height x 4 bytes when decoded, at the size it is baked. At 450 x 450 a full
picture is 0.81 MB. So 100 MB holds about 120 full-screen frames; always-on holds about 12 full
pictures. Cropping matters more than file size here. The planner computes the footprint the same way
and the validator checks it (it runs `MEMORY_FOOTPRINT`). Baked variants per option count per
configuration, not summed, if the validator's per-configuration calculator does what its name says
(to confirm with a test design).

### 7.4 Frame counts

`frames = seconds x fps` per loop, all loops together at most `frames` (default 150). Aurora's
Santa crosses in about 11.5 seconds: at 10 fps that is 115 frames alone. Cropped to a strip it is
cheap in memory, but it is still many files. The lint shows frames, decoded MB and estimated KB per
loop.

### 7.5 WFF version per watch

- Push needs Wear OS 6, so the floor is **WFF 4**. The converter writes 4 by default and uses WFF 5
  features (`verticalAlign`, `Group` blend, `complicationSlotIds`) only when the watch reports 5.
- The watch reports its version with the screen size. The simplest source is `SDK_INT` (36 gives 4,
  37 gives 5). Whether the runtime exposes its exact format version is an open point (11).
- Fallback: a feature the watch's version lacks goes one tier down (live to bake). The plan is made
  per version, so the lint can show "on Wear OS 6 this layer is baked, on Wear OS 7 it is live".
- The manifest property `com.google.wear.watchface.format.version` gets the chosen version, and the
  validator checks the XML against that version's schema.

### 7.6 The lint

The planner's output, shown in three places:
1. the design tests in `~/Projects/aaps-watchfaces` (a JVM call, one report per design);
2. the AAPS phone app, where a design is chosen ("On Wear OS 6 watches: 3 parts are left out, 2 are
   still");
3. the converter itself, before it builds.

One line per layer and view, in paint order, then the totals:

```
Aurora Town -> WFF 4, screen 450 px
 L0  stars (45 copies)        bake  static run 1; twinkle dropped (motion still)
 L1  moon halo                complication  block "sky"
 L9  Aurora (slices 100)      bake  static run 1 (rest pose); sway dropped:
                                    3 option choices x 24 frames is over the size budget (warning)
 L11 Santa                    bake  loop "santa" 11.5 s x 10 fps, strip 450 x 68
                                    shown by option santa + date (live condition)
 L19 clock() AuroraCut        live  DigitalClock, font AuroraCut
 L21 glucose colour           live  in slot "glucose", thresholds 70 / 180 mg/dl
 L23 old reading bar          live  in slot "glucose", age from slot "age" through [REFERENCE]
                                    (WFF 4; see note 1)
 L24 snow                     bake  loop "front" 2 s x 15 fps, repeated over the wake
 complication1..3             live  user slots 1..3, round style
Slots 6 / 8   frames 145 / 150   APK ~ 620 KB / 1200   memory active ~ 40 MB / 100, always-on ~ 2 MB / 10
Errors 0   Warnings 4
 note 1: if [REFERENCE] is not available, the glucose provider sends a stale flag instead.
```

Severities: **error** (the build stops: a safety view cannot be placed, a `live` hint cannot be
met, the hard size limit), **warning** (something visible is lost or changes: motion dropped, a
blink that now runs all the time, weather from another source), **info** (how it was done).

---

## 8. Worked example: Aurora Town

`Aurora_Town.zip`, 306 KB, 27 layers, 3 options (`moon`: Still / Across the sky / Real sky;
`aurora`: Calm / Normal / Lively; `santa`: At Christmas / Always / Never), wake 15 s at 24 fps with
`fadeMs` 1500. Visible classic views: `background`, `cover_chart`, `complication1` to `3`,
`direction`, `hour_hand` and `minute_hand` (the church tower clock). The glucose and the time are
text layers, not views.

### 8.1 Layer by layer

| Layer | What it is | Plan | Notes |
|---|---|---|---|
| background, `cover_chart` | sky photo, a frame | B static run 1 | opaque, JPEG |
| L0 | 45 stars, masked by `SkyMask`, twinkle in the wake | B run 1 (rest alpha `0.35 + 0.6 r4`); twinkle into the back loop or dropped | `r1..r6` from the engine's seed 11, so the stars are where the watch puts them |
| L1, L2 | moon halo, place-based (`v_moon_cycle`), option `moon` | C sky block | |
| L3 to L8 | the moon: dark disc, `@moon` photo, lit part by `group` + `erase` + `inside`, all masked by `SkyMask`, moved by `mdx` / `mdy` | C sky block | needs `v_moon_limb`, `v_moon_north`, `v_moon_cycle` (place). The block covers the moon's path (about 400 x 230) and the engine draws all three options. Alternative (`"steps": 8`): baked phase pictures by `[MOON_PHASE_TYPE]`, position from the clock for "Across the sky", as in the prototype; it loses the limb angle and the hills' mask unless `renderMode="MASK"` works. |
| L9, L10 | two aurora pictures, 100 slices, sway in the wake, speed by option `aurora` | B run 1 (rest pose: no sway); sway as a back loop if the budget allows | the heaviest effect: a sky-wide loop per option choice. By default it stays still, with a warning (3 choices are over the size budget). The hint in 6.5 adds a 3 s loop at 8 fps for the default choice only. |
| L11 | Santa crossing in the wake, front slot, `santa` formula (option + 24 and 25 December) | B loop "santa", cropped strip, about 11.5 s at 10 fps | shown by a live `Condition`: `[CONFIGURATION.santa] == "1"` or (`"0"` and `[MONTH] == 12 && ([DAY] == 24 \|\| [DAY] == 25)`) |
| L12 to L17 | 186 + 55 + 4 x 14 lights, `blend` add, blink in the wake | B run 1 (rest alpha 0.9 or 1); blink into the back loop or dropped | 300 lights as elements would be far too many |
| L18 | star glow, wobble in the wake | B run 1; wobble small loop or dropped | |
| L19, L20 | `clock()` twice: AuroraCut red, AuroraSnow white on top | L two `DigitalClock` / `TimeText`, fonts in `res/font` | proven; the white one hides in always-on |
| L21, L22 | glucose twice, colour by `v_sgvLevel` | C glucose slot: two `PartText` `[COMPLICATION.TEXT]`, `Condition` on the value with the thresholds | the white snow copy hides in always-on |
| L23 | bar over an old reading; width from the text length of `bg()` | C: in the glucose slot, visible when the age is 12 minutes or more | width: `(textLength - dec) * 43.85 + dec * 17.14`, where `dec` is a **build-time constant** (mmol has a decimal sign, mg/dl has none). The age lives in the age slot, so this needs `[REFERENCE.age]` (WFF 4, unproven across slots). Fallback: the glucose provider sends the reading again at 12 minutes with a stale flag in its data. |
| L24 to L26 | snow: 120 flakes, 70 crystals, 1 big crystal, front slot | B loop "front", 2 s at 15 fps (30 frames), played until the wake is over | as in the prototype, 4.7 KB per frame |
| `direction` | design's arrows | C glucose slot `SMALL_IMAGE` | |
| `hour_hand`, `minute_hand` | tower clock | L `AnalogClock` | proven |
| `complication1` to `3` | round dark slots with a ring | L user slots 1 to 3 | proven |

`bg()` (own function) is inlined. `let` names `at`, `am` (aurora speed) are constants per option
choice, so they only change the baked frames of each choice. `ma`, `mdx`, `mdy`, `mup` belong to the
moon (sky block). `santa` becomes the live `Condition` above.

### 8.2 Slots

| Id | Slot | Type |
|---|---|---|
| 1, 2, 3 | user slots (`complication1` to `3`) | wearer's choice |
| 10 | glucose (value, text, delta, arrow picture) | `RANGED_VALUE` |
| 11 | age (dynamic) | `RANGED_VALUE` |
| 20 | sky block (moon, halo) | `SMALL_IMAGE` |

6 of 8. With the arrow in its own slot (if 5.1 fails): 7.

### 8.3 Document order

```
PartImage run 1 (sky, stars, aurora rest, lights, star glow, cover_chart)   AMBIENT alpha 0
PartAnimatedImage loop "back" (aurora sway, per option: ListConfiguration)  AMBIENT alpha 0
ComplicationSlot 20 sky block                                               AMBIENT alpha 0
AnalogClock tower                                                           AMBIENT alpha 0
ComplicationSlot 1, 2, 3
ComplicationSlot 10 glucose (two texts, colour Condition, arrow, old bar)
ComplicationSlot 11 age (the Reference source)
PartAnimatedImage loop "santa" in Condition (option + date)                 AMBIENT alpha 0
DigitalClock red, DigitalClock white (white AMBIENT alpha 0)
PartAnimatedImage loop "front" (snow)                                       AMBIENT alpha 0
PartImage tap area over the glucose -> Launch MainMenuActivity
```

The order follows the design's slots: the moon is in slot back, so its block lies under the tower
and the views; the snow is in slot front, so it falls over the clock. The glucose text in the
design is in slot front, above the tower; since the tower and the glucose do not overlap, the order
between them does not matter.

### 8.4 Budget estimate (to be measured)

| Part | APK | Decoded |
|---|---|---|
| run 1, 450 x 450 JPEG | ~120 KB | 0.8 MB |
| fonts | 11 KB | n/a |
| snow loop, 30 frames, PNG | ~140 KB | 24 MB (could be cropped less, it covers the face) |
| Santa loop, 115 frames, strip 450 x 68 | ~350 KB (estimate) | 14 MB |
| aurora loop, 24 frames x 3 choices, sky 450 x 260, WebP | ~1.2 MB (estimate) | 11 MB per choice |

Without the aurora loop: about 0.6 MB and 40 MB, inside the defaults. With it, the APK goes over
1200 KB, so by default the planner keeps the aurora still and warns; the hint in 6.5
(`"options": "default"`, 8 fps, 3 s, so 24 frames) adds about 0.5 MB and needs `"frames": 170` at the top level.

### 8.5 What Aurora loses on WFF

- The aurora sways only within the budget (or not at all), the stars and lights do not twinkle,
  the star glow does not wobble. The face is still lively through the snow and Santa.
- The loops cannot fade out with `env`: they end hard (`afterPlaying="HIDE"`). A fade could be baked
  into a last "out" sequence that starts with `delayPlay`; whether WFF keeps that timing is open.
- Santa's speed is fixed; he does not follow a changed wake length until a rebuild.
- In always-on: clock (red), glucose (green), age and the user slots. The design's art is hidden,
  as on our code face.

---

## 9. Check against a data-heavy design: Cyberpunk Tech

`Cyberpunk_Tech.zip`, 194 KB. Views: `sgv`, `direction`, `timestamp` (age colour), `delta` and
`avg_delta` used as battery icons (21 pictures each, by `watch_battery` and `uploader_battery`),
`rig_battery` and `uploader_battery` as battery numbers, `iob1` / `iob2`, `cob1` / `cob2`,
`basalRate`, `reservoir`, labels `freetext2` / `freetext3`, `loop` ("LOOP 3'" with a colour by
age), `chart` with `graphLineWidth`, `hour` / `minute` with a static ":" (`freetext1`), `day_name`,
`day`, `month`, `complication1` and `2`, a full-screen `cover_plate`, the moon template on
`freetext4` and a weather cloud on `patient_name`, both moved along an arc by `v_night_progress`.
Animation: a rain template (140 lines), `blink` on four battery views at 20 % or less, a beating
logo (`pulse` with glow), and two view layers with `rest`.

### 9.1 Plan

| Part | Plan |
|---|---|
| background | B run 1 |
| rain | B loop "back" (thin lines, small PNG frames), between the background and the data |
| `sgv` | C glucose slot, colour by thresholds, font Cyber |
| `direction` | C glucose slot arrow |
| `timestamp` | C age slot; `dynData bgAge` colour as a `Condition` on the age |
| `delta` (watch battery icon, 21 pictures) | L `Condition` on `[BATTERY_PERCENT]`, 21 branches |
| `rig_battery` (watch battery number) | L `PartText` with `[BATTERY_PERCENT]`, font Cyber |
| `avg_delta` (phone battery icon), `uploader_battery` (number) | C value slot `uploader_battery` (the existing uploader battery provider already sends `RANGED_VALUE`), 21 branches inside it |
| `iob1`, `iob2`, `cob1`, `cob2`, `basalRate`, `reservoir`, labels | C block "status" (x 244 to 359, y 100 to 196) |
| `loop` | C block "status" (the box grows to x 172), or its own block |
| `chart` | C block "graph" |
| `hour`, `minute`, ":" | L `DigitalClock` hours and minutes in font Cyber; ":" baked |
| `day_name`, `day`, `month` (`allCaps`) | L `PartText` with `Upper` |
| moon on `freetext4`, cloud on `patient_name`, moved by `v_night_progress`, cloud by weather looks | C block "sky" (the arc at the top, about 400 x 200) |
| `complication1`, `2` | L user slots 1, 2 |
| `cover_plate` | B transparent run 2, above all data (it lies over them in the design) |
| `blink` on the watch battery views | O by default (warning); with `motion: "clock"` a live blink while low |
| `blink` on the phone battery views | O (warning); inside the value slot the same clock blink is possible |
| `pulse` logo | B loop, baked at `fallbackBpm` 60 (warning: the heart rate is not followed) |

### 9.2 Slots

| Id | Slot |
|---|---|
| 1, 2 | user slots |
| 10 | glucose (with the arrow) |
| 11 | age |
| 21 | value: phone battery |
| 22 | block "status" (with loop) |
| 23 | block "graph" |
| 24 | block "sky" |

8 of 8. This design hits the limit. If the arrow cannot ride in the glucose slot (5.1), the planner
must merge: the phone battery icon and number move into the status block (the icon becomes part of
a picture; still correct, it only updates with data), which frees a slot. If `loop` keeps its own
block, the count is 9 and the planner merges it into "status". The lint reports each merge.

What the check shows:
- The tier model holds: every view has a home, and nothing safety-related is lost.
- **The slot budget is the main constraint** for data designs, more than size or memory. Blocks
  are what makes these designs fit, so the block provider is not optional.
- Two features of this design have no exact WFF form: wake-only blinking of data views and a
  heartbeat at the real rate. Both become "visibly different" with a warning, not silently dead.
- `cover_plate` over the data views forces a second transparent run. That is cheap (one picture).

### 9.3 Grand Complication (a second data point)

Gauge hands turned by values (`dynData rotationOffset`): the glucose hand (`patient_name` with
`BgHand`, -60 to 60 degrees over 40 to 300 mg/dl) becomes a `PartImage` with a `Transform angle`
inside the glucose slot, so that slot's box must be large enough to hold the hand (152 x 152 around
the dial). The watch battery hand is live on `[BATTERY_PERCENT]`, the phone battery hand needs a
value slot. Hand colours by level are `tintColor` in `Condition` branches. `dynPref` (on
`key_show_complication1`, `key_show_iob`, `key_show_cob`, `key_units`) is resolved at build time, so
the phone needs those watch preferences and a change means a rebuild. The sun and moon on the sub
dial (`v_sun_cycle`, `v_moon_cycle`) go to the sky block, or the sun alone to fitted sun times.

---

## 10. Testing

1. **Planner tests (JVM).** For every design zip in `~/Projects/aaps-watchfaces/cwf-designs`, the
   plan (tier per layer and view, slots, budgets) as a golden text file. A change in the planner
   shows as a diff. Runs in the design repo's tests and in AAPS.
2. **Expression tests (JVM).** For each translated formula, evaluate the engine's formula and the
   printed WFF expression at many sample inputs and compare. This needs a small evaluator for the
   WFF subset we print (operators, `? :`, the functions we use). Its weak point is that it is our
   reading of WFF, so step 4 checks it against the real runtime.
3. **Validator in tests (JVM).** Run the validator CLI (already a Gradle dependency) on each
   converted design: it must give a token, and the memory footprint must be inside the budget.
4. **Expression probe face (emulator).** A test face where each translated expression drives a
   green or red box (`Condition` on "expected value"). Set the emulator clock to fixed times
   (`adb shell date` on the emulator) and take screenshots. This settles `%` with negative numbers,
   radians, `DAY_OF_WEEK` numbering, `icuText` patterns, `numberFormat`.
5. **Picture compare (emulator).** The engine draws the rest frame on the JVM (`:wear:cwfrender`
   with Robolectric native graphics, as `CwfPreviewRendererTest` already does) with fixed values and
   a fixed time. The WFF face runs on the Wear OS 6 emulator with a debug mode of the new providers
   that sends the same fixed values; `adb exec-out screencap` takes the picture. Scale, align and
   compare per region: baked runs must match closely; live text regions get a looser limit (font
   rasterising differs); a region that differs much is a failure with a diff picture.
6. **Loop timing (emulator).** `adb shell screenrecord` during a wake: the loop must start on
   visible and on tap, play the expected frames and hide at the end.
7. **Install path (emulator).** A debug entry in the wear app that installs an APK and token from
   a file (`adb push`), through the same `WatchFacePushHelper` code. Later the full phone to watch
   path with a paired phone emulator or a real phone.
8. **Real watch (Wear OS 6, owner's Galaxy).** Only on the owner's request: transfer time over
   Bluetooth, update keeps the editor choices and the user slots, always-on look and the age counting
   while AAPS sleeps, taps per region, battery use over a day with and without loops, how often the
   blocks redraw.

---

## 11. Open questions and risks

Questions an emulator test can answer (cheap, do first):

1. Does `[REFERENCE.x]` published inside one `Complication` update in a `Transform` or `Condition`
   outside it, and inside another slot? (Aurora's old-reading bar depends on it.)
2. Does `[COMPLICATION.SMALL_IMAGE]` show inside a `RANGED_VALUE` complication? (Saves a slot.)
3. Does a dynamic `RANGED_VALUE_VALUE` (from a `DynamicFloat`) drive a `Condition`, and keep
   counting in always-on?
4. What does `renderMode="MASK"` do exactly, and can it replace our `clip.mask`, `rings` and
   `boxes`? Does a `Group` clip its children to its box?
5. Does `blendMode="PLUS"` match our `add`, and `SCREEN` our `screen`, against what lies below?
6. Which shape attributes take a `Transform` (`width`, `height`, `endAngle`, line ends)?
7. Does `[CONFIGURATION.colour1]` work in a `color` attribute (`ColorConfiguration`)?
8. Does a literal `displayName` (not `@string`) work, as the template APK needs?
9. `TAP` while a loop plays: restart, ignore, or queue? Does `delayPlay` give a reliable "in, loop,
   out" chain?
10. WFF `%` with a negative left side; `MOON_PHASE_POSITION` range; `DAY_OF_WEEK` numbering;
    `icuText` pattern letters; `numberFormat` arguments.
11. How does the runtime report its WFF version? Is `SDK_INT` enough?
12. Does the validator's per-configuration footprint count option variants per configuration (not
    summed)?

### 11.1 Answers from the Wear OS 6 emulator (2026-11-20)

Probe faces were written by hand, built with aapt2, zipalign and apksigner, and set with
`adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation
set-watchface --es watchFaceId <package>`. A small probe provider app sent the data (a settable
`RANGED_VALUE`, a dynamic age, a `SMALL_IMAGE` of a chosen size).

| # | Answer |
|---|---|
| 1 | **Yes.** A `Reference` (`source="angle"`, the angle set by a `Transform` from `[COMPLICATION.RANGED_VALUE_VALUE]`) inside one slot is read live in another slot's `Complication`, outside all slots in a `Template` and in a `Condition`. Both ways. |
| 2 | **No** for `[COMPLICATION.SMALL_IMAGE]` in a `RANGED_VALUE` complication: not drawn. **Yes** for `[COMPLICATION.MONOCHROMATIC_IMAGE]`, which keeps its colours without `tintColor`. `[COMPLICATION.TITLE]` works too. |
| 3 | **Yes** with the screen on: a `DynamicFloat` (minutes since the reading) ticks by itself, drives a `Condition` and a `Reference`, and keeps counting with the provider's process stopped. Always-on cannot be seen on the emulator (the display goes off). |
| 4 | Inside a `Group`, a part with `renderMode="MASK"` masks the parts with `renderMode="SOURCE"` (a square became a circle). A `Group` does **not** clip its children to its box. |
| 5 | `PLUS` is an exact add (#404080 + #808040 = #c0c0c0), `SCREEN` is 1 - (1 - a)(1 - b) (#a0a0a0). Both equal ours. |
| 6 | `Transform` works on `Rectangle width`, `Line endX` and `Arc endAngle`. |
| 7 | **Yes**: `ColorOption colors="#a #b"`, read as `[CONFIGURATION.<id>.0]` and `.1` in a `Fill color`. |
| 8 | **No**: with literal `displayName` texts the editor shows "1st option" for every setting and choice. Names must be string resources. So the APK step must write strings into `resources.arsc` (placeholders in a template, or our own `resources.arsc` writer). |
| 9 | A tap while a `TAP` loop plays starts the loop again from frame 0. |
| 10 | `%` keeps the sign of the left side (`-7 % 3` = -1). `fract(-0.25)` = -0.25 (ours: 0.75). `5 / 0` = 0. `round(-2.5)` = -2, `round(2.5)` = 3. Radians. `[MOON_PHASE_POSITION]` is a fraction (9.42 and growing), not a whole day. `[DAY_OF_WEEK]`: 1 is Sunday. **`icuText` takes an ICU skeleton, not a pattern**: `"yyyy-MM-dd"` gave `11/20/2026`. `numberFormat` rounds half to even. Joining texts with `+` does not work: one `Parameter` per value. |
| 11 | A face that declares format version 5 is refused on Wear OS 6 ("could not resolve resource-only watch face"). The runtime APK has no version property. `SDK_INT` 36 means WFF 4. |
| 12 | **Per choice.** Active memory is the sum of decoded pictures per configuration: 135 full noise pictures in one face fail ("104.28 MB in active"), the same as 3 `ListOption`s of 45 pass. Always-on counted only 0.77 MB for that face, and 20 half-transparent full pictures passed, so the 10 MB always-on limit is far looser than 7.3 assumed. Plain pictures count less than noise (the calculator quantizes). |
| 5.4 | A `SMALL_IMAGE` of 1200 x 1200 px is accepted and scaled into a 200 x 200 slot. |

What this changes:
- 5.1: the arrow is the glucose slot's monochromatic image.
- The printer (section 3) never maps `date(pattern)` to `icuText` (a pattern is not a skeleton),
  and writes our `%` as `a - b * floor(a / b)`.
- The engine now gives `fract`, `icuText` and `[MOON_PHASE_POSITION]` the runtime's meaning (the
  owner's choice, 2026-11-20): `fract` keeps the sign, `icuText` reads a skeleton with the ICU of
  Android, and the moon position is even between the true quarters (Meeus chapter 49; 12 emulator
  samples from 2020 to 2027 match to 0.003). No design zip depended on the old meanings.
- Step 5 must write string resources.

Questions only a real watch can answer:

13. Bluetooth transfer time for 0.5 to 2 MB through the Channel API, and how reliable it is.
14. Does `updateWatchFace` with a different signing key work (new phone, reinstalled AAPS)? If not,
    the face must be removed and added again, which resets the wearer's choices. The key should be
    backed up with the AAPS settings export.
15. Battery cost of loops on every wake, of clock motion, and of blocks that redraw every few
    minutes, against our code face and against the picture face.
16. Whether the OEM (Samsung) keeps the 8 slot limit, the 100 MB / 10 MB limits, and shows
    WFF always-on as the emulator does.
17. Whether many elements (21 battery branches, two clocks, several loops) render smoothly on a
    Galaxy Watch 4 class CPU.
18. How long the face is blank or flickers during an update push (matters for how often to rebuild).

Risks:

- **Scope.** A converter that covers every feature is large. The build order (12) gives a useful
  face early (static bake + live clock + glucose slots) and adds tiers step by step.
- **Two renderers drift.** Live text, gradients and blends are drawn by WFF, not by us, so they
  will look a little different. The picture compare (10.5) keeps that visible.
- **Hidden coupling with the engine.** The converter reads the engine's syntax tree and needs a
  "draw these layers at this `t`" call. Both are engine changes, and other sessions work on those
  files now (`wff-aligned-names`). They must be agreed, not patched in from here.
- **The data path is the riskiest part.** Glucose must be right and on time. The new providers must
  follow the same rules as the existing ones (async, never block the main thread or the loop), and
  the build must fail closed (keep the picture face) rather than ship a face without a working
  glucose slot.
- **Push gives one slot.** The converted face replaces the picture face; switching between them
  resets the face's own settings. The phone UI must say so.
- **Watch Face Push and the validator are alpha libraries** (`1.1.0-alpha01`). APIs and rules may
  change.
- **Size of the phone APK.** The validator library adds megabytes to AAPS. Measure before deciding.

---

## 12. A build order

Each step gives something usable and answers some of the open questions.

1. **Proofs on the emulator** (questions 1 to 12), with hand-written test faces like the prototype.
2. **The providers** (glucose `RANGED_VALUE` with arrow, age with a dynamic value, `Block1..4`,
   `Value1..4`) and a hand-written Aurora face that uses them. This proves the data path before any
   converter code.
3. **The planner and the lint** as a JVM library, run in the design tests over all designs.
4. **The baker**: the renderer call that draws chosen layers and views at a chosen `t` into a
   transparent bitmap; static runs first, then loops, then variants.
5. **The writer and packaging**: `watchface.xml` from the plan, the template APK from Gradle, zip
   alignment, signing, `validator-push-android` on the phone.
6. **Transfer and push**: Channel API, a third `PushedFace`, the rebuild triggers of 2.3.
7. **Hints**: the `wff` keys, after the defaults have been tried on real designs, so the hints solve
   real cases and not guessed ones.
