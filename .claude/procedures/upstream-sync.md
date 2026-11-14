<!-- ============================================================================================
     PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
     Own file: keep upstream files to small hooks that call into here.
     ============================================================================================ -->
# Keeping local/personal up to date with upstream

Moved here from `.github/README.md`, which is now kept short. Read this before merging a new upstream
version into `local/personal`. Keep the "Base" part current after each merge; the README itself only
has one sentence about the base.

## Base and branches

The changes 1 to 8 are made on top of upstream **4.0.0** (`master` at `f77abd9b82`). The Dexcom
ONE+ / G7 driver (the Dexcom ONE+ / G7 pull request) is built on upstream `dev`, so merging it also brought the commits of
upstream `dev` that came after 4.0.0. Merged now: upstream **4.0.0** (`master` at `f77abd9b82`) and
pull request #5431 at `13005dae25` (2026-11-12), which holds upstream `dev` up to `951df2fa89`. So
the base of `local/personal` is upstream `dev`, not 4.0.0. Later changes are made on top of that.

Upstream `dev` calls itself `4.0.1-dev-a`. AAPS sees the `-dev` and then turns the closed loop off
unless engineering mode is on. This fork sets the version to **`4.0.1`**, so the closed loop works
as before.

| Branch | What it holds |
|---|---|
| `local/personal` | **The branch to build.** All changes below, merged. |
| `local/wear-battery` | Less battery use on the watch, fewer messages from the phone |
| `local/wear-hr-steps-design` | Heart rate and steps on the watch face (its design *AAPS HR Steps* was removed later) |
| `local/wear-wake-animation` | Wake-up animation, design *AAPS Tech* (now *Cyberpunk*), and later design updates |
| `local/wear-day-night` | Day and weather looks, sun and moon on the edge |
| `local/wear-day-night-faces` | A day face and a night face, chosen in AAPS; the four Cyberpunk and Wilds designs |
| `local/status-and-logs` | Phone: status lights count down to the real end, save logs to Downloads |
| `local/wear-fast-wake` | Fast first frame after a wake, and the header that marks our own files |
| `local/wear-phone-battery` | Phone and watch battery icons on the Cyberpunk and Wilds designs, blinking when low |
| `local/wear-live-preview` | Phone: live preview of the Custom watch face, its settings set on the phone and synced with the watch |
| `local/wear-grand-complication` | The analog *Grand Complication* designs (day and night, Bodoni Moda), the real sun and moon (`sun_cycle`, `moon_cycle`); *AAPS HR Steps* removed |
| `local/wear-preview-slots` | Phone: the live preview draws the complication slots (heart rate, steps) where the watch's system draws them |
| `local/wear-loop-time` | Phone: the loop time sent to the watch is kept across loop runs, so the face does not show "LOOP --" while a run waits for the pump |
| `local/wear-design-import` | Phone: a button in the list of watch face designs imports zips from Google Drive (file picker) into AAPS/export, replacing the old ones |

`master` of this fork is a plain copy of upstream `master`.

## Tests

All changes have JVM / Robolectric tests in the `wear`, `plugins/sync` and `ui` modules. The designs are
built by tests (`CwfTechDesignTest`, `CwfGrandDayDesignTest`, ...) that render the real watch face.
These tests check, for example, that no text is cut off, that all values in the table line up, that
the rain does not make the table hard to read, and that the glucose hand points at the value.

## Updating to a new upstream version

The version check of AAPS is not changed, so the app still tells you when a new AAPS version is
out. The changes are kept small so they can be moved to a new version: mostly new files, small
hooks in existing files, and their own strings file. Our own files start with a
`PERSONAL PATCH` header; newer hooks in upstream files are marked with a `// PERSONAL PATCH`
comment.

Since the Dexcom ONE+ / G7 driver (the Dexcom ONE+ / G7 pull request), `local/personal` holds a merge of upstream `dev`.
A rebase onto a new `master` would have to move all those `dev` commits too, so **merge** instead:

1. Fetch upstream: `git fetch https://github.com/nightscout/AndroidAPS dev:refs/remotes/upstream/dev`
   (or the new release tag).
2. Merge it into `local/personal`: `git merge upstream/dev`. Solve conflicts where git stops.
   When pull request #5431 is merged upstream, its commits come in this way and nothing else is
   needed for it.
3. Run the `wear`, `plugins/sync`, `plugins/source` and `ui` tests, build, and push with a normal
   `git push` (a merge does not rewrite commits).
4. Change the base in "Base and branches" above, and the one "Version" sentence at the end of `.github/README.md`.

Before the driver, the branches were moved with
`git rebase --rebase-merges --onto <new master> f77abd9b82 local/personal`, and the `local/wear-*`
branches were pointed at their moved commits.
