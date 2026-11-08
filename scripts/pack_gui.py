#!/usr/bin/env python3
# A window for scripts/pack_android.py: pick a game (a .nup file or a dump folder), see what it holds and whether the tools work, then prepare its pack
# (the rewritten code and the Vulkan shader library) with a progress bar and the packer's log. The packing itself is pack_android.py's, run as a subprocess with
# the arguments the window shows, so the command line and the window make the same pack.
# Start it with scripts/bananative.command (macOS) or scripts/bananative.bat (Windows), which find or make the environment it needs (android/environment.yml).
# Usage: <env python> scripts/pack_gui.py [<game folder | .nup file>]   (exit 1 when Tk is missing)
import os, queue, re, signal, subprocess, sys, threading, time
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'scripts')); sys.path.insert(0, str(REPO / 'dm/games')); sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
try:
    import tkinter as tk
    from tkinter import ttk, filedialog, messagebox
except ImportError:
    sys.exit(f'[RED] {sys.executable} has no Tk: use the environment of android/environment.yml (conda-forge Python has Tk), or brew install python-tk')
import pack_dump
import game
import bananative_env

PACKER = REPO / 'scripts/pack_android.py'
WINDOWS = os.name == 'nt'
bananative_env.apply()   # the environment's programs (spirv-val) and .NET runtime for the packer's subprocesses, also when started without the launcher

RE_SPLIT = re.compile(r'^progress: (\d+)/(\d+)')            # shader_split.py
RE_BUILD = re.compile(r'^progress (\d+) (\d+) ?(.*)')       # vk_android_build.py
RE_MOD = re.compile(r'^  (\S+): (rewriting|done|unchanged)')  # pack_android.rewrite


def inspect(path):
    """(title ID, name or None, [versions], DLC count, default pack folder, profile note) of a game folder or .nup file"""
    app, vers, dlc = pack_dump.scan(path)
    tid = f'{app:016X}'
    try: g = game.load(tid); name, note = g.get('name', '?'), f'profile dm/games/{tid}'
    except SystemExit: name, note = None, f'no profile dm/games/{tid}: this game cannot be packed yet'
    return tid, name, sorted(vers), len(dlc), path.parent / f'{tid}-work' / 'android-pack', note


class App:
    def __init__(self, root, initial=None):
        self.root, self.proc, self.q, self.info, self.t0 = root, None, queue.Queue(), None, 0.0
        root.title('Banana Native: prepare a game'); root.minsize(760, 560)
        f = ttk.Frame(root, padding=10); f.pack(fill='both', expand=True)
        f.columnconfigure(1, weight=1)

        ttk.Label(f, text='Game').grid(row=0, column=0, sticky='w')
        self.game = tk.StringVar()
        ttk.Entry(f, textvariable=self.game, state='readonly').grid(row=0, column=1, sticky='ew', padx=6)
        b = ttk.Frame(f); b.grid(row=0, column=2, sticky='e')
        ttk.Button(b, text='.nup file…', command=self.pick_nup).pack(side='left')
        ttk.Button(b, text='Dump folder…', command=self.pick_dir).pack(side='left', padx=(4, 0))

        self.about = ttk.Label(f, text='Pick a .nup file or a dump folder (base/, updates/<version>/, dlc/).', foreground='gray')
        self.about.grid(row=1, column=0, columnspan=3, sticky='w', pady=(4, 8))

        o = ttk.LabelFrame(f, text='Options', padding=8); o.grid(row=2, column=0, columnspan=3, sticky='ew')
        o.columnconfigure(1, weight=1)
        ttk.Label(o, text='Version').grid(row=0, column=0, sticky='w')
        self.version = tk.StringVar(value='newest')
        self.vbox = ttk.Combobox(o, textvariable=self.version, values=['newest'], state='readonly', width=12)
        self.vbox.grid(row=0, column=1, sticky='w', padx=6)
        ttk.Label(o, text='Shaders at once').grid(row=0, column=2, sticky='e')
        self.J = tk.IntVar(value=min(8, os.cpu_count() or 8))
        ttk.Spinbox(o, from_=1, to=os.cpu_count() or 8, textvariable=self.J, width=4).grid(row=0, column=3, padx=6)
        self.no_shaders = tk.BooleanVar()
        ttk.Checkbutton(o, text='Code only, no shader library', variable=self.no_shaders).grid(row=0, column=4, sticky='w')
        ttk.Label(o, text='Pack folder').grid(row=1, column=0, sticky='w', pady=(6, 0))
        self.out = tk.StringVar()
        ttk.Entry(o, textvariable=self.out).grid(row=1, column=1, columnspan=3, sticky='ew', padx=6, pady=(6, 0))
        ttk.Button(o, text='Choose…', command=self.pick_out).grid(row=1, column=4, sticky='w', pady=(6, 0))

        t = ttk.LabelFrame(f, text='Tools', padding=8); t.grid(row=3, column=0, columnspan=3, sticky='ew', pady=8)
        self.tools = ttk.Label(t, text='checking…', justify='left'); self.tools.pack(anchor='w')

        r = ttk.Frame(f); r.grid(row=4, column=0, columnspan=3, sticky='ew')
        r.columnconfigure(1, weight=1)
        self.go = ttk.Button(r, text='Prepare', command=self.start, state='disabled'); self.go.grid(row=0, column=0)
        self.bar = ttk.Progressbar(r, mode='determinate', maximum=1000); self.bar.grid(row=0, column=1, sticky='ew', padx=8)
        self.stop_b = ttk.Button(r, text='Stop', command=self.stop, state='disabled'); self.stop_b.grid(row=0, column=2)
        self.open_b = ttk.Button(r, text='Show pack', command=self.show_pack, state='disabled'); self.open_b.grid(row=0, column=3, padx=(4, 0))
        self.stage = ttk.Label(f, text=''); self.stage.grid(row=5, column=0, columnspan=3, sticky='w', pady=(4, 4))

        lf = ttk.Frame(f); lf.grid(row=6, column=0, columnspan=3, sticky='nsew'); f.rowconfigure(6, weight=1)
        self.log = tk.Text(lf, height=16, wrap='word', font=('Consolas', 10) if WINDOWS else ('Menlo', 11), state='disabled')
        sb = ttk.Scrollbar(lf, command=self.log.yview); self.log['yscrollcommand'] = sb.set
        self.log.pack(side='left', fill='both', expand=True); sb.pack(side='right', fill='y')
        self.log.tag_configure('red', foreground='#d03030'); self.log.tag_configure('me', foreground='gray')

        self.tools_ok = self.has_zstd = False
        threading.Thread(target=lambda: self.q.put(('tools', bananative_env.check())), daemon=True).start()
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.after(100, self.pump)
        if initial: self.choose(Path(initial).expanduser().resolve())

    # picking
    def pick_nup(self):
        p = filedialog.askopenfilename(title='A .nup file', filetypes=[('NUP package', '*.nup'), ('All files', '*')])
        if p: self.choose(Path(p))

    def pick_dir(self):
        p = filedialog.askdirectory(title='A dump folder (base/, updates/, dlc/)', mustexist=True)
        if p: self.choose(Path(p))

    def pick_out(self):
        p = filedialog.askdirectory(title='The pack folder')
        if p: self.out.set(p)

    def choose(self, path):
        self.game.set(str(path)); self.info = None; self.update_go()
        self.about.configure(text='reading…', foreground='gray')
        def work():
            try: self.q.put(('game', path, inspect(path)))
            except (pack_dump.Error, OSError, ValueError) as e: self.q.put(('game', path, e))
        threading.Thread(target=work, daemon=True).start()

    def update_go(self):
        ok = bool(self.info and self.info[1]) and self.proc is None
        self.go.configure(state='normal' if ok else 'disabled')

    # running
    def command(self):
        cmd = [sys.executable, '-u', str(PACKER), self.game.get(), '-J', str(self.J.get())]
        if self.has_zstd: cmd += ['--shader-python', sys.executable]   # else the packer looks for python3.14 / python3 with zstd on PATH
        if self.version.get() != 'newest': cmd += ['--version', '0' if self.version.get() == 'base' else self.version.get()]
        if self.out.get().strip() and Path(self.out.get()) != self.info[4]: cmd += ['--out', self.out.get().strip()]
        if self.no_shaders.get(): cmd.append('--no-shaders')
        return cmd

    def start(self):
        if not self.tools_ok and not messagebox.askyesno('Tools missing', 'Some tools the packer needs are missing (see "Tools"). Start anyway?'): return
        cmd = self.command()
        self.clear(); self.say('$ ' + ' '.join(cmd), 'me'); self.no_job = False
        # stop() ends the packer with its subprocesses: on macOS they share its session; on Windows the packer puts them in a kill-on-close job (pack_android.py)
        group = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW} if WINDOWS else {'start_new_session': True}
        self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', bufsize=1, cwd=str(REPO),
                                     env=dict(os.environ, PYTHONUNBUFFERED='1', PYTHONUTF8='1'), **group)
        self.t0 = time.monotonic(); self.set_stage('Starting'); self.bar.configure(mode='indeterminate'); self.bar.start(15)
        self.go.configure(state='disabled'); self.stop_b.configure(state='normal'); self.open_b.configure(state='disabled')
        threading.Thread(target=self.read, args=(self.proc,), daemon=True).start()

    def read(self, p):
        for line in p.stdout: self.q.put(('line', line.rstrip('\n')))
        self.q.put(('exit', p.wait()))

    def stop(self):
        if self.proc and self.proc.poll() is None:
            if WINDOWS and self.no_job: subprocess.run(['taskkill', '/PID', str(self.proc.pid), '/T', '/F'], capture_output=True)   # the packer's process tree
            elif WINDOWS: self.proc.terminate()   # its job ends the tools it started
            else:
                try: os.killpg(self.proc.pid, signal.SIGTERM)   # the packer and its rewriter / shader subprocesses (one session)
                except ProcessLookupError: pass
            self.say('stopping… (a later run resumes: finished modules and translated shaders are kept)', 'me')

    def close(self):
        if self.proc and self.proc.poll() is None:
            if not messagebox.askyesno('Preparing', 'A pack is being prepared. Stop it and quit?'): return
            self.stop()
        self.root.destroy()

    def show_pack(self):
        out = Path(self.out.get().strip() or self.info[4])
        d = out if out.is_dir() else out.parent
        if WINDOWS: os.startfile(d)
        else: subprocess.run(['open', str(d)])

    # the log and the progress bar
    def set_stage(self, s): self.stage.configure(text=s)

    def determinate(self, done, total, what):
        if str(self.bar['mode']) != 'determinate': self.bar.stop(); self.bar.configure(mode='determinate')
        self.bar['value'] = 1000 * done / max(1, total)
        el = time.monotonic() - self.t0
        self.set_stage(f'{what}: {done:,} / {total:,}  ({el / 60:.0f} min elapsed)')

    def line(self, s):
        self.say(s, 'red' if s.startswith('[RED]') else None)
        if 'are not in a job' in s: self.no_job = True   # pack_android.windows_job failed: stop() ends the tree with taskkill
        if m := RE_SPLIT.match(s): return self.determinate(int(m[1]), int(m[2]), 'Splitting the shaders out of the dump')
        if m := RE_BUILD.match(s): return self.determinate(int(m[1]), int(m[2]), 'Building the shader library' + (f' ({m[3]})' if m[3] else ''))
        if m := RE_MOD.match(s): return self.set_stage(f'Rewriting the code: {m[1]} {m[2]}')
        if s.startswith('shaders: splitting'): self.set_stage('Splitting the shaders out of the dump (a few minutes)')
        elif s.startswith('shaders: building'): self.set_stage('Building the shader library (about 10 minutes)')

    def say(self, s, tag=None):
        self.log.configure(state='normal'); self.log.insert('end', s + '\n', tag or ())
        self.log.see('end'); self.log.configure(state='disabled')

    def clear(self):
        self.log.configure(state='normal'); self.log.delete('1.0', 'end'); self.log.configure(state='disabled')

    def pump(self):
        try:
            while True: self.handle(self.q.get_nowait())
        except queue.Empty: pass
        self.root.after(100, self.pump)

    def handle(self, ev):
        if ev[0] == 'line': self.line(ev[1])
        elif ev[0] == 'exit':
            rc, el = ev[1], time.monotonic() - self.t0
            self.bar.stop(); self.bar.configure(mode='determinate'); self.proc = None
            if rc == 0: self.bar['value'] = 1000; self.set_stage(f'Done in {el / 60:.1f} min. On the phone, put the pack next to the game: <title ID>-work/android-pack (only android-pack).')
            else: self.set_stage(f'Stopped (exit code {rc}) after {el / 60:.1f} min: see the log'); self.bar['value'] = 0
            self.stop_b.configure(state='disabled'); self.open_b.configure(state='normal'); self.update_go()
        elif ev[0] == 'tools':
            self.tools_ok = all(ok for what, ok, _ in ev[1] if not what.startswith('Tk')); self.has_zstd = any(ok for what, ok, _ in ev[1] if what == 'zstd')
            self.tools.configure(text='\n'.join(f'{"✓" if ok else "✗"}  {what}' + (f'   {d}' if d else '') for what, ok, d in ev[1]))
        elif ev[0] == 'game':
            path, r = ev[1], ev[2]
            if str(path) != self.game.get(): return   # another game was picked meanwhile
            if isinstance(r, Exception):
                self.info = None; self.about.configure(text=f'{r}', foreground='#d03030')
            else:
                self.info = r; tid, name, vers, ndlc, out, note = r
                self.about.configure(text=f'{name or "?"} ({tid})   versions: base' + ''.join(f', {v}' for v in vers) + f'   DLC: {ndlc}   {note}',
                                     foreground='' if name else '#d03030')
                self.vbox.configure(values=['newest', 'base', *map(str, vers)]); self.version.set('newest')
                self.out.set(str(out)); self.open_b.configure(state='normal' if out.is_dir() else 'disabled')
            self.update_go()


def main():
    root = tk.Tk()
    App(root, sys.argv[1] if len(sys.argv) > 1 else None)
    root.mainloop()


if __name__ == '__main__':
    main()
