# fonts: stand-in shared fonts (open licence only)

`NotoSans-Regular-subset.ttf` is the stand-in the host serves for the shared-font types on Android (`dm/host/hle_sdk16.c`, `$DM_FONT_DIR/standard.ttf`), so that text no longer depends on the phone's `/system/fonts`. The launcher copies it into the app's files folder and sets `DM_FONT_DIR` to it (`android/app/build.gradle` `nxFontAssets`, `GameFonts.java`).

Nintendo's fonts are never included, downloaded or imitated here.

| | |
|---|---|
| Font | Noto Sans Regular, static hinted TrueType (glyf outlines, no `fvar`) |
| Version | NotoSans-v2.015 (notofonts/latin-greek-cyrillic release, 2024-11-20), 2197 glyphs after subsetting |
| Source | https://github.com/notofonts/latin-greek-cyrillic/releases/download/NotoSans-v2.015/NotoSans-v2.015.zip (zip sha256 `0c34df072a3fa7efbb7cbf34950e1f971a4447cffe365d3a359e2d4089b958f5`), file `NotoSans/hinted/ttf/NotoSans-Regular.ttf` (621,572 bytes) |
| This file | `NotoSans-Regular-subset.ttf`, 337,344 bytes, sha256 `5d9684f477933f00bf42bd8d6a0c3fba4609f9c052db285820dfdb377c310f9d` |
| Licence | SIL Open Font License 1.1, `OFL.txt` (copied unmodified from the release; the copyright line names no Reserved Font Name, so the subset keeps the font's name) |

Coverage: Basic Latin, Latin-1, Latin Extended A/B, IPA, spacing modifiers, combining marks, Greek, Cyrillic (with supplement), Latin Extended Additional, Greek Extended, general punctuation, currency, letterlike, number forms, arrows, mathematical operators, geometric shapes, miscellaneous symbols, dingbats, U+FFFD. No CJK, no Hangul, no private-use icons.

Reproduce (fonttools 4.68.0, in a venv outside the repository):

```
pyftsubset NotoSans/hinted/ttf/NotoSans-Regular.ttf \
  --unicodes="U+0020-007E,U+00A0-024F,U+0250-02AF,U+02B0-02FF,U+0300-036F,U+0370-03FF,U+0400-052F,U+1E00-1EFF,U+1F00-1FFF,U+2000-206F,U+20A0-20CF,U+2100-214F,U+2150-218F,U+2190-21FF,U+2200-22FF,U+25A0-25FF,U+2600-26FF,U+2700-27BF,U+FFFD" \
  --layout-features='kern,liga,ccmp,locl,mark,mkmk' --glyph-names --notdef-outline --name-IDs='*' --hinting \
  --output-file=NotoSans-Regular-subset.ttf
```
