---
name: wff-parity
description: Run the parity suite that compares each feature of the watch face layer engine (CWF) with its Watch Face Format (WFF) face. Use before committing a change to the layer engine, the WFF planner or complication providers, :wear:cwfrender, docs/watchface-animation.html or a design zip, and when a WFF face looks different from our own face.
---

# The CWF and WFF parity suite

The WFF converter and its parity suite live in their own repository, `aaps-face-tool` (next to this
checkout, for example `~/Projects/aaps-face-tool`). It builds against an AAPS checkout as an included
Gradle build; AAPS does not depend on it. The full procedure is the skill `wff-parity` of that
repository (`.claude/skills/wff-parity/SKILL.md`) and its `docs/wff-parity.md`.

Run it before you commit a change to any of these in AAPS:

- the layer engine: `wear/src/main/kotlin/app/aaps/wear/watchfaces/animation/`
- the planner of the data picture: `wear/src/main/kotlin/app/aaps/wear/watchfaces/wff/`
- the WFF complication providers: `wear/src/main/kotlin/app/aaps/wear/complications/wff/`
- `CustomWatchface.kt`, the classic views it draws, and `:wear:cwfrender`
- `docs/watchface-animation.html` (a change of what a feature means)

In short: set `aapsDir` in the tool's `local.properties` to the AAPS checkout you changed, then run
`wffParityTest` there (and `-PwffParityEmulator` with a Wear OS 6 emulator and a fresh
`:wear:assembleFullDebug` of this checkout). Every Gradle build goes through
`sh ~/.claude/bin/gradle-locked.sh <folder> <log> <tasks>`.
