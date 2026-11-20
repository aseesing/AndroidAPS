# Watch Face Format face zips

Watches that started with Wear OS 5 or later, and all watches on Wear OS 6, do not run the
code-based AAPS watch faces. They run Watch Face Format (WFF) faces: an APK with pictures and XML,
and no code. A tool outside AAPS turns a Custom watchface design into such a face. This page says
what that tool must deliver, and what AAPS does with it.

## For the user

On the phone, in the Wear plugin, **Load from file** opens the system file picker. Pick a zip from
Downloads, Google Drive or anywhere else. The phone looks inside:

- an old Custom watchface zip goes to the watch as before;
- a WFF face zip goes to the watch as a face, in the version the watch can run.

What then happens on the watch:

| Watch | Install | What the user does |
|---|---|---|
| Wear OS 6 (API 36) and newer | Watch Face Push, into the one face slot of the AAPS app | Nothing. The face replaces the face in the slot and becomes the active face where the system allows it. On the phone the face is a third choice beside the two built-in faces. |
| Wear OS 5 and 5.1 (API 34, 35) | As an ordinary app | Tap the notification on the watch and confirm the install. Then pick the face in the watch's face picker. The watch may first ask to allow AAPS to install apps. |
| Older | none | The phone says which Wear OS version the face needs. |

The phone shows the name of the face once the watch reports it installed.

## The zip

| Entry | What |
|---|---|
| `face.json` | `{"format": 1, "name": "<design name>"}` |
| `data.zip` | A design zip in the Custom watchface format (`CustomWatchface.json` with `metadata.name`, a `CustomWatchface` picture, the resources). It holds only the parts the watch draws into the data picture (see below), and the design's arrow pictures (`ArrowNone`, `ArrowDoubleUp`, `ArrowSingleUp`, `Arrow45Up`, `ArrowFlat`, `Arrow45Down`, `ArrowSingleDown`, `ArrowDoubleDown`) when the design has them. Always present. |
| `wff4/<appId>.apk` | The face in WFF version 4, for Wear OS 6. |
| `wff4/<appId>.token` | The Watch Face Push validation token of exactly that APK, as text. A version 4 face without its token is not used. |
| `wff3/<appId>.apk` | Optional: the face in WFF version 3, for Wear OS 5.1. |
| `wff2/<appId>.apk` | The face in WFF version 2, for Wear OS 5.0. |

- `<appId>` is the AAPS app the face is for: `info.nightscout.androidaps`,
  `info.nightscout.aapsclient`, `info.nightscout.aapsclient2`, `info.nightscout.aapsclient3` or
  `info.nightscout.aapspumpcontrol`. A zip can hold faces for several apps. The phone takes the one
  for its own app, and says so when there is none.
- Every face package is named `<appId>.watchfacepush.design`. Watch Face Push requires the
  `<appId>.watchfacepush.` start; the wear app lists `.design` as a safe face of its complications.
- The phone takes the highest version the watch can run, and sends the watch only `face.json`,
  `data.zip`, that APK as `face.apk` and its token as `face.token`.
- The phone knows the watch's Android API level from the watch's `WatchFacePushStatus`, or from its
  `ActionPong`. When it has neither, it pings the watch and waits about 8 seconds before it says
  that the watch has not told its version.
- The phone says "sent" only after the whole zip went out on the channel. When the channel fails,
  it says that the watchface could not be sent.
- On Wear OS 5 a face can only replace an installed one when both are signed with the same key.

## Complications the face reads

A face names them as `<appId>/<class>` in its `DefaultProviderPolicy`.

| Class | Type | Data |
|---|---|---|
| `app.aaps.wear.complications.wff.WffGlucoseComplication` | RANGED_VALUE | value: the glucose in mg/dl, kept in 39 to 400; text: the glucose in the user's unit; title: the delta; monochromatic image: the trend arrow, from the arrows in `data.zip`, else the app's own |
| `app.aaps.wear.complications.wff.WffGlucoseAgeComplication` | RANGED_VALUE | the minutes since the reading, 0 to 60, as a dynamic value that keeps counting by itself; text: a count-up |
| `app.aaps.wear.complications.wff.WffDataComplication` | SMALL_IMAGE (PHOTO) | the data picture: the design in `data.zip`, drawn by the watch on a see-through square as wide as the screen. Made again when data arrives and every 5 minutes. |

The user slots of a face may default to any other AAPS complication, for example
`app.aaps.wear.complications.CobIconComplication`.

## The data picture

A WFF face can draw the clock, the date, hands and the values of complications by itself. Everything
else that shows AAPS data (the graph, the status texts, gauge hands) is drawn by the AAPS watch app
into one picture, with the same Custom watchface engine that draws the code-based face. The tool
decides which parts these are and puts them in `data.zip`. The data picture is still between two
updates: animations of a design belong in the face itself, as frames.

`data.zip` is loaded without the right to change watch settings, so a face can never change them.

## Code

- Phone: `plugins/sync/.../wear/WffFaceZip.kt` (check the zip and cut it down), `WearViewModel.loadWatchfaceFile`,
  `DataLayerListenerServiceMobile` (sends it on a Data Layer channel, path `path_wff_face`).
- Watch: `DataLayerListenerServiceWear.onChannelOpened`, `watchfaces/wff/WffFace.kt` (keeps the zip),
  `watchfaces/wff/WffFaceInstaller.kt` (Watch Face Push or the package installer), `WatchFacePushHelper`
  (the `design` face), `complications/wff/`.
