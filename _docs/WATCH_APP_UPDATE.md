# Watch app update from the phone

How the phone app can update the AAPS watch app over the Wear OS connection, so the watch does not
need adb (remote debugging) for every new version. The same transfer code also downloads the log of
the watch app to the phone. The last part, [Open points](#open-points), lists what is not done yet.

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
   writes `watch_app/watch_app.properties` with a label, the compressed size and the APK size. A
   relative path in the property starts at the root project directory. A variant whose application id
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
   phone sends a header (magic, version 4, label, size of the gzip body, size of the APK, SHA-256 of
   the body, and a Wi-Fi offer); the watch answers *Ready* and the phone sends the gzip body over the
   channel; the watch answers *Committed* or *Failed*. A watch app that is too old for this feature
   never answers, and the phone says so after 30 seconds. A watch app with an older header version
   answers *Failed* "Unknown update format"; the phone then says that the watch app must be
   installed once with adb. A watch app with a newer header version answers *Failed* "Update format
   older than the watch app"; the phone then asks for a newer phone app. A watch that is still busy
   with another update answers *Failed* "Busy", and the phone says so. When the channel breaks after
   the whole body was sent, the phone does not know whether the install started, and asks the user
   to look on the watch.
   A watchdog (`ChannelWatchdog`) closes the channel when nothing
   moves for too long, because reads and writes on a channel block and cannot be cancelled.

   **Wi-Fi.** The channel goes over Bluetooth, about 70 KB/s: 5 minutes for 21 MB. So when the
   phone has a Wi-Fi address (its Wi-Fi, or its hotspot), it starts a one-time TCP server
   (`SocketWifiBodyServer`) and puts its addresses, the port and a random token in the header. The
   watch answers *WifiStarted*, asks Android for a Wi-Fi network (Wear OS then turns on the Wi-Fi of
   the watch, if the user allows Wi-Fi and the watch knows a network), connects, sends the token
   and reads the body (`NetworkWifiBodyFetcher`). When any of that fails, the watch answers *Ready*
   and the body comes over the channel after all. The token keeps other devices on the network
   from fetching the APK; the SHA-256 from the channel keeps them from giving the watch another one.
   The server takes only connections that reached one of the offered addresses (not the mobile
   network or a VPN). It serves one connection at a time, and the whole request must arrive within
   2 seconds after the server took the connection; a slow or wrong request gets the socket closed.
   After 5 such connections the server closes itself. The watch then fails at once and the body
   comes over the channel.

4. **Watch.** `DataLayerListenerServiceWear.onChannelOpened` hands the channel to
   `WatchAppSelfUpdate`. `WatchAppReceiveTask` unpacks the APK while it arrives, over Wi-Fi or over
   the channel, straight into a `PackageInstaller` session that is limited to the app's own package
   (`setAppPackageName`) and to the APK size from the header. The unpack stops as soon as the APK
   gets larger than that size, so a broken body cannot fill the storage of the watch. gzip checks
   its CRC and the watch checks the SHA-256, so a damaged transfer is never installed. The watch
   commits the session first and only then answers *Committed*: *Committed* means that the
   installer has the APK, not that it is installed. After the commit the watch never abandons the
   session, also when it cannot send the answer because the channel is closed. Android refuses an
   APK that is not signed with the same key as the installed watch app. On Android 12 and newer the
   session asks for no user action (`USER_ACTION_NOT_REQUIRED`, allowed for an app that updates
   itself); Wear OS 3 (Android 11) does not have that call, so there the watch always asks. Where
   the watch still wants a confirmation,
   `WatchAppInstallStatusReceiver` opens it and posts a notification "Tap to confirm the update".
   The OnePlus Watch 2R asks for the confirmation.

## Watch log download

The log of the watch app could only be read with adb. The Wear screen now has a **Download watch
log** button, also in a phone app without the watch APK inside. It opens a page with a **Download**
button, the progress and the result.

- **Protocol** (`WatchLogProtocol` version 2 in `:shared:impl`, path `/aaps/watch_logs`): the phone
  sends a request with how many log files it wants, and, when it has a Wi-Fi address, a Wi-Fi offer
  (the same `SocketWifiBodyServer` and token as the update) and a new AES-256 key and 12 byte nonce
  from `SecureRandom`. The watch answers *Accepted* at once, so an old watch app is found out after
  30 seconds. A watch app with an older request version answers *Failed* "Unknown log request
  format", and the phone asks for a newer watch app. A watch that is still sending a log answers
  *Failed* "Busy".
- **Wi-Fi, encrypted.** The watch connects, zips its log files, encrypts the zip in one piece with
  AES-GCM (`WatchLogCrypto`, 128 bit tag), says the size of the encrypted body over the channel and
  writes it over Wi-Fi. The key only travels over the channel, so somebody on the Wi-Fi network, or
  somebody who takes the place of the phone there, gets only cipher text, and a body from anybody
  without the key is refused. The phone decrypts the whole body and uses nothing of it before the
  tag is checked; the tag is the integrity check of this path.
- **Channel.** Without Wi-Fi, or when Wi-Fi breaks, a zip with only the 10 newest log files comes
  over the channel, as it is (the channel is protected by Wear OS), with its size and SHA-256. When
  Wi-Fi breaks in the middle, the watch notices it after its own stall time (60 seconds); the phone
  waits that long, and the time to start the channel zip, before it gives up.
- **Size.** The zip holds the newest log files up to 8 MB together (`MAX_LOG_BYTES`); older files
  stay on the watch, and the page says so. The phone refuses a body over 9 MB (zip with headers,
  plus the tag) before it reads it. The whole zip is in memory once on each side.
- **Watch** (`WatchLogUpload`, `WatchLogSendTask`, `WatchLogZipper`): reads the log folder from
  logback (`EXT_FILES_DIR` in `logback.xml`: the public folder `/sdcard/AAPS/logs/<package>` on the
  watch, not a folder that only the app can read) and zips only `AndroidAPS*.log` and the older
  `AndroidAPS*.zip` parts in that folder, with their plain names. One download at a time.
- **Phone** (`WatchLogDownloader`, `WatchLogReceiver`): saves the zip in the Downloads folder of the
  phone as `AndroidAPS_WEAR_LOG_<date>_<time>.zip`, through `MediaStore`. No permission is needed
  for that. Other apps that can read the user's files can read it there.

## Setup

Everyone needs adb **once**, to get a watch app with this feature onto the watch:

| Situation | What is needed |
|---|---|
| Never had the AAPS watch app | Install the watch app once, with adb as before. |
| Has an older AAPS watch app, without this feature | Install the new watch app once with adb. The old app does not listen for updates. |
| Has a watch app with this feature | Nothing. Install the new phone app, tap **Send to watch**, confirm on the watch. |

Right after that one adb install, this was also run in all tests so far:

```
adb shell appops set info.nightscout.androidaps REQUEST_INSTALL_PACKAGES allow
```

This allows "install unknown apps" for AAPS on the watch. It was added as a safety measure, because
some watches have no settings screen for it, and it is not yet known whether the step is needed at
all (see the open points). The setting stays across updates; only a fresh install after an uninstall
needs it again.

## Open points

1. **Make the `appops` command unnecessary.** Without the permission, the installer on the watch
   should show "not allowed to install from this source" with a **Settings** button. Whether every
   watch has that screen is not known.
   - Watch: before it answers *Ready*, check `packageManager.canRequestPackageInstalls()`. When it
     is false, open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the app's package (or post a
     notification that opens it), and answer *Failed* with a fixed reason.
   - Phone: show that reason as its own text, for example "Allow AAPS to install updates on the
     watch, then send again", instead of a general failure (a new `WatchAppUpdateState.Reason`).
   - Test on a real watch with `adb shell appops set info.nightscout.androidaps REQUEST_INSTALL_PACKAGES default`,
     on more than one watch model (OnePlus, Galaxy, Pixel).
2. **The first install still needs adb.** AAPS on the phone could do this step itself, by talking
   the adb protocol to the watch over Wi-Fi (pairing code, TLS connection, install), like the
   "Wear Installer" apps do. It removes the computer, not the developer options on the watch. It
   needs an adb client library on Android; that is a lot of code for a step that is needed once per
   watch.
3. **More watches.** Test the Wi-Fi path on more watches: whether Wear OS turns on the Wi-Fi for the
   request on every model, and how fast it is. Also with the phone as a hotspot for the watch.
4. **Release builds.** Decide whether release builds of AAPS should always carry the watch app (a
   bigger phone APK, about +21 MB) or whether it stays an option for people who build themselves.
5. **User documentation** on the AAPS docs site: how to build with `-PembedWearApk`, the one-time
   setup, and what the watch shows.
6. **The APK goes over Wi-Fi as it is.** It is no secret; the token keeps it from other devices
   and the SHA-256 from the channel keeps them from giving the watch another one.
