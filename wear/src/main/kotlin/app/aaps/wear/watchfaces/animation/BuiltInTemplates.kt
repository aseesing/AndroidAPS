// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import org.json.JSONObject

/**
 * The layer templates every design can `use` (see [Templates]), as one json document. Kept as text in
 * Kotlin, not as a resource, so the phone preview (`wear/cwfrender`, which copies the watch's Kotlin
 * files) has them too.
 *
 * - `rain`: falling streaks, behind the data
 * - `glitch`: short bursts of thin coloured bands that jump sideways, behind the data
 * - `pulse`: a picture that beats at the heart rate (`bpm`), with a glow
 * - `blink`: a view that blinks while a value is low
 * - `sweep`: a hand that swings to the end of its scale and back to its value
 * - `slide`: a view that slides to the end of its scale and back to its value
 * - `wobble`: a hand that shakes round its value
 * - `trace`: a view that draws itself in from the left, with a bright line at the edge
 * - `glint`: a light that slides once over the shiny parts (rings and boxes)
 * - `moon`: the moon as it is in the sky now, made from the photo `@moon` and a few shapes
 *
 * The times are in ms of the wake (`ms`), the sizes in design pixels, the alphas 0 to 255.
 *
 * The formulas give exactly the numbers of the effects these templates replace (`f32` where those
 * worked in 32-bit floats), so a design looks the same pixel for pixel.
 *
 * The `moon` is built in four steps. First what the dark part shows: a black disc and the photo, both
 * faint, so the sky shows through (`dark` is how bright the dark part is, `cover` how much it covers).
 * Then a group draws the lit part on a sheet of its own: a half disc on the lit side with a soft edge
 * (a circle with a gradient, turned to `v_moon_limb`), widened by an ellipse when more than half is lit
 * or cut by one (`erase`) when less, and last the photo `inside` that shape. The photo is asked for at
 * twice its size (`maxScale`), so it stays sharp when it is turned.
 */
object BuiltInTemplates {

    /** The templates by name. One shared object: [Templates] only reads it. */
    val all: JSONObject by lazy { JSONObject(TEXT) }

    private val TEXT = $$"""
{
  "rain": {
    "params": {
      "count": {"default": 90, "min": 0, "max": 300, "int": true},
      "seed": {"default": 7, "int": true},
      "speed": {"default": 520, "min": 10, "max": 4000},
      "angle": {"default": 12, "min": -45, "max": 45},
      "length": {"default": 22, "min": 1, "max": 200},
      "lengthVariation": {"default": 0.4, "min": 0, "max": 0.9},
      "thickness": {"default": 1.6, "min": 0.5, "max": 10},
      "color": "#A6C8FF",
      "alpha": {"default": 150, "min": 0, "max": 255, "int": true},
      "region": {"left": 0, "top": 0, "width": 400, "height": 400}
    },
    "layers": [{
      "shape": "line", "colors": ["$color"], "slot": "back", "count": "$count", "seed": "$seed",
      "if": "$count > 0",
      "let": {
        "sinA": "f32(sin(f32($angle) * (pi / 180)))",
        "cosA": "f32(cos(f32($angle) * (pi / 180)))",
        "tanA": "f32(tan(f32($angle) * (pi / 180)))",
        "longest": "f32($length * f32(1 + $lengthVariation))",
        "span": "f32(f32($region.height + f32(longest * cosA)) + 1)",
        "pad": "f32(longest * abs(sinA))",
        "fromX": "f32(f32($region.left - max(0, f32($region.height * tanA))) - pad)",
        "toX": "f32(f32(f32($region.left + $region.width) + max(0, f32(-$region.height * tanA))) + pad)",
        "spread": "f32(toX - fromX)",
        "fallen": "mod(r2 * span + $speed * (0.8 + 0.4 * r3) * ms / 1000, span)",
        "streak": "$length * (1 + $lengthVariation * (2 * r4 - 1))"
      },
      "x": "fromX + r1 * spread + fallen * tanA - (streak) * sinA",
      "y": "$region.top + fallen - (streak) * cosA",
      "length": "streak",
      "angle": "$angle",
      "thickness": "$thickness",
      "alpha": "env * f32($alpha / 255) * (0.45 + 0.55 * r5)",
      "clip": {"rect": "$region", "round": true}
    }]
  },
  "glitch": {
    "params": {
      "periodMs": {"default": 700, "min": 100, "max": 10000, "int": true},
      "chance": {"default": 0.6, "min": 0, "max": 1},
      "burstMs": {"default": 160, "min": 16, "max": "$periodMs", "int": true},
      "bands": {"default": 3, "min": 0, "max": 12, "int": true},
      "seed": {"default": 5, "int": true},
      "minHeight": {"default": 2, "min": 0.5, "max": 40},
      "maxHeight": {"default": 8, "min": "$minHeight", "max": 40},
      "minWidth": {"default": 80, "min": 1, "max": 400},
      "maxWidth": {"default": 300, "min": "$minWidth", "max": 400},
      "jump": {"default": 8, "min": 0, "max": 40},
      "color1": "#FF003C",
      "color2": "#00C8D8",
      "alpha": {"default": 200, "min": 0, "max": 255, "int": true},
      "region": {"left": 0, "top": 0, "width": 400, "height": 400}
    },
    "layers": [{
      "shape": "rect", "colors": ["$color1", "$color2"], "slot": "back", "count": "$bands", "seed": "$seed",
      "if": "$bands > 0 && $chance > 0",
      "let": {
        "period": "floor(ms / $periodMs)",
        "start": "floor(hash($seed, period, 1) * ($periodMs - $burstMs))",
        "inPeriod": "mod(ms, $periodMs)",
        "jumps": "floor((inPeriod - start) / 40)",
        "h": "($minHeight + hash($seed, period, 2 + i * 4) * f32(f32($maxHeight) - f32($minHeight)))",
        "w": "($minWidth + hash($seed, period, 3 + i * 4) * f32(f32($maxWidth) - f32($minWidth)))"
      },
      "x": "$region.left - w / 2 + hash($seed, period, 5 + i * 4) * $region.width + (mod(jumps + i, 3) - 1) * $jump",
      "y": "$region.top + hash($seed, period, 4 + i * 4) * max(0, $region.height - h)",
      "width": "w",
      "height": "h",
      "colorIndex": "mod(i, 2)",
      "alpha": "if(hash($seed, period, 0) < $chance && inPeriod >= start && inPeriod < start + $burstMs, f32($alpha / 255) * env, 0)",
      "clip": {"rect": "$region", "round": true}
    }]
  },
  "pulse": {
    "params": {
      "image": "",
      "left": 175, "top": 40, "width": 50, "height": 50,
      "amplitude": {"default": 0.12, "min": 0, "max": 0.5},
      "secondBeat": {"default": 0.35, "min": 0, "max": 1},
      "glowColor": "#FF3050",
      "glowAlpha": {"default": 160, "min": 0, "max": 255, "int": true},
      "glowRadius": {"default": 10, "min": 0, "max": 40}
    },
    "layers": [{
      "image": "$image", "rect": {"left": "$left", "top": "$top", "width": "$width", "height": "$height"}, "slot": "front",
      "scaleX": "1 + $amplitude * pulse(ms, bpm, $secondBeat) * env",
      "scaleY": "1 + $amplitude * pulse(ms, bpm, $secondBeat) * env",
      "glowAlpha": "f32($glowAlpha / 255) * pulse(ms, bpm, $secondBeat) * env",
      "glow": {"color": "$glowColor", "radius": "$glowRadius"},
      "maxScale": "f32(1 + f32($amplitude))",
      "clip": {"round": false}
    }]
  },
  "blink": {
    "params": {
      "view": "",
      "valueKey": "",
      "atOrBelow": 0,
      "periodMs": {"default": 1000, "min": 200, "max": 5000, "int": true}
    },
    "layers": [{
      "view": "$view",
      "alpha": "if(v_$valueKey <= $atOrBelow && mod(ms, $periodMs) >= floor($periodMs / 2), 0, 1)"
    }]
  },
  "sweep": {
    "params": {
      "view": "",
      "emptyAngle": 0,
      "fullAngle": 0,
      "delayMs": {"default": 0, "min": 0, "max": 5000, "int": true},
      "upMs": {"default": 600, "min": 50, "max": 5000, "int": true},
      "settleMs": {"default": 900, "min": 50, "max": 5000, "int": true}
    },
    "layers": [{
      "view": "$view",
      "let": {"w": "ms - $delayMs"},
      "rotation": "if(w <= 0, $emptyAngle, if(w < $upMs, $emptyAngle + f32(f32($fullAngle) - f32($emptyAngle)) * easeInOut(w / $upMs), if(w < $upMs + $settleMs, $fullAngle + (rot0 - $fullAngle) * settle((w - $upMs) / $settleMs), rot0))) - rot0"
    }]
  },
  "slide": {
    "params": {
      "view": "",
      "fullDx": 0,
      "delayMs": {"default": 0, "min": 0, "max": 5000, "int": true},
      "upMs": {"default": 600, "min": 50, "max": 5000, "int": true},
      "settleMs": {"default": 900, "min": 50, "max": 5000, "int": true}
    },
    "layers": [{
      "view": "$view",
      "let": {"w": "ms - $delayMs"},
      "x": "if(w <= 0, 0, if(w < $upMs, 0 + ($fullDx - 0) * easeInOut(w / $upMs), if(w < $upMs + $settleMs, $fullDx + (dx0 - $fullDx) * settle((w - $upMs) / $settleMs), dx0))) - dx0"
    }]
  },
  "wobble": {
    "params": {
      "view": "",
      "delayMs": {"default": 0, "min": 0, "max": 5000, "int": true},
      "amplitude": {"default": 16, "min": -90, "max": 90},
      "hz": {"default": 2.5, "min": 0.2, "max": 10},
      "decayMs": {"default": 350, "min": 50, "max": 5000, "int": true}
    },
    "layers": [{
      "view": "$view",
      "let": {"w": "ms - $delayMs"},
      "rotation": "if(w <= 0 || w > $decayMs * 5, 0, $amplitude * exp(-w / $decayMs) * sin(2 * pi * $hz * w / 1000))"
    }]
  },
  "trace": {
    "params": {
      "view": "chart",
      "rect": {"left": 0, "top": 0, "width": 400, "height": 400},
      "delayMs": {"default": 0, "min": 0, "max": 5000, "int": true},
      "durationMs": {"default": 1000, "min": 50, "max": 5000, "int": true},
      "color": "#FFFFFF",
      "alpha": {"default": 140, "min": 0, "max": 255, "int": true},
      "width": {"default": 8, "min": 0, "max": 40}
    },
    "layers": [{
      "view": "$view",
      "let": {"w": "ms - $delayMs"},
      "clipRight": "$rect.left + $rect.width * clamp(w / $durationMs, 0, 1)",
      "alpha": "if(w > 0, 1, 0)"
    }, {
      "shape": "rect", "colors": ["$color"], "slot": "views",
      "gradient": {"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]},
      "if": "$width > 0 && $alpha > 0",
      "let": {
        "w": "ms - $delayMs",
        "edge": "$rect.left + $rect.width * clamp(w / $durationMs, 0, 1)"
      },
      "x": "edge - $width / 2",
      "y": "$rect.top",
      "width": "$width",
      "height": "$rect.height",
      "alpha": "if(w > 0 && w < $durationMs, f32($alpha / 255) * min(1, (1 - w / $durationMs) * 8), 0)",
      "clip": {"round": false}
    }]
  },
  "glint": {
    "params": {
      "rings": [],
      "boxes": [],
      "cornerRadius": {"default": 0, "min": 0, "max": 200},
      "delayMs": {"default": 0, "min": 0, "max": 5000, "int": true},
      "durationMs": {"default": 900, "min": 50, "max": 5000, "int": true},
      "angle": 30,
      "width": {"default": 60, "min": 1, "max": 400},
      "color": "#FFFFFF",
      "alpha": {"default": 90, "min": 0, "max": 255, "int": true}
    },
    "layers": [{
      "shape": "rect", "colors": ["$color"], "slot": "front",
      "gradient": {"type": "linear", "stops": [[0, 0], [0.5, 1], [1, 0]]},
      "if": "$alpha > 0",
      "let": {
        "diagonal": "f32(400 * f32(sqrt(2)))",
        "w": "ms - $delayMs",
        "middle": "f32(f32(200 - f32(diagonal / 2)) - f32(f32($width) / 2)) + f32(diagonal + f32(2 * f32(f32($width) / 2))) * easeInOut(w / $durationMs)"
      },
      "pivotX": 200, "pivotY": 200,
      "rotation": "$angle",
      "x": "middle - $width / 2",
      "y": "f32(200 - diagonal)",
      "width": "$width",
      "height": "f32(2 * diagonal)",
      "alpha": "if(w >= 0 && w <= $durationMs, f32($alpha / 255), 0)",
      "clip": {"round": false, "rings": "$rings", "boxes": "$boxes", "cornerRadius": "$cornerRadius"}
    }]
  },
  "moon": {
    "params": {
      "left": 0, "top": 0,
      "size": {"default": 400, "min": 1, "max": 400},
      "slot": "back",
      "rest": 0,
      "dark": {"default": 0.3, "min": 0, "max": 1},
      "cover": {"default": 0.45, "min": 0, "max": 1}
    },
    "layers": [{
      "shape": "circle", "color": "#000000", "slot": "$slot", "rest": "$rest",
      "x": "$left + $size / 2", "y": "$top + $size / 2", "radius": "$size / 2",
      "alpha": "1 - (1 - $cover) / (1 - $dark * $cover)",
      "clip": {"round": false}
    }, {
      "image": "@moon", "rect": {"left": "$left", "top": "$top", "width": "$size", "height": "$size"},
      "slot": "$slot", "rest": "$rest",
      "rotation": "v_moon_north", "maxScale": 2,
      "alpha": "$dark * $cover",
      "clip": {"round": false}
    }, {
      "shape": "circle", "group": "moon", "slot": "$slot", "rest": "$rest",
      "gradient": {"type": "linear", "stops": [[0, 0], [0.49, 0], [0.51, 1], [1, 1]]},
      "x": "$left + $size / 2", "y": "$top + $size / 2", "radius": "$size / 2",
      "rotation": "v_moon_limb - 90",
      "clip": {"rect": {"left": "$left", "top": "$top", "width": "$size", "height": "$size"}, "round": false}
    }, {
      "shape": "circle", "group": "moon", "slot": "$slot", "rest": "$rest",
      "x": "$left + $size / 2", "y": "$top + $size / 2", "radius": "$size / 2",
      "rotation": "v_moon_limb - 90",
      "scaleX": "2 * v_moon_lit - 1",
      "visible": "v_moon_lit > 0.5",
      "clip": {"round": false}
    }, {
      "shape": "circle", "group": "moon", "blend": "erase", "slot": "$slot", "rest": "$rest",
      "x": "$left + $size / 2", "y": "$top + $size / 2", "radius": "$size / 2",
      "rotation": "v_moon_limb - 90",
      "scaleX": "1 - 2 * v_moon_lit",
      "visible": "v_moon_lit < 0.5",
      "clip": {"round": false}
    }, {
      "image": "@moon", "rect": {"left": "$left", "top": "$top", "width": "$size", "height": "$size"},
      "group": "moon", "blend": "inside", "slot": "$slot", "rest": "$rest",
      "rotation": "v_moon_north", "maxScale": 2,
      "clip": {"round": false}
    }]
  }
}
"""
}
