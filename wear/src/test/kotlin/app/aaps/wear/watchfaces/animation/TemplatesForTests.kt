// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import org.json.JSONObject

/**
 * The nine templates of the manual (`docs/watchface-animation.html`, chapter 7), for the tests. The app
 * has no templates of its own: a design brings them in the `templates` of its animation, and so do the
 * tests (see [animationOf]). Keep this text the same as the templates of the manual.
 */
object TemplatesForTests {

    /** A new copy of the templates by name, so a test may change it. */
    fun all(): JSONObject = JSONObject(TEXT)

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
        "sinA": "sin($angle * pi / 180)",
        "cosA": "cos($angle * pi / 180)",
        "tanA": "tan($angle * pi / 180)",
        "longest": "$length * (1 + $lengthVariation)",
        "span": "$region.height + longest * cosA + 1",
        "pad": "longest * abs(sinA)",
        "fromX": "$region.left - max(0, $region.height * tanA) - pad",
        "toX": "$region.left + $region.width + max(0, -$region.height * tanA) + pad",
        "fallen": "mod(r2 * span + $speed * (0.8 + 0.4 * r3) * ms / 1000, span)",
        "streak": "$length * (1 + $lengthVariation * (2 * r4 - 1))"
      },
      "x": "fromX + r1 * (toX - fromX) + fallen * tanA - streak * sinA",
      "y": "$region.top + fallen - streak * cosA",
      "length": "streak",
      "angle": "$angle",
      "thickness": "$thickness",
      "alpha": "env * $alpha / 255 * (0.45 + 0.55 * r5)",
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
        "h": "$minHeight + hash($seed, period, 2 + i * 4) * ($maxHeight - $minHeight)",
        "w": "$minWidth + hash($seed, period, 3 + i * 4) * ($maxWidth - $minWidth)"
      },
      "x": "$region.left - w / 2 + hash($seed, period, 5 + i * 4) * $region.width + (mod(jumps + i, 3) - 1) * $jump",
      "y": "$region.top + hash($seed, period, 4 + i * 4) * max(0, $region.height - h)",
      "width": "w",
      "height": "h",
      "colorIndex": "mod(i, 2)",
      "alpha": "if(hash($seed, period, 0) < $chance && inPeriod >= start && inPeriod < start + $burstMs, env * $alpha / 255, 0)",
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
      "let": {
        "period": "floor(60000 / bpm)",
        "at": "mod(max(0, floor(ms)), period)",
        "rise": "min(120, period * 0.3)",
        "fall": "max(50, period * 0.16)",
        "first": "if(at < rise, pow(sin(pi / 2 * at / rise), 2), exp(-(at - rise) / fall))",
        "d": "(at - rise - min(250, period * 0.3)) / max(25, period * 0.06)",
        "beat": "clamp(first + $secondBeat * exp(-d * d), 0, 1) * env"
      },
      "scaleX": "1 + $amplitude * beat",
      "scaleY": "1 + $amplitude * beat",
      "glowAlpha": "$glowAlpha / 255 * beat",
      "glow": {"color": "$glowColor", "radius": "$glowRadius"},
      "maxScale": "1 + $amplitude",
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
      "let": {
        "w": "ms - $delayMs",
        "s": "clamp((w - $upMs) / $settleMs, 0, 1)",
        "settled": "1 - exp(-5 * s) * cos(3 * pi * s) - exp(-5) * s"
      },
      "rotation": "if(w <= 0, $emptyAngle, if(w < $upMs, $emptyAngle + ($fullAngle - $emptyAngle) * easeInOut(w / $upMs), if(w < $upMs + $settleMs, $fullAngle + (rot0 - $fullAngle) * settled, rot0))) - rot0"
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
      "let": {
        "w": "ms - $delayMs",
        "s": "clamp((w - $upMs) / $settleMs, 0, 1)",
        "settled": "1 - exp(-5 * s) * cos(3 * pi * s) - exp(-5) * s"
      },
      "x": "if(w <= 0, 0, if(w < $upMs, $fullDx * easeInOut(w / $upMs), if(w < $upMs + $settleMs, $fullDx + (dx0 - $fullDx) * settled, dx0))) - dx0"
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
      "alpha": "if(w > 0 && w < $durationMs, $alpha / 255 * min(1, (1 - w / $durationMs) * 8), 0)",
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
        "diagonal": "400 * sqrt(2)",
        "w": "ms - $delayMs",
        "middle": "200 - diagonal / 2 - $width / 2 + (diagonal + $width) * easeInOut(w / $durationMs)"
      },
      "pivotX": 200, "pivotY": 200,
      "rotation": "$angle",
      "x": "middle - $width / 2",
      "y": "200 - diagonal",
      "width": "$width",
      "height": "2 * diagonal",
      "alpha": "if(w >= 0 && w <= $durationMs, $alpha / 255, 0)",
      "clip": {"round": false, "rings": "$rings", "boxes": "$boxes", "cornerRadius": "$cornerRadius"}
    }]
  }
}
"""
}
