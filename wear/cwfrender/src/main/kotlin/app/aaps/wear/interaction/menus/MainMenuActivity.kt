// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.interaction.menus

import android.app.Activity

/**
 * Stand-in for the watch's menu. A tap on the watch face opens it on the watch; the phone preview
 * never does. See wear/cwfrender/build.gradle.kts.
 */
class MainMenuActivity : Activity()
