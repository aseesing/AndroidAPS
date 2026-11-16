<!-- ============================================================================================
     PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
     Own file: keep upstream files to small hooks that call into here.
     ============================================================================================ -->
# Making a font file smaller (subset) for a watch face design

## When to use this

A custom watch face (CWF) design zip carries its own fonts from `wear/cwf-designs/fonts/`. The whole
design is sent to the watch unpacked, so the size of all its files counts, not the zip size. A big
design is slow (D2 at 358 KB took minutes) or never arrives at all (628 KB failed, with no error). A full Google font is often 60-100 KB, most of it Greek, Cyrillic, Vietnamese marks
and stylistic extras that a watch face never shows.

Cut such a font down to the Latin letters before you add it to a design. A text font goes from about
96 KB to about 41 KB. Examples in the repo: `Cyber.ttf` (Rajdhani Bold) and `D2Text.ttf` (Saira
Condensed Medium).

Keep all Latin letters, not only the glyphs the preview shows. The watch writes live text in the font:
day and month names in the language of the watch (any Latin-script language), AM/PM, glucose like
`18,5` or `6.7`, `+0,2`, `--`, `°`, `'`, `%`, `h`, `:`.

## 1. Install fontTools (once per machine)

```
pipx install fonttools
```

On macOS pipx is `/opt/homebrew/bin/pipx`; the tools land in `~/.local/bin` (`pyftsubset`, `ttx`) and
the Python that has fontTools is `~/.local/pipx/venvs/fonttools/bin/python`. Do not use sudo.

## 2. Make the subset

Work in a temporary folder and keep a copy of the original font there. Do not overwrite the original
in the repo before you checked the result.

```
pyftsubset Original.ttf --output-file=subset.ttf \
  --unicodes="U+0020-024F,U+02B0-02FF,U+0394,U+03BC,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-215F,U+2190-21FF,U+2206,U+2212,U+2215,U+221E,U+2248,U+2260-2265" \
  --no-hinting --drop-tables+=DSIG,FFTM
```

What the ranges keep:

| Range | What |
|---|---|
| `U+0020-024F` | Basic Latin, Latin-1 (é ë ü ß °), Latin Extended-A and -B |
| `U+02B0-02FF` | Modifier letters (ˆ ˜ ˇ and similar) |
| `U+0394`, `U+2206`, `U+03BC` | Δ (delta, both code points) and μ |
| `U+1E00-1EFF` | Latin Extended Additional (Vietnamese, Welsh ẁ ẃ) |
| `U+2000-206F` | Spaces, dashes, quotes, bullet, ‰, ′ ″ |
| `U+20A0-20CF` | Currency signs (€) |
| `U+2100-215F` | Letter-like symbols and fractions (℃ № ⅓) |
| `U+2190-21FF` | Arrows |
| `U+2212 ...` | Math signs: − ∕ ∞ ≈ ≠ ≤ ≥ |

Notes on the options:

- **Layout features: use the default, not `--layout-features='*'`.** The default keeps what Android
  turns on by itself (`kern`, `liga`, `locl`, ...). `'*'` also keeps superscripts, small figures,
  stylistic sets and their extra glyphs, which the watch never uses (the watch code sets no
  `fontFeatureSettings`). For Saira Condensed this saved 7 KB. `Cyber.ttf` was made with `'*'`
  before this was known.
- **Do not add the combining accents (`U+0300-036F`).** They pull in the mark positioning tables
  (`mark`, `mkmk`), about 8 KB more. Android writes date names with ready-made accented letters
  (é, not e + accent), so they are not needed.
- `--no-hinting` drops the hinting instructions. A watch screen has a high pixel density, so they do
  not help, and they are large.

## 3. Rename the font when the licence has a Reserved Font Name

Most of these fonts are under the SIL Open Font License (OFL). Read the first line of the licence
file. When it says `with reserved font name "Xyz"`, a changed font (and a subset is a changed font)
may **not** use the name `Xyz`. Then:

- give the file a new name that does not contain `Xyz` (for example `D2Text.ttf`, not
  `SairaCondensedMedium.ttf`), and
- change the names inside the font (the `name` table).

Keep the licence with the font under the new name (copy `Xyz-OFL.txt` to `NewName-OFL.txt`, without
changes) and keep the copyright line (name ID 0) as it is.

When the licence has no Reserved Font Name (Rajdhani, for example), you may keep the name, but a new
short name is also fine.

Set the name table with this script (`rename.py in.ttf out.ttf "D2 Text" D2Text-Regular`):

```python
import sys
from fontTools.ttLib import TTFont
src, dst, family, ps = sys.argv[1:5]
f = TTFont(src)
name = f["name"]
version = name.getDebugName(5) or "Version 1.0"
for nid in (16, 17, 21, 22):  # typographic names would still have the old name
    name.removeNames(nameID=nid)
name.setName(family, 1, 3, 1, 0x409)              # family
name.setName("Regular", 2, 3, 1, 0x409)           # style
name.setName(f"{version.replace('Version ', '')};{ps}", 3, 3, 1, 0x409)  # unique ID
name.setName(family, 4, 3, 1, 0x409)              # full name
name.setName(ps, 6, 3, 1, 0x409)                  # PostScript name
f.save(dst)
```

Run it with the pipx Python: `~/.local/pipx/venvs/fonttools/bin/python -I rename.py ...`. Check the
result: `ttx -t name -o - out.ttf` must not show the reserved name anywhere except the copyright line.
If the font also has Mac name records (platform 1), set those too or remove them.

## 4. Check the result

Check that the characters the watch needs are still in the character map (cmap), and see what was
dropped compared with the original:

```python
import sys
from fontTools.ttLib import TTFont
new = TTFont(sys.argv[1]).getBestCmap()
old = TTFont(sys.argv[2]).getBestCmap()
need = ("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        "éëüßèêïöäçñåøæœàáâãíóôõúûý" "°'%:,.+-−Δ")
print("missing:", [c for c in need if ord(c) not in new] or "none")
print("dropped:", " ".join(f"U+{c:04X}" for c in sorted(set(old) - set(new))))
```

`missing` must be `none` (a character that was not in the original font cannot be there either).
Then look at the file size, copy the font and its licence into `wear/cwf-designs/fonts/`, point the
design at the new file name, delete the old font file if nothing else uses it, and build the design
zip again. Look at the rendered preview: any box or empty spot in the text means a missing glyph.
