"""The games an APK carries by default: android/app/gradle.properties' nxGames is every game supported on Android, the profiles whose readiness.tsv has a status row
(dm/games/README.md "Readiness rows"), so a build made without -PnxGames never lacks one (a player's card once said "Could not prepare" for a game the APK did not have).
Each listed game has what build.gradle reads of it: game.tsv with build rows, and shader_map.tsv for Prepare's shaders."""
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
PROPS = REPO / 'android/app/gradle.properties'


def default_games():
    for line in PROPS.read_text(encoding='utf-8').splitlines():
        if line.startswith('nxGames='): return [g.strip() for g in line[len('nxGames='):].split(',') if g.strip()]
    return []


def android_profiles():
    out = set()
    for t in (REPO / 'dm/games').glob('*/readiness.tsv'):
        for line in t.read_text(encoding='utf-8').splitlines():
            c = line.split('\t')
            if len(c) >= 2 and c[0] == 'status' and c[1].strip() in ('playable', 'bring-up'): out.add(t.parent.name)
    return out


class Games(unittest.TestCase):
    def test_every_supported_game_by_default(self):
        games = default_games()
        self.assertTrue(games, 'gradle.properties has no nxGames row')
        self.assertEqual(len(games), len(set(games)), 'a game twice in nxGames')
        self.assertEqual(set(games), android_profiles(), 'nxGames must be the profiles with a readiness.tsv status row')
        self.assertEqual(games[0], '040074A01BF12000', 'the first (the default game, the self-test\'s host) stays Donkey Kong Bananza')

    def test_listed_games_have_what_the_build_reads(self):
        for g in default_games():
            d = REPO / 'dm/games' / g
            self.assertTrue((d / 'game.tsv').is_file(), f'{g}: no game.tsv')
            self.assertTrue(any(l.startswith('build\t') for l in (d / 'game.tsv').read_text(encoding='utf-8').splitlines()), f'{g}: no build row (Prepare checks dumps against them)')
            self.assertTrue((d / 'shader_map.tsv').is_file(), f'{g}: no shader_map.tsv (Prepare builds its shaders from it)')

    def test_no_single_game_default(self):   # the old one-game default (nxGame) is gone: build.gradle reads nxGames
        self.assertNotIn('\nnxGame=', '\n' + PROPS.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
