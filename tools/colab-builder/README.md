# AAPS Builder for Google Colab

Build the AAPS app (AndroidAPS) yourself, from your phone, without installing anything and
without a GitHub account. You only need a Google account, which almost every Android phone
already has.

**[Open AAPS Builder in Google Colab](https://colab.research.google.com/github/aseesing/AndroidAPS/blob/ccr-20e9b6dd-wkln63/tools/colab-builder/AAPS_Builder.ipynb)**

> This is a community tool. The AAPS developers did not make it and did not check it.
> AAPS is not an approved medical device. You build and use it at your own risk.

## How it works for the user

1. Open the link above in Chrome on your phone, and sign in with your Google account.
2. Press the ▶ button. If Colab warns that the notebook was not made by Google, choose
   **Run anyway**.
3. Read the first page. Tick the box to accept the risks and the Android SDK license, and press
   **Continue**.
4. Allow access to your Google Drive. Your key and your app are saved there.
5. Press **Start building**. The defaults are fine: the newest version and the full AAPS app.
   Under **More options** you can choose another version, a client app, or the watch app.
6. Wait about 30 to 60 minutes. Keep the page open and the screen on.
7. Install the APK from Google Drive, from the folder `AAPS/<version>`.

An update is the same, but shorter. The builder finds your key in Google Drive and suggests the
update.

## What is saved in your Google Drive

| Path | What |
|---|---|
| `AAPS/KeyStore/aaps-builder-keystore.jks` | Your signing key. You need it for every update. |
| `AAPS/KeyStore/aaps-builder.json` | Key alias, the password (if you chose to save it), the last build, and when you accepted the risks. |
| `AAPS/KeyStore/*-old-<date>.*` | An old key, kept when you chose to start again with a new key. Nothing is ever deleted. |
| `AAPS/<version>/aaps-<version>.apk` | The app. The same folder layout as the AAPS browser build. |
| `AAPS/Builder/logs/build-<date>.log` | The full build log. |

## Choices

- **Password.** "No password" is the default. `keytool` does not accept an empty password, so
  the key then gets the well known password `AndroidAPS`. In that case the key is protected by
  your Google account only. You can also choose your own password, and choose whether it is
  saved in Google Drive.
- **Key from the browser build.** If you built AAPS before with the GitHub browser build, paste
  your `KEYSTORE_SET` secret. Then the new app can update the app on your phone.
- **Start again with a new key.** Possible, but Android cannot update the app on your phone
  with a different key. You must export your settings and uninstall AAPS first. The builder asks
  you to confirm this.

## Why Colab and not a ready app

AAPS is software that controls an insulin pump. It is a medical device, and it is not approved.
The AAPS project explains that the way to use it within the rules is to build it yourself, for
your own use, and never to give the app to others. This builder runs entirely in your own Google
account, from the official source code, with your own key. Nobody else builds the app for you.

## What it does, step by step

1. Shows the legal page, in the language of the browser (English and Dutch for now).
2. Mounts your Google Drive and looks for an earlier key in `AAPS/KeyStore`.
3. Installs Java 21 (from Adoptium, or from apt as a fallback).
4. Shows the build settings and checks the key and the password.
5. Installs the Android SDK command line tools and accepts the SDK licenses, which you
   accepted on the first page.
6. Downloads the chosen release tag from `github.com/nightscout/AndroidAPS`.
7. Builds the unsigned app with Gradle. The memory is set from the machine, so it fits on the
   free Colab machine.
8. Signs the app with `apksigner` (v2 and v3), like the AAPS command line guide does. Passwords go
   to `keytool` and `apksigner` through environment variables, so they never show in the log.
9. Checks that the app is signed with your key, and only then saves it.

## Files

| File | What |
|---|---|
| `AAPS_Builder.ipynb` | The notebook. One cell, which downloads `aaps_builder.py` and starts it. |
| `aaps_builder.py` | All the logic. It also runs outside Colab, for tests. |
| `ui.html` | The pages (HTML and JavaScript), shown in the Colab output. |
| `i18n/<lang>.json` | All texts. Add a language by adding a file and its code to `LANGUAGES`. |

## Testing outside Colab

The same code runs on Linux with a scripted UI. A local folder stands in for Google Drive:

```
python3 aaps_builder.py --work /tmp/aaps-work --drive /tmp/aaps-drive
python3 aaps_builder.py --work /tmp/aaps-work --drive /tmp/aaps-drive    # second run: update
```

`AAPS_BUILDER_RAM_GB` and `AAPS_BUILDER_CPUS` make the build use the memory and the number of
processors of a Colab machine. See `--help` for the other options.

## Known limits

- The free Colab machine is not guaranteed. Google can change the limits or the terms at any
  time.
- If the phone goes to sleep for a long time, Colab may stop the session. Keep the screen on.
- Only AAPS 3.3 and newer are offered, because older versions need an older Java.
