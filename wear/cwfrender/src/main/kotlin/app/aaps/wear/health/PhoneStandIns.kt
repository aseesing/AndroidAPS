// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.health

// Stand-ins for watch-only classes. The shared renderer names them as the default sources of the
// complication slots, but never runs them on the phone. See wear/cwfrender/build.gradle.kts.

class HeartRateComplication

class StepsComplication
