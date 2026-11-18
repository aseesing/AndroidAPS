package app.aaps.wear.interaction.menus

import android.app.Activity

/**
 * Stand-in for the watch's menu. A tap on the watch face opens it on the watch; on the phone the
 * watch face code never gets a tap. See wear/cwfrender/build.gradle.kts.
 */
class MainMenuActivity : Activity()
