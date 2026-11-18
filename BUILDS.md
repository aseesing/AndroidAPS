# Builds

This file lists every APK build of this fork, newest first. Each build has a git tag `build/...`
on the commit it was built from, so you can always find the code of an APK. This branch holds only
this file; it never touches a code branch.

| Date and time | Tag | Branch | Commit | Files | Uploaded to | What is new |
|---|---|---|---|---|---|---|
| 2026-11-18 22:07 | [build/4.0.1-2026-11-18-2207-local-upstream-tickets-e4a9b260ef](https://github.com/aseesing/AndroidAPS/releases/tag/build/4.0.1-2026-11-18-2207-local-upstream-tickets-e4a9b260ef) | local/upstream-tickets (test branch) | [e4a9b260ef](https://github.com/aseesing/AndroidAPS/commit/e4a9b260efca4a91f016bb9aafe03378795506e8) | aaps-4.0.1-2026-11-18-2207-local-upstream-tickets-e4a9b260ef.apk; aaps-wear-4.0.1-2026-11-18-2205-local-upstream-tickets-e4a9b260ef.apk (new, inside the phone APK, not on Drive) | Phone APK uploaded to AAPS/latest (Google Drive). The old phone APK went to AAPS/history/4.0.1 personal 2026-11-18 ea3f656a24. Design zips in latest were already the same as aaps-watchfaces 57f50a5, so none were uploaded. | The nine features of upstream tickets 5604 to 5611 and 5613 in their reviewed versions, on top of local/personal at ea3f656a24 with the original animation code. New: Day and night screen, location only when a design needs it and the user allows it, sensor countdown from the Dexcom driver's session length, resend timing with hard limits. One update format for the watch app: install the watch app once with adb. |
