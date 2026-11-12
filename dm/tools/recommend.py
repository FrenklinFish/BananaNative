#!/usr/bin/env python3
"""The recommended settings per game and phone (game.tsv recommend rows; dm/games/README.md "Recommended settings", android/README.md "Recommended settings"):
checks every profile's rows as build.gradle's recommendOf does, and prints the launcher's column (Profile.recommend). An unknown setting, a value the setting does not take,
a setting the game does not offer, a match on a fact that is not allowed (a name: driver differences key on what the driver reports) or a driver row that sets more than
the driver is an error.

Usage:
  recommend.py                 check every profile (exit 1 on the first error)
  recommend.py <title ID>      check one profile and print its column"""
import re, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'games'))
import game   # noqa: E402

FACTS = ('gpu.vendor', 'gpu.device', 'driver.id', 'driver.version', 'vk.api')
FACT_PREFIXES = ('feature.', 'ext.', 'limit.')
COND = re.compile(r'([A-Za-z0-9_.]+)(?:&(0[xX][0-9a-fA-F]+|[0-9]+))?(!=|>=|<=|=|>|<)([0-9A-Za-z_.]+)')
UPSCALERS = ['nearest', 'bilinear', 'fsr1', 'native', 'sgsr1']   # GameUpscaler.MODES' order
DLSS_MODES = ['gsr2', 'fsr1', 'bilinear', 'fsr3']   # DlssTune.MODE_NAMES' order
DRS = ['off', '80', '70', '60', '50', '40', '30']   # Prefs.DRS_NAMES' order
CAPS = ['own', '60', '30', '20', '15']   # Prefs.FPS_CAPS {0, 60, 30, 20, 15}
PACE = ['60', '45', '40', '30', '20']   # Prefs.PACE_CAPS {0, 45, 40, 30, 20}
ON_OFF = ['on', 'off']


class Error(Exception): pass


def fact_allowed(f):
    return f in FACTS or any(f.startswith(p) and len(f) > len(p) for p in FACT_PREFIXES)


def offered(g):
    """What the launcher offers this game, as build.gradle reads it from the profile"""
    clean = lambda s: re.sub(r'[;|]', ',', re.sub(r'[\t\n\\"]', ' ', s)).strip()
    outs = {v[0] for v in g.values('scene_output') if len(v) >= 3 and re.fullmatch(r'\d+x\d+', v[1])}
    pal = bool(g.values('scene_scale_palette'))
    presets = [v[0] for v in g.values('res_preset') if len(v) >= 3 and (v[2] == 'default' or (re.fullmatch(r'(floor|fixed):\d+', v[2]) and pal and v[1] in outs))]
    opts = [clean(v[0]) for v in g.values('option') if v and v[0].strip()]
    ft = g.values('fps_target')
    patches = g.dir / 'patches.py'
    steps = bool(g.values('fps_steps')) or (bool(ft) and ft[0] and ft[0][0] != '0' and patches.is_file() and 'FRAME_STEP' in patches.read_text(encoding='utf-8'))
    limit = g.values('fps_limit')
    limited = bool(g.values('fps_decouple')) or bool(limit and limit[0] and limit[0][0] != '0')
    return {'presets': presets, 'options': opts, 'steps': steps, 'limited': limited, 'dlss': bool(g.values('dlss_standin')), 'drs': bool(g.values('drs_ctl')),
            'vegetation': bool(g.values('mesh_vegetation_cut')), 'skip_layers': bool(g.values('skip_layers'))}


def check_row(g, words, o=None):
    """One row's words (match, then setting=value …): the column's form of it, or Error"""
    o = o or offered(g)
    where = f'{g.id}: recommend'
    if len(words) < 2: raise Error(f'{where}: a row takes <match> <setting>=<value> …: {" ".join(words)}')
    match, sets = words[0], words[1:]
    if match != 'any':
        for cond in match.split(','):
            m = COND.fullmatch(cond)
            if not m: raise Error(f"{where}: the condition '{cond}' is not <fact>[&<mask>]<op><value>")
            if not fact_allowed(m.group(1)): raise Error(f'{where}: a match reads {", ".join(FACTS)} and feature.*, ext.*, limit.* (never a name), not {m.group(1)}')
    for kv in sets:
        k, eq, v = kv.partition('=')
        if not k or not eq or not v: raise Error(f"{where}: '{kv}' is not <setting>=<value>")

        def take(ok, have=True):
            if not have: raise Error(f'{where}: {k}: this game does not offer the setting')
            if v not in ok: raise Error(f'{where}: {k} takes {", ".join(ok)}, not {v}')
        if k == 'res_preset': take(o['presets'], bool(o['presets']))
        elif k == 'driver':
            if not (v == 'system' or re.fullmatch(r'[1-9][0-9]*', v)): raise Error(f'{where}: driver takes system or a VkDriverId (a number), not {v}')
            if len(sets) > 1: raise Error(f'{where}: a driver row sets only the driver: {" ".join(sets)}')
        elif k == 'upscaler': take(DLSS_MODES if o['dlss'] else UPSCALERS)
        elif k == 'hold60': take(ON_OFF, o['steps'])
        elif k == 'fps_cap': take(PACE if o['limited'] else CAPS)
        elif k == 'drs': take(DRS, o['drs'])
        elif k == 'vegetation': take(ON_OFF, o['vegetation'])
        elif k == 'skip_layers': take(ON_OFF, o['skip_layers'])
        elif k.startswith('option.'): take(ON_OFF, k[7:] in o['options'])
        else: raise Error(f'{where}: unknown setting {k} (res_preset, driver, upscaler, hold60, fps_cap, drs, vegetation, skip_layers, option.<ENV>)')
    return match + '|' + ' '.join(sets)


def column(g):
    """build.gradle recommendOf: the checked rows as the launcher reads them, ';' between them"""
    o = offered(g)
    return ';'.join(check_row(g, [w for v in row for w in v.split()], o) for row in g.values('recommend'))


def main():
    try:
        if len(sys.argv) > 1:
            print(column(game.load(sys.argv[1])))
        else:
            n = 0
            for g in game.all_games():
                column(g); n += len(g.values('recommend'))
            print(f'recommend rows: {n}, all valid')
    except Error as e:
        print(f'[RED] {e}', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
