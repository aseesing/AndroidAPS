---
name: watchface-animation-manual
description: Keep the manual of the watch face layer engine (docs/watchface-animation.html) true to the code. Use in the same change whenever a key, a default or a limit, a formula function or variable, a `v_` value, a template rule, a look rule or a drawing rule of the wear animation engine changes, and before trusting the manual to write a design.
---

<!-- PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md). -->

# The manual of the watch face layer engine

`docs/watchface-animation.html` is the manual for people (and AI sessions) who write watch face
designs. It is one page with no outside files. A small JavaScript player inside the page draws a live
picture for every example, from the JSON text the reader sees.

There is no test for this page. **You are the check.** Do the steps below by reading the code and the
page yourself. Do not add a script or a test for it (the user's decision, 2026-11-19).

## When to do this

In the same commit as any change to what a design can say or to what the watch does with it:

- `wear/.../watchfaces/animation/` (the parser `WakeAnimationJson`, `AnimLayerSpec`, `Formula`,
  `LayerVariables`, `LayerAnimator`, `Templates`, `WakeAnimationSpec`);
- `wear/.../watchfaces/design/Looks.kt`;
- `wear/.../watchfaces/utils/` (`DynamicImages`, `FrameLayers`, `ViewPainter`, `ViewImages`,
  `DesignTimeText`);
- the enum `ValueMap` and the reading of the design in `CustomWatchface`;
- `shared/impl/.../weardata/CwfPlaceNeeds.kt` (when a design needs the place).

A change that only makes the same thing faster, with the same pixels, needs no change of the page.
Say in your report that you checked and why nothing changed.

## The check

Read the changed code first, then the page. Compare these pairs, one by one. In each pair, nothing
in the code may be missing in the page, and nothing in the page may be missing in the code.

| In the code | In the text of the page | In the player of the page (the `<script>`) |
|---|---|---|
| Keys the parser reads (`WakeAnimationJson`), with type, default and limit | The chapter of the key and the key table in chapter 12 | `readAnimation`, `readLayer`, `readSource`, `readGradient`, `readCopies`, `readLets` |
| `LayerProp` (the props that take a formula) and `LayerProp.CLIPS` | Chapter 3 and chapter 12 | `PROPS`, `CLIP_PROPS` |
| `Formula.FUNCTIONS` and the operators of the parser | Chapter 5, with a curve for a function that shapes time | `FUNCTIONS`, `FN`, `parseFormula` |
| `LayerVariables.FIXED` and the `v_` prefix | Chapter 5, "Variables" | `FIXED`, `V` |
| The limits (`AnimLayerSpec.MAX_*`, `WakeAnimationSpec.MIN_DURATION_MS` and so on, `Formula.MAX_*`) | Chapter 11 and the tables | The `MAX_*` and `MIN_*` lines at the top of the script |
| The rules of `Templates` (the app has no templates of its own) | Chapter 7 | `expandTemplates` |
| The nine templates in `TemplatesForTests` (wear tests) and `DesignTemplates` (repository `aaps-watchfaces`): the same text | Chapter 7: the `templates` of each template example, and the parameter table under it | Nothing: each example carries its templates |
| `ValueMap` in `CustomWatchface` (every key and its range) | The table in chapter 6 | The values an example reads must exist in `ValueMap` |
| `Looks` (`when`, `patch`, `images`, the order) | Chapter 9 | `applyLooks` |
| How a layer is drawn (`LayerAnimator`, `FrameLayers`, `ViewPainter`) | The text beside the examples, "Known limits" in chapter 11 | `Animator`, `beginFrame`, `Scene` |

Then:

1. **Every example is still true.** For each changed key or rule, find the examples that use it
   (search the page for the key in quotes). The JSON must be valid for the real parser: real key
   names, real value types, values inside the limits.
2. **A new feature gets its own example**, with a picture, in the chapter where it belongs, and a row
   in the key table of chapter 12. Add it to the contents at the top when it gets a heading.
3. **A removed feature leaves the page**: the text, the examples, the table row and the player code.
4. **Open the page in a browser** and scroll through all of it. A broken example shows its error in
   the picture box. Only the example "Three mistakes" in chapter 11 may show errors. Look at the
   pictures of the examples you changed: do they show what the text says?
5. **Every example that uses `use` carries its templates** in `animation.templates`. The page has no
   templates of its own, as the app has none.

## Rules for the text

- Plain school English, short sentences. No em dash or en dash as a dash.
- Write plainly. State facts, do not sell them (the user's style rules, 2026-11-19). Avoid:
    - a tail with "-ing" that adds a comment: ", making it...", ", ensuring...", ", allowing...";
    - a tail that praises the first half: ", which means...", ", and that is the point";
    - "not X, but Y" and "it is not just X, it is Y";
    - a noun with a grand second name: "the settings menu, the heart of the app";
    - a question you answer yourself: "The result? ...", "Why does this matter? Because...";
    - three short fragments in a row: "No tools. No stress. No surprises.";
    - words that sell: seamless, powerful, game-changer, unlock, elevate, crucial, delve, robust;
    - a closing summary or moral that repeats what was said.
- Emphasis (bold or italic inside a sentence) at most once on the whole page. The bold label that
  starts a caption or a note box is a label and stays.
- A key word (a key, a value, a variable, a function, a template, by its real name) is set in
  fixed-width style (`<code>`), so the reader sees it is a key word. That is not emphasis.
- Vary the length of the sentences. If cutting the second half of a sentence loses nothing, cut it.
- The page says at the top that the pictures show the idea, not the exact pixels. Keep that honest:
  when the player cannot copy a rule of the watch, say so beside the example. Never bend the text to
  fit the player. The Kotlin code is the truth, then the text, then the pictures.
- No outside files, no network, no real person, device or place of the user (examples use Amsterdam).
- Keep the header comment at the top of the file.

## The moon template of chapter 13

The example `ex-moon-template` holds the moon template as JSON to copy. The watch has no copy of it.
The tests of the wear app (`ManualMoon`) and the design repo (`MoonTemplate` in `aaps-watchfaces`) read
it from this page: keep the figure id, keep its `<pre>` valid JSON with `animation.templates.moon`, and
run `:wear:testFullDebugUnitTest` after a change to it. The designs get the new template when their
zips are built again.

## What the player does not copy

Known, and said on the page: the face is a stand-in (simple views, no complications), `@moon` is a
drawn full moon and the moon values come from two sliders (the page has no place), a group opens its
sheet at its first layer (the watch: at the first one that is drawn), the sun is fixed at 06:00 to 18:00, rest layers are redrawn each frame, the
glow is a canvas shadow, and there is no picture memory limit. Do not "fix" the watch to match these.
