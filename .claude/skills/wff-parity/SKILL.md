---
name: wff-parity
description: Run the parity suite that compares each feature of the watch face layer engine (CWF) with its Watch Face Format (WFF) face. Use before committing a change to the layer engine, the WFF converter, the WFF complication providers, :wear:cwfrender, docs/watchface-animation.html or a design zip, and when a WFF face looks different from our own face.
---

# The CWF and WFF parity suite

Our own watch face engine (CWF, the layer engine) and the faces that the converter makes for the
Watch Face Format (WFF) must look the same. The parity suite has one small design per feature of
the engine. Each test converts the design, checks the face, and, on the emulator, compares the
picture of the WFF face with the picture our engine draws at the same moment with the same values.

The suite lives in the design repository (`aaps-watchfaces`, folder
`src/test/kotlin/app/aaps/watchfaces/wffparity`), because it needs Pack and the emulator tools of
that repository. Its documentation is `docs/wff-parity.md` there.

## When to run it

It takes minutes, so it is not part of `testFullDebugUnitTest` or CI. Run it before you commit a
change to any of these:

- the layer engine: `wear/src/main/kotlin/app/aaps/wear/watchfaces/animation/`
- the converter: `wear/src/main/kotlin/app/aaps/wear/watchfaces/wff/`
- the WFF complication providers: `wear/src/main/kotlin/app/aaps/wear/complications/wff/`
- `CustomWatchface.kt`, the classic views it draws, and `:wear:cwfrender`
- `docs/watchface-animation.html` (a change of what a feature means)
- a design zip that will be shipped as a WFF face

A change elsewhere in the wear app does not need it.

## How to run it

In the design repository, with its `aapsDir` set to the AAPS checkout you changed. Every Gradle build
goes through `sh ~/.claude/bin/gradle-locked.sh <folder> <log> <tasks>` (see the global rules).

1. Once: build Pack, `sh tools/build-pack.sh` (see `docs/wff-apk.md`).
2. Without the emulator (converts, validates and checks the lint of every feature): `wffParityTest`.
3. With the emulator: start a Wear OS 6 emulator (API 36), build the debug wear app of the AAPS
   checkout (`:wear:assembleFullDebug` in AAPS), then `wffParityTest -PwffParityEmulator`. The run
   installs that wear app when it changed, and refuses one that is older than its sources.
4. While you work on one feature, run only its part: `-PwffParityOnly=ShapesText,ClassicFace.secondView`.
   A face that did not change keeps its last emulator picture, so a run again is quick.

The tests run side by side on the JVM and queue their faces; one driver shows them on the emulator
while the tests still run. Do not start a second run in the same folder at the same time (the lock
script makes it wait). The scripts refuse a real watch.

## Reading the result

`build/wff-parity/summary.html` lists every compared face, the differing ones first. Per test, in
`build/wff-parity/<class>/<test>/`:

- `result.txt`: the lint, the share of changed pixels per region, and why it failed
- `diff.png`: our engine, the WFF face on the emulator, and the changed pixels in red
- `lint.txt`, `face/`, `face.apk`: what the converter made

A difference is a bug in one of three places. Find which before you change anything:

- the converter writes a WFF part that means something else (fix the converter);
- WFF cannot do it (the planner must bake the part or leave it out, and the lint must say so; the
  test then checks the lint, not the picture);
- the emulator cannot show it (always-on, sensors). Say so in the test, do not loosen the limit.

Do not raise a region's limit to make a test pass without saying why in the test.
