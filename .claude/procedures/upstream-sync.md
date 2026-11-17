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
| `local/wear-log-graph` | The glucose graph of every watch face on a log time scale: a whole day, the last minutes in detail, lines and labels for 1, 3, 6 and 12 hours ago; a watch setting, also in AAPS; the phone then sends a day of history, thinned further back |
| `local/wear-d2` | The digital design *D2* (glucose and trend scales, three complications, weather, sunrise and sunset, sensor and pump time left, a BG graph); a main and a second colour, chosen on the watch or in AAPS; the phone offers every setting of the watch face menu by itself; the wake-up animation can swing hands, shake hands, draw a view in and slide a light over the face; a picture in the seconds that turns each second, drawn smaller with 12 hours; sensor and pump time left up to 4 days on a log scale; raised steel strips with pins and screws between the panels; the wake-up animation can also slide a view (the month mark); a cut-down font (D2 Text) |
| `local/watch-app-update` | The phone updates the watch app over the Wear OS connection, no adb on the watch: build the phone with `-PembedWearApk=<signed watch APK>`. Made upstream-ready on `origin/master`, without our other changes, for a pull request |
| `local/wear-graph-top-labels` | The time labels of the log graph go on its top edge when the round screen cuts its bottom edge; the graph of the Cyberpunk and Wilds designs sits higher |
| `local/wear-glucose-line` | A design can draw the glucose of the graph as a line, with its own width and colour (`GlucoseLine`); the Cyberpunk and Wilds designs do |
| `local/wear-anim-layers` | One generic wake-up animation engine: a design's `animation.layers` are pictures, shapes or views whose properties are safe math formulas (copies, strips, points, masks, still layers after the wake); the old effects (rain, glitch, pulse, blink, sweep, slide, wobble, trace, glint) are short forms of it. All designs and their generators moved out to [aaps-watchfaces](https://github.com/aseesing/aaps-watchfaces) |
| `local/wear-design-manage` | Phone: a delete button for each design in the list of watch face designs; a loading indicator while a new face goes to the watch; the watch face settings button on the night face card too |
| `local/wear-start-after-unlock` | The watch app waits for the unlock of the watch before it starts: Android may start it earlier after a boot, and it then crashed again and again until the unlock |

`master` of this fork is a plain copy of upstream `master`.

## Tests

All changes have JVM / Robolectric tests in the `wear`, `plugins/sync` and `ui` modules. The designs
live in their own repository, `aaps-watchfaces`, beside AAPS. It includes AAPS as a composite build,
and its tests (`CwfTechDesignTest`, `CwfGrandDayDesignTest`, ...) build the design zips with the
real watch face code (`:wear:cwfrender`). These tests check, for example, that no text is cut off, that all values in the table line up, that
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
