"""The launcher's game page (android/app GameSettings.java with MainActivity.gameTab, Ui.java: ⋯ on a card opens it; tabs Performance, Saves, Storage): every per-game setting still has its row and its
picker or switch, rows keep their titles short enough for one line on a phone, and the page's parts (Recommended / Custom, the sections, the wide window's rail)
are there. Source checks only: the look is checked on the phone (android/README.md, The launcher)."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'

# A row's title, one line at the normal font scale on a 1080-wide phone (about 22 characters with a switch at its end)
MAX_TITLE = 22


def titles(src):
    return re.findall(r'new Ui\.Setting\((?:a|this), "[^"]+", "([^"]+)"', src)


class GameSettingsPage(unittest.TestCase):
    def setUp(self):
        self.gs = (SRC / 'GameSettings.java').read_text(encoding='utf-8')

    def test_every_setting_reachable(self):
        for call in ('presets(a, g, again)', 'resolution(a, g, again)', 'scene(a, g, again)', 'upscaler(a, g, again)', 'sharpness(a, g, again)', 'dlssMode(a, g, again)',
                     'clarity(a, g, again)', 'dlssSharp(a, g, again)', 'display(a, g, again)', 'aspect(a, g, again)', 'cap(a, g, again)', 'drs(a, g, again)',
                     'driver(a, g, again)', 'gpuTiming(a, g, again)', 'language(a, g, again)', 'xbox(a, g, again)', 'PadMapUi.forGame(a, g, again)', 'env(a, g, again)',
                     'Prefs.resetGame(a, id)', 'Prefs.backToRecommended(a, id)'):
            self.assertIn(call, self.gs, f'{call}: its row is gone')
        for setter in ('fullVegetation', 'skipLayers', 'hold60', 'decouple', 'instancedDraws', 'classicPasses', 'psoAsync', 'ramSaver', 'pauseOnReturn', 'rumble', 'pinThreads'):
            self.assertIn(f'Prefs.{setter}(a, id, on)', self.gs, f'{setter}: its switch is gone')
        self.assertIn('Prefs.gameOption(a, id, opt.env, checked)', self.gs, "the profile's options")

    def test_short_titles(self):
        t = titles(self.gs)
        self.assertGreater(len(t), 25)
        for s in t: self.assertLessEqual(len(s), MAX_TITLE, f'"{s}" does not fit on one line')
        self.assertIn('Hidden layers off', t)
        self.assertIn('hid.badge("Experimental")', self.gs, 'the speedhack says it is experimental')

    def test_sections(self):
        for key, label in (('picture', 'Picture'), ('speed', 'Speed'), ('options', 'Game options'), ('controls', 'Controls'), ('advanced', 'Advanced')):
            self.assertIn(f'"{key}", ', self.gs); self.assertIn(f'"{label}")', self.gs)
        self.assertIn('new Ui.Segmented(a, new String[] {"Recommended", "Custom"}', self.gs)
        self.assertIn('screenWidthDp >= 600', self.gs, 'a wide window gets the rail')
        self.assertIn('rail(a, content, all, reset, again)', self.gs); self.assertIn('column(a, content, all, reset)', self.gs)
        self.assertNotIn('Ui.sheet(a, "Game settings', self.gs, 'a page, not a dialog')

    def test_ui_helpers(self):
        ui = (SRC / 'Ui.java').read_text(encoding='utf-8')
        for h in ('static final class Segmented', 'static TextView navItem(', 'static TextView statusChip(', 'Setting says(CharSequence on, CharSequence off)', 'static LinearLayout tabs(',
                  'Setting badge(CharSequence s)', 'Setting sub()'):
            self.assertIn(h, ui)

    def test_one_page(self):   # ⋯ opens the game's page: Performance (the settings), Saves, Storage under tabs, Play in its header
        ma = (SRC / 'MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('private void manage(Game g) { GameSettings.open(this, g); }', ma, 'no floating menu')
        self.assertNotIn('Ui.sheet(this, null, box)', ma)
        self.assertIn('{"Performance", "Game", "Saves", "Storage"}', self.gs)
        self.assertIn('Ui.tabs(a, labels, t, ', self.gs)
        self.assertIn('Ui.button(a, "Play", Ui.Kind.PRIMARY, v -> a.launch(g))', self.gs, 'Play in the header')
        self.assertIn('t.setMaxLines(2);', self.gs.split('private static LinearLayout header(')[1].split('return out;')[0], 'a long name wraps')
        m = ma.split('void gameTab(LinearLayout content, Game g, boolean savesTab) {')[1].split('\n    }\n')[0]
        for s in titles(m): self.assertLessEqual(len(s), MAX_TITLE, s)
        for call in ('SaveUi.backup(this, g)', 'SaveUi.restoreList(this, g)', 'SaveUi.importPick(this, g)', 'Ondevice.prepare(this, new File(g.gameDir), true)',
                     'Ondevice.manageJob(this)', 'PackUi.exportPick(this, g)', 'PackUi.importPick(this, g)', 'Folders.dialog(this, g)', 'Shortcuts.pin(this, g)', 'shareLog(g.titleId)', 'confirmRemove(g)'):
            self.assertIn(call, m, f'{call}: the page lost it')
        self.assertIn('void gameSettings(Game g)', ma)
        self.assertIn('out.putString("game_settings", settingsGame.gameDir)', ma, 'a fold or unfold keeps the page')

    def test_switch_lines_follow_the_switch(self):   # a switch's second line says what its state does, never the opposite state
        self.assertIn('says(); });   // the value line follows the switch', (SRC / 'Ui.java').read_text(encoding='utf-8'))
        for name in ('hold', 'full', 'vegetation', 'hid'): self.assertRegex(self.gs, name + r'(\.badge\("Experimental"\))?\.says\(', name)
        self.assertNotIn('Off: drops to 30', self.gs)
        self.assertNotIn('Long-press', self.gs, 'no row has a long press')
        self.assertNotIn('.help(', self.gs)

    def test_rows_say_what_they_do(self):   # seen on the folded 1080 px screen: tags squeezing the text, "Needs Prepare again (Pr…" in place of the description
        ui = (SRC / 'Ui.java').read_text(encoding='utf-8')
        row = ui.split('static final class Setting {')[1].split('private void line()')[0]
        self.assertIn('value.setMaxLines(3);', row, 'a description wraps instead of being cut')
        self.assertLess(row.index('col.addView(value'), row.index('col.addView(tags'), 'the tags on their own line, under the text')
        self.assertIn('Setting status(CharSequence s)', ui)
        self.assertIn('s.value(opt.description);', self.gs, "an option's row always says what it does")
        self.assertIn("Needs Prepare again: patches the game's code", self.gs, 'and why it needs Prepare again: code, not shaders')
        self.assertNotIn('Needs Prepare again (Storage', self.gs)
        self.assertIn('optionIcon(opt.env)', self.gs)
        for env, icon in (('DM_OOT_MAX_RESOLUTION', '🔭'), ('DM_OOT_TEXTURE_POOL_X2', '🧠'), ('DM_OOT_FPS_UNLOCK', '⏩')): self.assertIn(icon, self.gs, env)
        self.assertNotIn('"Default · "', (SRC / 'PadMapUi.java').read_text(encoding='utf-8'), 'Button mapping never reads Default · Default')

    def test_option_descriptions_for_players(self):   # game.tsv option rows: plain sentences for the player, no developer wording
        rows = [l.split('\t') for p in sorted((REPO / 'dm/games').glob('*/game.tsv')) for l in p.read_text(encoding='utf-8').splitlines() if l.startswith('option\t')]
        self.assertGreater(len(rows), 2)
        for r in rows:
            d = r[3]
            self.assertGreater(len(d), 40, r[2]); self.assertLess(len(d), 260, r[2])
            for word in ('guest memory', 'allocation', 'update step', 'capped at', ';', '|'): self.assertNotIn(word, d, f'{r[2]}: {word}')

if __name__ == '__main__':
    unittest.main()
