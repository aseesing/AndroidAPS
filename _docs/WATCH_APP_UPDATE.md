# Watch app update from the phone

How the phone app can update the AAPS watch app over the Wear OS connection, so the watch does not
need adb (remote debugging) for every new version. The first part describes what the code does
today. The last part, [Before the upstream pull request](#before-the-upstream-pull-request), is a
list of open work.

## Why

Installing the watch app needs adb: developer options and wireless debugging on the watch, pairing,
a computer or a "Wear Installer" app on the phone. That is the most painful step of an AAPS update,
and it comes back with every new version. With this feature it is needed **once**.

## How it works

1. **Build.** The phone app carries the watch APK only when it is built with the property
   `-PembedWearApk=<signed watch APK>`:

   ```
   ./gradlew :wear:assembleFullRelease          # then sign it with the key of the installed watch app
   ./gradlew :app:assembleFullRelease -PembedWearApk=/path/to/wear-full-release-signed.apk
   ```

   `EmbedWatchAppTask` (in `buildSrc`) reads the package name and version with `aapt2`, stores the
   APK gzip compressed in the assets (`watch_app/wear.apk.gzip`, about 21 MB instead of 58 MB) and
   writes `watch_app/watch_app.properties` with a label and the size. A variant whose application id
   is not the package of the watch APK gets nothing. Without the property, nothing changes and the
   phone app shows no update button. The asset is not named `.gz`: aapt unpacks such an asset and
   drops the extension.

2. **Phone.** The Wear screen shows a small **Update watch app** button in the connection card. It
   opens a page with the included version, a hint (several minutes over Bluetooth, much faster
   with Wi-Fi, keep the watch close), a **Send to watch** button, a progress bar with the time left
   and the way the body goes (Wi-Fi or Bluetooth), and the result.
   `WatchAppUpdater` (one app-wide instance, so the transfer goes on when the page is left) opens a
   Wear OS `ChannelClient` channel to the watch and `WatchAppSender` runs the protocol.

3. **Protocol** (`WatchAppUpdateProtocol` in `:shared:impl`, path `/aaps/watch_app_update`):
   phone sends a header (magic, version, label, size, SHA-256 of the body, and a Wi-Fi offer); the
   watch answers *Ready* and the phone sends the gzip body over the channel; the watch answers
   *Committed* or *Failed*. A watch app that is too old for this feature never answers, and the
   phone says so after 30 seconds. A watchdog closes the channel when nothing moves for too long,
   because reads and writes on a channel block and cannot be cancelled.

   **Wi-Fi.** The channel goes over Bluetooth, about 70 KB/s: 5 minutes for 21 MB. So when the
   phone has a Wi-Fi address (its Wi-Fi, or its hotspot), it starts a one-time TCP server
   (`SocketWifiBodyServer`) and puts its addresses, the port and a random token in the header. The
   watch answers *WifiStarted*, asks Android for a Wi-Fi network (Wear OS then turns on the Wi-Fi of
   the watch, if the user allows Wi-Fi and the watch knows a network), connects, sends the token
   and reads the body (`NetworkWifiBodyFetcher`). When any of that fails, the watch answers *Ready*
   and the body comes over the channel after all. The token keeps other devices on the network
   from fetching the APK; the SHA-256 from the channel keeps them from giving the watch another one.

   Version 1 of the protocol (the first watch app with this feature) had no hash and no Wi-Fi. Such
   a watch app answers a version 2 header with *Failed* "Unknown update format"; the phone then waits
   12 seconds (the old watch app is busy for 10 seconds after an answer) and sends again in version 1.

4. **Watch.** `DataLayerListenerServiceWear.onChannelOpened` hands the channel to
   `WatchAppSelfUpdate`. `WatchAppReceiveTask` unpacks the APK while it arrives, over Wi-Fi or over
   the channel, straight into a
   `PackageInstaller` session that is limited to the app's own package
   (`setAppPackageName`). gzip checks its CRC and the watch checks the SHA-256, so a damaged
   transfer is never installed. Android
   refuses an APK that is not signed with the same key as the installed watch app. The session asks
   for no user action (`USER_ACTION_NOT_REQUIRED`, allowed for an app that updates itself). Where
   the watch still wants a confirmation, `WatchAppInstallStatusReceiver` opens it and posts a
   notification "Tap to confirm the update". The OnePlus Watch 2R asks for the confirmation.

## Setup, today

Everyone needs adb **once**, to get a watch app with this feature onto the watch:

| Situation | What is needed |
|---|---|
| Never had the AAPS watch app | Install the watch app once, with adb as before. |
| Has an older AAPS watch app, without this feature | Install the new watch app once with adb. The old app does not listen for updates. |
| Has a watch app with this feature | Nothing. Install the new phone app, tap **Send to watch**, confirm on the watch. |

Right after that one adb install we also run:

```
adb shell appops set info.nightscout.androidaps REQUEST_INSTALL_PACKAGES allow
```

This allows "install unknown apps" for AAPS on the watch. It was added as a safety measure, because
some watches have no settings screen for it, and it is not yet known whether the step is needed at
all (see below). The setting stays across updates; only a fresh install after an uninstall needs it
again.

## Before the upstream pull request

Open work, to do before this goes to `nightscout/AndroidAPS` in its own pull request. Not done yet
on purpose.

### 1. Make the `appops` command unnecessary

Without the permission, Android's installer on the watch should show "not allowed to install from
this source" with a **Settings** button. Whether every watch has that screen is not known.

- **Watch:** before it answers *Ready*, check `packageManager.canRequestPackageInstalls()`. When it
  is false, open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the app's package (or post a
  notification that opens it), and answer *Failed* with a fixed reason.
- **Phone:** show that reason as its own text, for example "Allow AAPS to install updates on the
  watch, then send again", instead of a general failure (new `WatchAppUpdateState.Reason`).
- **Test** on a real watch: `adb shell appops set info.nightscout.androidaps REQUEST_INSTALL_PACKAGES default`,
  then send an update and see what the watch shows. Try more than one watch model (OnePlus, Galaxy,
  Pixel).
- If the settings screen exists, take the `appops` line out of the setup text. If it does not, keep
  it there as the fallback for those watches.

### 2. Idea: the first install from the AAPS phone app itself

The one-time install still needs adb. Today that means a computer, or a separate app such as
"Wear Installer", which was a pain to use. AAPS on the phone could do this step itself: talk the adb
protocol to the watch over Wi-Fi, like those apps do.

- The user turns on developer options and wireless debugging on the watch, and enters the pairing
  code in AAPS. AAPS pairs (adb TLS pairing), connects, and installs the embedded watch APK
  (`adb install -r`). It could also run the `appops` command from point 1 at the same time.
- This removes the computer and the separate app. It does not remove developer options on the watch:
  Android allows nothing else for a first install of an app that is not in the Play Store.
- Needs an adb client library on Android (pairing, TLS connection, install streaming). Check the
  license and the maintenance of what exists before choosing one. It is a lot of code for a step
  that is needed once per watch, so weigh it against better install instructions.

### 3. Other points for the pull request

- Write the user documentation (AAPS docs site): how to build with `-PembedWearApk`, the one-time
  setup, and what the watch shows.
- Decide whether release builds of AAPS should always carry the watch app (a bigger phone APK, about
  +21 MB) or whether it stays an option for people who build themselves.
- Test the Wi-Fi path on more watches: whether Wear OS turns on the Wi-Fi for the request on
  every model, and how fast it is. Also with the phone as a hotspot for the watch.
- Run the full CI command from `CLAUDE.md` before pushing.
- Remove this "Before the upstream pull request" part once the points above are done or dropped.
