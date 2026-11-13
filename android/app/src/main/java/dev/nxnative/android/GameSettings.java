package dev.nxnative.android;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A game's page (MainActivity.gameSettings: ⋯ on its card, or a long press; Back returns to Home): the game's name with Play, then tabs. **Performance** is the game's
 * settings for speed and picture: resolution, upscaler, frame rate cap, GPU driver and the testers' settings; **Game** the profile's options, Game language and
 * controls. All are stored per title ID (Prefs.gameKey,
 * GpuDriver.choice). Each starts as "Default", which follows the main screen's Settings (global), or this phone's recommendation (Recommend); a value set here wins
 * for this game only. The in-game panel's frame rate cap is this game's too. GameActivity reads them at start. **Saves** and **Storage** are the game's
 * actions (MainActivity.gameTab).
 * <p>
 * Performance: a chip with the GPU and the driver the game starts with, Recommended / Custom where the profile recommends settings for this phone, then the
 * sections Picture, Speed and Advanced; Game has Game options and Controls. A phone shows them one under the other, Advanced folded; a wide window (an unfolded foldable, a
 * tablet, landscape) shows a rail of the sections at the left and the one picked at the right. A row is one line of title over a line that says its value (a
 * switch's line says what its state does: Ui.Setting.says).
 */
final class GameSettings {
    private GameSettings() {}

    static final String PERFORMANCE = "performance", GAME = "game", SAVES = "saves", STORAGE = "storage";

    /** The tab, the section the rail shows on a wide window, and whether Advanced is open on a narrow one: kept while the app runs, so the page built again after a choice keeps its place */
    private static String tab = PERFORMANCE;
    private static String shown = "picture";
    private static boolean advancedOpen;
    private static boolean custom;   // the player picked Custom while nothing was changed yet (else the choice follows the changed count)
    private static String customFor;

    /** Opens a game's page (a tap on its tile) at its first tab */
    static void open(MainActivity a, Game g) {
        tab = Profile.missing(g.titleId, g.name) == null ? PERFORMANCE : SAVES;   // a game this app has no profile for has no settings
        a.gameSettings(g);
    }

    /** A controller's L1 / R1: the next (+1) or previous (-1) tab of the game's page, round; false when there is nothing to switch to */
    static boolean stepTab(Game g, int delta) {
        String[] keys = Profile.missing(g.titleId, g.name) == null ? new String[] {PERFORMANCE, GAME, SAVES, STORAGE} : new String[] {SAVES, STORAGE};
        int cur = Math.max(0, Arrays.asList(keys).indexOf(tab));
        int next = PadPanel.tab(cur, delta, keys.length);
        if (next == cur) return false;
        tab = keys[next];
        return true;
    }

    /** One section of the page: its rail entry and label, its card of rows, and a small line under the card */
    private static final class Section {
        final String key, emoji, label;
        final LinearLayout rows;
        final List<String> titles = new ArrayList<>();
        String info;

        Section(MainActivity a, String key, String emoji, String label) { this.key = key; this.emoji = emoji; this.label = label; rows = Ui.listCard(a); }

        boolean empty() { return rows.getChildCount() == 0; }

        Ui.Setting add(Ui.Setting s) { rows.addView(s.view); titles.add(s.title.getText().toString()); return s; }
    }

    /** Builds the page into content (Ui.page's column); again builds it once more after a choice (keeping the place), close returns to Home */
    static void build(MainActivity a, LinearLayout content, Game g, Runnable again, Runnable close) {
        String id = g.titleId;
        if (!id.equals(customFor)) { customFor = id; custom = false; }
        boolean wide = a.getResources().getConfiguration().screenWidthDp >= 600;
        String missing = Profile.missing(id, g.name);
        content.addView(header(a, g, missing == null, wide, close));
        String[] keys = missing == null ? new String[] {PERFORMANCE, GAME, SAVES, STORAGE} : new String[] {SAVES, STORAGE};
        String[] labels = missing == null ? new String[] {"Performance", "Game", "Saves", "Storage"} : new String[] {"Saves", "Storage"};
        int t = Math.max(0, Arrays.asList(keys).indexOf(tab));
        tab = keys[t];
        Ui.add(content, Ui.tabs(a, labels, t, k -> { tab = keys[k]; a.gameSettingsTop(); }), 4);
        if (missing != null) Ui.add(content, Ui.small(a, missing), 12);
        if (!tab.equals(PERFORMANCE) && !tab.equals(GAME)) { a.gameTab(content, g, tab.equals(SAVES)); return; }
        boolean perf = tab.equals(PERFORMANCE);
        if (perf) Ui.add(content, Ui.statusChip(a, chipText(a, id)), 12, Ui.wrap());
        TextView hint = Ui.add(content, Ui.small(a, "For this game only. Default follows Settings."), 6);
        hint.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);

        // Recommended settings for this phone (game.tsv recommend rows: Recommend, Prefs.recommended): Recommended / Custom over the recommended values in a line, and on each
        // row of such a setting a tag (Recommended, or Changed with a "Recommended: …" line), kept current by refresh after every toggle. Without a recommendation, no choice
        java.util.Map<String, Ui.Setting> recRows = new java.util.HashMap<>();
        Runnable[] refresh = {() -> {}};
        List<Recommend.State> states = Prefs.recStates(a, id);
        String recLine = Recommend.line(states, (s, v) -> Prefs.recCardName(a, id, s, v));
        if (recLine != null) {
            // the card shows on Performance; on Game it is built off the page, so the rows it tags (Full vegetation) keep their Recommended / Changed tags
            LinearLayout card = Ui.add(perf ? content : new LinearLayout(a), Ui.card(a), 12);
            card.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
            card.addView(Ui.text(a, "Recommended for this phone", 15, R.color.bn_text, 700));
            Ui.Segmented[] seg = new Ui.Segmented[1];
            seg[0] = Ui.add(card, new Ui.Segmented(a, new String[] {"Recommended", "Custom"}, 0, k -> {
                if (k == 1) { custom = true; refresh[0].run(); return; }   // Custom keeps the player's values: nothing to change
                if (Recommend.changedCount(Prefs.recStates(a, id)) == 0) { custom = false; refresh[0].run(); return; }
                new PadFocus.Builder(a).setTitle("Use recommended · " + g.name)
                        .setMessage("The settings you changed for this game that have a recommendation for this phone take it again. Your other settings stay.")
                        .setPositiveButton("Use recommended", (x, w) -> { Prefs.backToRecommended(a, id); custom = false; again.run(); })
                        .setNegativeButton("Cancel", (x, w) -> seg[0].select(1)).setOnCancelListener(x -> seg[0].select(1)).show();
            }), 10);
            TextView values = Ui.add(card, Ui.body(a, recLine), 8);
            values.setTextSize(14);
            TextView changed = Ui.add(card, Ui.small(a, ""), 2);
            refresh[0] = () -> {
                List<Recommend.State> now = Prefs.recStates(a, id);
                for (Recommend.State st : now) { Ui.Setting r = recRows.get(st.setting); if (r != null) decorate(a, id, r, st); }
                int n = Recommend.changedCount(now);
                String l = Recommend.line(now, (s, v) -> Prefs.recCardName(a, id, s, v)), c = Recommend.changedLine(n);
                if (n > 0) custom = false;   // the changed count decides from here on
                seg[0].select(n > 0 || custom ? 1 : 0);
                if (l != null) values.setText(l);
                changed.setText(c != null ? c + " Recommended puts them back." : custom ? "Your own values stay: change any setting below." : "");
                changed.setVisibility(changed.length() == 0 ? View.GONE : View.VISIBLE);
            };
        }

        Section picture = new Section(a, "picture", "🖼️", "Picture"), speed = new Section(a, "speed", "⏱️", "Speed"),
                options = new Section(a, "options", "🌿", "Game options"), controls = new Section(a, "controls", "🕹️", "Controls"),
                advanced = new Section(a, "advanced", "🧪", "Advanced");
        picture.info = "The in-game panel changes the upscaler and the window while the game runs; the rest applies at the next start.";
        speed.info = "The in-game panel changes the frame rate cap, Hold 60 fps and Full game speed while the game runs.";
        Profile prof = Profile.of(id);
        if (prof == null || prof.options.stream().noneMatch(o -> o.live)) options.info = "These apply at the next start.";
        advanced.info = "For testing and troubleshooting.";

        // ---- Picture ----
        List<ResPresets.Preset> rps = ResPresets.presets(prof);
        boolean simple = Prefs.simpleRes(a, id);   // a game with Resolution presets and the tester rows off (Settings › Help): Resolution's choices alone, no Advanced, Upscaler or Dynamic resolution
        if (!rps.isEmpty()) {   // a profile with res_preset rows: one Resolution (window and 3D scene together, ResPresets); Advanced shows the two settings apart
            String rc = Prefs.resolutionChoice(a, id);
            picture.add(track(recRows, Recommend.RES_PRESET, prepared(new Ui.Setting(a, "🖼️", "Resolution", v -> presets(a, g, again)), presetValue(a, g, rps, rc, simple)).note(ResPresets.NOTE).chevron(a)));
            if (ResPresets.ADVANCED.equals(rc) && !simple) {
                if (prof.hasResolution()) picture.add(new Ui.Setting(a, "🪟", "Window", v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a).sub());
                if (!SceneRes.presets(prof).isEmpty())
                    picture.add(prepared(new Ui.Setting(a, "🎬", "3D scene", v -> scene(a, g, again)), sceneValue(a, g, SceneRes.presets(prof))).chevron(a).sub());
            }
        } else {
            if (prof != null && prof.hasResolution())   // only where it does something: a profile with window_fn or graphics_scale rows
                picture.add(new Ui.Setting(a, "🖼️", "Resolution", v -> resolution(a, g, again)).value(windowLabel(a, id)).chevron(a));
            if (!SceneRes.presets(prof).isEmpty())   // a profile with scene_scale rows but no presets: the 3D scene below the window by the game's own palette (SceneRes)
                picture.add(prepared(new Ui.Setting(a, "🎬", "3D scene", v -> scene(a, g, again)), sceneValue(a, g, SceneRes.presets(prof))).chevron(a));
        }
        if (prof != null && prof.renderCfg()) renderRows(a, g, prof, picture, again);   // Resolution, Upscaling quality, Graphics quality instead of Resolution (a render_cfg row)
        if (simple) {   // the Upscaler stays as stored (GameActivity reads the preference); only testers change it for a game with Resolution presets
        } else if (Prefs.dlssGame(id)) {   // a game on the DLSS stand-in (game.tsv dlss_standin): its Upscaler is the stand-in (GSR2's sliders under GSR 2, the sharpness under FSR 1)
            int dm = GameUpscaler.dlssMode(a, id);
            picture.add(track(recRows, Recommend.UPSCALER, new Ui.Setting(a, "🔍", "Upscaler", v -> dlssMode(a, g, again)).value(shortName(DlssTune.MODE_NAMES[dm])).chevron(a)));
            if (DlssTune.showsClarity(dm))
                picture.add(new Ui.Setting(a, "🌀", "Motion clarity", v -> clarity(a, g, again)).value(dflt(GameUpscaler.ownClarity(a, id), DlssTune.clarityName(GameUpscaler.clarity(a, id)))).chevron(a).sub());
            if (DlssTune.showsSharp(dm))
                picture.add(new Ui.Setting(a, "✨", DlssTune.sharpTitle(dm), v -> dlssSharp(a, g, again)).value(dflt(GameUpscaler.ownDlssSharp(a, id), DlssTune.sharpName(GameUpscaler.dlssSharp(a, id)))).chevron(a).sub());
        } else {
            picture.add(track(recRows, Recommend.UPSCALER, new Ui.Setting(a, "🔍", "Upscaler", v -> upscaler(a, g, again)).value(shortName(GameUpscaler.MODES[GameUpscaler.mode(a, id)])).chevron(a)));
            if (GameUpscaler.mode(a, id) == GameUpscaler.MODE_FSR)   // only with FSR 1 chosen
                picture.add(new Ui.Setting(a, "✨", "FSR sharpness", v -> sharpness(a, g, again)).value(dflt(GameUpscaler.ownSharp(a, id), GameUpscaler.sharpName(a, id))).chevron(a).sub());
        }
        picture.add(new Ui.Setting(a, "📺", "Display", v -> display(a, g, again)).value(Prefs.DISPLAY_NAMES[Prefs.stretch(a, id) ? 1 : 0]).chevron(a));
        if (!aspects(prof).isEmpty())   // a profile with a native aspect mod (game.tsv variant rows labelled w:h)
            picture.add(new Ui.Setting(a, "🔳", "Aspect ratio", v -> aspect(a, g, again)).value(aspectLabel(a, id)).chevron(a));

        // ---- Speed ----
        if (!Prefs.limited(id) && Prefs.ownRate(a, id)) {   // the host found no limiter it can hold for this game: choices would do nothing (the host's frame limiter holds a limited game)
            Ui.Setting fixed = speed.add(new Ui.Setting(a, "⏱️", "Frame rate cap", null).value("This game picks its own frame rate"));
            fixed.view.setClickable(false);
            fixed.view.setAlpha(0.6f);
        } else {
            Ui.Setting cap = speed.add(track(recRows, Recommend.FPS_CAP, new Ui.Setting(a, "⏱️", "Frame rate cap", v -> cap(a, g, again)).value(capLabel(a, id)).chevron(a)));
            if (Prefs.limited(id) && Prefs.presentOnly(id)) { cap.badge("Experimental"); speed.info += " " + Prefs.PRESENT_ONLY_NOTE; }   // fps_limit_present: the limit paces the presents only
        }
        if (Prefs.steps(id)) {   // a framework of whole steps a frame (fps_steps): Hold 60 fps keeps one step at the cap of 60. The in-game panel changes it live
            Ui.Setting hold = new Ui.Setting(a, "🎯", "Hold 60 fps", null).toggle(a, Prefs.hold60(a, id), (b, on) -> { Prefs.hold60(a, id, on); refresh[0].run(); });
            hold.says("Never drops to 30; less even under 60", "Drops to 30 when 60 does not hold");
            speed.add(track(recRows, Recommend.HOLD60, hold));
        }
        if (Prefs.paced(id)) {   // the game's speed follows its measured frame rate (fps_decouple sead); off: as on the console. The in-game panel changes it live
            Ui.Setting full = new Ui.Setting(a, "🏃", "Full game speed", null).toggle(a, Prefs.decouple(a, id), (b, on) -> Prefs.decouple(a, id, on));
            full.says("Normal speed when the frame rate drops", "Slows down with the frame rate");
            speed.add(full);
        }
        if (Prefs.drsGame(id) && !simple)   // the host's dynamic resolution slider: testers only for a game with Resolution presets (Quality is the game's own dynamic resolution)
            speed.add(track(recRows, Recommend.DRS, new Ui.Setting(a, "📏", "Dynamic resolution", v -> drs(a, g, again)).value(drsValue(Prefs.drs(a, id))).chevron(a)));
        speed.add(track(recRows, Recommend.DRIVER, new Ui.Setting(a, "🎮", "GPU driver", v -> driver(a, g, again)).value(driverShort(a, id)).chevron(a)));

        // ---- Game options: the profile's own switches ----
        if (prof != null && prof.vegetationDetail) {
            Ui.Setting vegetation = new Ui.Setting(a, "🌿", "Full vegetation", null)
                    .toggle(a, Prefs.fullVegetation(a, id), (button, on) -> { Prefs.fullVegetation(a, id, on); refresh[0].run(); });
            vegetation.says("All grass and foliage", "Less grass and foliage, less GPU work");
            options.add(track(recRows, Recommend.VEGETATION, vegetation));
        }
        if (Prefs.skipLayersGame(id)) {   // a game whose profile names layers the picture covers anyway (game.tsv skip_layers)
            Ui.Setting hid = new Ui.Setting(a, "🚀", "Hidden layers off", null).toggle(a, Prefs.skipLayers(a, id), (b, on) -> { Prefs.skipLayers(a, id, on); refresh[0].run(); });
            hid.badge("Experimental").says("Skips layers drawn over later", "Draws every layer");
            options.add(track(recRows, Recommend.SKIP_LAYERS, hid));
        }
        if (prof != null && !prof.options.isEmpty()) {
            File packDir = g.packDir != null ? new File(g.packDir) : null;
            java.util.Map<String, Ui.Setting> rowSettings = new java.util.LinkedHashMap<>();
            boolean[] updating = new boolean[1];

            Runnable updateRows = () -> {
                updating[0] = true;
                try {
                    for (Profile.Option opt : prof.options) {
                        Ui.Setting s = rowSettings.get(opt.env);
                        if (s == null) continue;
                        Profile.Option source = Profile.findImpliedBy(prof.options, opt, env -> Prefs.gameOption(a, id, env));
                        boolean supported = opt.isSupported(packDir);
                        s.value(opt.description);   // always what it does; what it needs, or what turned it on, on a line of its own
                        // needs=<marker>: the rewriter's patch of the game's code that Prepare makes (main.fixups.tsv), not the shaders
                        s.status(!supported ? "Needs Prepare again: patches the game's code (Storage › Prepare again)" : source != null ? "Turned on by " + source.label : null);
                        if (source != null) {
                            s.toggle.setChecked(true);
                            s.toggle.setEnabled(false);
                            s.view.setAlpha(0.6f);
                        } else {
                            s.toggle.setChecked(Prefs.gameOption(a, id, opt.env));
                            s.toggle.setEnabled(true);
                            s.view.setAlpha(1.0f);
                        }
                    }
                } finally {
                    updating[0] = false;
                }
            };

            for (Profile.Option opt : prof.options) {
                Ui.Setting setting = new Ui.Setting(a, optionIcon(opt.env), opt.label, null)
                        .toggle(a, Prefs.gameOption(a, id, opt.env), (button, checked) -> {
                            if (updating[0]) return;
                            Prefs.gameOption(a, id, opt.env, checked);
                            updateRows.run();
                            refresh[0].run();
                        });
                rowSettings.put(opt.env, setting);
                options.add(track(recRows, Recommend.OPTION + opt.env, setting));
            }
            updateRows.run();
        }

        if (LinkPlay.has(id))
            options.add(new Ui.Setting(a, "🔗", "Link Play", v -> LinkPlay.dialog(a, g, again)).value(LinkPlay.label(a, id)).chevron(a));
        options.add(new Ui.Setting(a, "🌐", "Game language", v -> language(a, g, again)).value(GameLanguage.label(Prefs.gameLang(a, id), phoneTag())).chevron(a));   // not a tester's knob: with the game's options, never folded

        // ---- Controls ----
        controls.add(new Ui.Setting(a, "📳", "Rumble", null).toggle(a, Prefs.rumble(a, id), (b, on) -> Prefs.rumble(a, id, on)).says("The controller, or the phone without one", "No rumble"));
        controls.add(new Ui.Setting(a, "🕹️", "Button mapping", v -> PadMapUi.forGame(a, g, again)).value(PadMapUi.label(a, id)).chevron(a));
        controls.add(new Ui.Setting(a, "🅰️", "Xbox button labels", v -> xbox(a, g, again)).value(Prefs.xboxLabel(Prefs.xboxButtons(a, id))).chevron(a));

        // ---- Advanced: for testers ----
        advanced.add(new Ui.Setting(a, "⏲️", "GPU timing", v -> gpuTiming(a, g, again)).value(gpuTsLabel(a, id)).note("What the game's GPU timestamps measure: per segment lets its dynamic resolution see the real GPU time").chevron(a));
        // Speed-ups that pay off most on Qualcomm's driver (fewer draws for the GPU, less CPU a render pass); off by default (with either on, a tester lost the GPU on Adreno 830
        // with Turnip in 1-2 of 5 starts: open), each one can be turned on alone
        advanced.add(new Ui.Setting(a, "🧩", "Instanced draws", null).toggle(a, Prefs.instancedDraws(a, id), (b, on) -> Prefs.instancedDraws(a, id, on))
                .says("Repeated meshes drawn as one", "One draw per mesh"));
        advanced.add(new Ui.Setting(a, "🧱", "Classic render passes", null).toggle(a, Prefs.classicPasses(a, id), (b, on) -> Prefs.classicPasses(a, id, on))
                .says("Less CPU a pass on Qualcomm's driver", "Dynamic rendering"));
        advanced.add(new Ui.Setting(a, "🧵", "Background pipelines", null).toggle(a, Prefs.psoAsync(a, id), (b, on) -> Prefs.psoAsync(a, id, on))
                .says("A short pop-in, not a stutter", "Made when first drawn: may stutter"));
        advanced.add(new Ui.Setting(a, "💾", "RAM saver", null).toggle(a, Prefs.ramSaver(a, id), (b, on) -> Prefs.ramSaver(a, id, on))
                .says("Texture data in a file: needs GBs free", "Texture data in compressed RAM"));
        advanced.add(new Ui.Setting(a, "⚡", "Use fast cores", null).toggle(a, Prefs.pinThreads(a, id), (b, on) -> Prefs.pinThreads(a, id, on))
                .says("Busy threads off the small cores", "Android places the threads"));
        advanced.add(new Ui.Setting(a, "⏸️", "Pause on return", null).toggle(a, Prefs.pauseOnReturn(a, id), (b, on) -> Prefs.pauseOnReturn(a, id, on))
                .says("Back from the background: paused until a tap", "Back from the background: runs at once"));
        String env = Prefs.gameEnv(a, id);
        int envLines = env.isEmpty() ? 0 : env.split("\n").length;
        advanced.add(new Ui.Setting(a, "📝", "Extra environment", v -> env(a, g, again))
                .value(envLines == 0 ? "None" : envLines + (envLines == 1 ? " line: " : " lines: ") + env.replace('\n', ' ')).chevron(a));

        Section[] all = perf ? new Section[] {picture, speed, advanced} : new Section[] {options, controls};   // Performance: what changes speed and picture; Game: the game's own options, language and controls
        View reset = recLine != null ? null : Ui.button(a, "Reset to defaults", Ui.Kind.SOFT, v -> new PadFocus.Builder(a).setTitle("Reset to defaults · " + g.name)
                .setMessage("The game will follow global settings. Extra environment lines are kept.")
                .setPositiveButton("Reset", (x, w) -> { Prefs.resetGame(a, id); again.run(); }).setNegativeButton("Cancel", null).show());
        if (wide) rail(a, content, all, reset, again);
        else column(a, content, all, reset);
        refresh[0].run();   // the tags, the Recommended: lines and the choice's changed line
    }

    /**
     * The page's header: Back, the game's name and Play. A wide window has them in one row; a phone has Back and Play in a row and the name under them across the whole
     * width, two lines at most (a long name a little smaller), so a name like "The Legend of Zelda: Ocarina of Time" is not cut
     */
    private static LinearLayout header(MainActivity a, Game g, boolean play, boolean wide, Runnable close) {
        LinearLayout out = new LinearLayout(a);
        out.setOrientation(LinearLayout.VERTICAL);
        LinearLayout h = Ui.row(a);
        h.setMinimumHeight(Ui.dp(a, 56));
        h.addView(Ui.back(a, v -> close.run()));
        TextView t = Ui.text(a, g.name, g.name.length() > 30 ? 19 : 22, R.color.bn_text, 800);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setAccessibilityHeading(true);
        if (wide) {
            t.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 8), 0);
            h.addView(t, Ui.weight());
        } else h.addView(Ui.spacer(a));
        if (play) {
            android.widget.Button b = Ui.button(a, "Play", Ui.Kind.PRIMARY, v -> a.launch(g));
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(a, R.drawable.ic_play, R.color.bn_on_banana), null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(a, 4));
            b.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 18), 0);
            h.addView(b, Ui.wrap());
        }
        out.addView(h);
        if (!wide) {
            t.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
            Ui.add(out, t, 4);
        }
        return out;
    }

    /** A phone: the sections one under the other, each a label over its card and its line; Advanced folded under its label until opened (Show / Hide) */
    private static void column(MainActivity a, LinearLayout content, Section[] all, View reset) {
        for (Section s : all) {
            if (s.empty()) continue;
            if (!s.key.equals("advanced")) {
                Ui.add(content, Ui.section(a, s.label), 20);
                Ui.add(content, s.rows, 6);
                if (s.info != null) info(a, content, s.info);
                continue;
            }
            LinearLayout head = Ui.row(a);   // its label and Show / Hide: the whole line is the touch target
            head.setMinimumHeight(Ui.dp(a, 44));
            head.setBackground(Ui.ripple(a, Ui.round(android.graphics.Color.TRANSPARENT, Ui.dp(a, 12), 0, 0)));
            head.addView(Ui.section(a, s.label), Ui.weight());
            TextView act = Ui.text(a, "", 13, R.color.bn_accent, 700);
            act.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0);
            head.addView(act);
            ImageView ch = new ImageView(a);
            ch.setImageDrawable(Ui.icon(a, R.drawable.ic_chevron, R.color.bn_accent));
            ch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            head.addView(ch, new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)));
            head.setClickable(true);
            head.setFocusable(true);
            Ui.add(content, head, 12);
            TextView sum = info(a, content, s.info + " " + String.join(", ", s.titles) + ".");
            Ui.add(content, s.rows, 6);
            Runnable show = () -> {
                s.rows.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
                sum.setText(advancedOpen ? s.info : s.info + " " + String.join(", ", s.titles) + ".");
                act.setText(advancedOpen ? "Hide" : "Show " + s.titles.size());
                ch.setRotation(advancedOpen ? -90 : 90);
                head.setContentDescription(advancedOpen ? "Hide Advanced" : "Show Advanced, " + s.titles.size() + " settings");
            };
            head.setOnClickListener(v -> { advancedOpen = !advancedOpen; show.run(); });
            show.run();
        }
        if (reset != null) Ui.add(content, reset, 20, Ui.wrap());
    }

    /** A wide window: a rail of the sections at the left, the one picked at the right (its label, its card, its line), like a settings app */
    private static void rail(MainActivity a, LinearLayout content, Section[] all, View reset, Runnable again) {
        Section cur = null;
        for (Section s : all) if (!s.empty() && s.key.equals(shown)) cur = s;
        if (cur == null) for (Section s : all) if (!s.empty()) { cur = s; break; }
        if (cur == null) return;
        LinearLayout body = Ui.add(content, Ui.row(a), 16);
        body.setGravity(Gravity.TOP);
        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.VERTICAL);
        for (Section s : all) {
            if (s.empty()) continue;
            Ui.add(nav, Ui.navItem(a, s.emoji, s.label, s == cur, v -> { shown = s.key; again.run(); }), 2);
        }
        body.addView(nav, new LinearLayout.LayoutParams(Ui.dp(a, 210), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = Ui.weight();
        rp.setMarginStart(Ui.dp(a, 20));
        body.addView(right, rp);
        right.addView(Ui.section(a, cur.label));
        Ui.add(right, cur.rows, 6);
        if (cur.info != null) info(a, right, cur.info);
        if (reset != null) Ui.add(right, reset, 20, Ui.wrap());
    }

    /** The small line under a section's card */
    private static TextView info(MainActivity a, LinearLayout parent, String s) {
        TextView t = Ui.add(parent, Ui.small(a, s), 6);
        t.setTextSize(12);
        t.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        return t;
    }

    /** A profile option's tile, by what its switch's name says it touches (RES: the picture's size, POOL / MEM: memory, FPS / RATE: the frame rate); a plain slider otherwise */
    static String optionIcon(String env) {
        String e = env.toUpperCase(java.util.Locale.ROOT);
        return e.contains("RES") ? "🔭" : e.contains("POOL") || e.contains("MEM") ? "🧠" : e.contains("FPS") || e.contains("RATE") ? "⏩" : "🎚️";
    }

    /** A row whose value may end in "· needs Prepare again" (presetValue, sceneValue): the value alone, and why on a line of its own: the 3D scene sizes are made from the game's files at Prepare */
    private static Ui.Setting prepared(Ui.Setting s, String v) {
        String tail = " \u00b7 needs Prepare again";
        boolean need = v.endsWith(tail);
        s.value(need ? v.substring(0, v.length() - tail.length()) : v);
        return s.status(need ? "Needs Prepare again: makes this 3D scene size from your game files (Storage › Prepare again)" : null);
    }

    /** Dynamic resolution's value: Off, or the range the render size moves in (Prefs.DRS_NAMES: the lowest size, as % of the window; always up to 100 %) */
    static String drsValue(int i) { return i <= 0 ? "Off" : "Between " + Prefs.DRS_NAMES[i] + " and 100% of the window"; }

    /** A list's choice without its explanation in brackets, as a row's value ("FSR 1 (sharp, fast)" reads "FSR 1"); the list shows it whole */
    static String shortName(String s) { int i = s.indexOf(" ("); return i > 0 ? s.substring(0, i) : s; }

    /** The GPU driver row's value: System, or the installed driver's name and version */
    static String driverShort(MainActivity a, String id) { GpuDriver.Info i = GpuDriver.selected(a, id); return i == null ? "System" : i.label(); }

    /** The Performance tab's chip: the GPU (GLES's renderer, GpuGl) and the driver this game starts with (an installed one's name, else the system driver's from the kept facts) */
    static String chipText(MainActivity a, String id) {
        GpuDriver.Info i = GpuDriver.selected(a, id);
        String d = i != null ? i.label() : Readiness.driverWord(Prefs.facts(a, Recommend.SYSTEM));
        if (d == null) d = "System driver";
        String n = GpuGl.name();
        return n != null ? n + " · " + d : d;
    }

    /** The title ID whose last pick here needs another render target pool than the setting before it (Profile.renderRestartNote): the next drawing of its sheet shows the line once */
    private static String renderNoteFor;

    /**
     * Resolution, Upscaling quality and Graphics quality of a game whose profile has a render_cfg row: three rows, each opening its radio choices as Display does, labels from the
     * string resources (RenderLabels), in the Picture section. A pick stores all three (Prefs.render) for the next start. One line, "This change will apply on game restart.", leads the
     * section's line under its card only right after a pick that needs another render target pool size than the stored setting did
     */
    private static void renderRows(MainActivity a, Game g, Profile p, Section s, Runnable again) {
        String id = g.titleId;
        Profile.Render cur = Prefs.render(a, p, id);
        RenderLabels l = new RenderLabels(a);
        String[] outs = p.renderOutputs(), outLabels = new String[outs.length], pctLabels = new String[Profile.RENDER_PCTS.length];
        for (int i = 0; i < outs.length; i++) outLabels[i] = l.out(p, outs[i]);
        for (int i = 0; i < pctLabels.length; i++) pctLabels[i] = l.pct(Profile.RENDER_PCTS[i]);
        java.util.function.Consumer<Profile.Render> pick = r -> {
            renderNoteFor = p.renderRestartNote(r, null, cur, Prefs.lowRam(a)) ? id : null;
            Prefs.render(a, id, r);
            again.run();
        };
        s.add(new Ui.Setting(a, "🖼️", l.resTitle, v -> radio(a, l.resTitle + " · " + g.name, outLabels, Arrays.asList(outs).indexOf(cur.out),
                w -> pick.accept(new Profile.Render(outs[w], cur.pct, cur.quality)))).value(l.out(p, cur.out)).chevron(a));
        int pi = Math.max(0, indexOfPct(cur.pct));
        s.add(new Ui.Setting(a, "🔍", l.upscaleTitle, v -> radio(a, l.upscaleTitle + " · " + g.name, pctLabels, pi,
                w -> pick.accept(new Profile.Render(cur.out, Profile.RENDER_PCTS[w], cur.quality)))).value(pctLabels[pi]).chevron(a));
        s.add(new Ui.Setting(a, "✨", l.gfxTitle, v -> radio(a, l.gfxTitle + " · " + g.name, l.gfx, cur.quality ? 0 : 1,
                w -> pick.accept(new Profile.Render(cur.out, cur.pct, w == 0)))).value(l.gfx[cur.quality ? 0 : 1]).chevron(a));
        if (id.equals(renderNoteFor)) { s.info = s.info == null ? l.restartNote : l.restartNote + " " + s.info; renderNoteFor = null; }   // first in the line under the card
    }
    static int indexOfPct(int pct) { for (int i = 0; i < Profile.RENDER_PCTS.length; i++) if (Profile.RENDER_PCTS[i] == pct) return i; return -1; }
    private static void radio(MainActivity a, String title, String[] labels, int checked, java.util.function.IntConsumer picked) {
        new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(labels, checked, (x, w) -> { x.dismiss(); picked.accept(w); }).setNegativeButton("Cancel", null).show();
    }

    static String windowLabel(MainActivity a, String id) {
        String w = Prefs.gameWindow(a, id);
        Profile p = Profile.of(id);
        if (p != null && p.modeOf(w) >= 0) return p.modeLabel(p.modeOf(w));   // the two-choice form (Profile.modes): Docked or Handheld
        String l = Prefs.windowName(w, false);
        return l;
    }

    static String capLabel(MainActivity a, String id) {
        String l = Prefs.limited(id) ? Prefs.paceLabel(Prefs.paceCap(a, id)) : MainActivity.capLabel(Prefs.fpsCap(a, id));
        return l;
    }

    /** Registers a row of a recommended setting: refresh gives it its tag and its "Recommended: …" line */
    private static Ui.Setting track(java.util.Map<String, Ui.Setting> rows, String setting, Ui.Setting s) { rows.put(setting, s); return s; }

    /** A recommended setting's row: the tag before its value (Recommend.tag: Recommended, or Changed in the warning tint) and, when changed, a muted "Recommended: <short name>" line */
    static void decorate(MainActivity a, String id, Ui.Setting row, Recommend.State st) {
        String t = Recommend.tag(st);
        row.tag(a, t, "Changed".equals(t));
        row.note(st.changed() ? "Recommended: " + Prefs.recName(a, id, st.setting, st.recommended) : null);
    }

    static final String[] GPU_TS = {"segment", "submission"};

    private static String gpuTsName(String mode) { return mode.equals("segment") ? "Per segment" : "Per submission"; }

    static String gpuTsLabel(MainActivity a, String id) {
        String m = Prefs.gpuTs(a, id);
        Profile p = Profile.of(id);
        return m != null ? gpuTsName(m) : "Game default · " + gpuTsName(p == null ? "submission" : p.gpuTs);
    }

    /**
     * GPU timing: what the game's GPU timestamps measure (DM_NVN_GPU_TS; game.tsv gpu_timestamps is the default). Per segment: each timestamp gets the GPU time of
     * its place in the submission, so a game's dynamic resolution and frame pacing see the real GPU time; per submission: every timestamp of a submission gets
     * its completion time (the older behaviour)
     */
    private static void gpuTiming(MainActivity a, Game g, Runnable done) {
        Profile p = Profile.of(g.titleId);
        String[] labels = {"Game default (" + gpuTsName(p == null ? "submission" : p.gpuTs) + ")", "Per segment", "Per submission"};
        String m = Prefs.gpuTs(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GPU_TS).indexOf(m));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        box.addView(Ui.small(a, "Per segment: the game's timestamps see the real GPU time, so its dynamic resolution can adapt. Per submission: one time for the whole "
                + "submission (the older behaviour). A DM_NVN_GPU_TS line in env.txt wins."));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(checked + 1);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.gpuTs(a, g.titleId, w <= 1 ? null : GPU_TS[w - 2]);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, "GPU timing · " + g.name, box).setNegativeButton("Cancel", null).show();
    }

    /** The pack's scene_res rows (Pack.scenes), empty without a pack or from one that cannot be read */
    private static java.util.Map<String, String> packScenes(Game g) {
        File pd = g.packDir != null ? new File(g.packDir) : null;
        try { return pd != null ? Pack.read(pd).scenes : java.util.Map.of(); } catch (java.io.IOException | RuntimeException e) { return java.util.Map.of(); }
    }

    /** Game settings' value of Resolution: the preset in force with its window and 3D scene, Advanced with its two values; and Prepare again when the pack lacks the overlay */
    private static String presetValue(MainActivity a, Game g, List<ResPresets.Preset> ps, String rc, boolean simple) {
        if (ResPresets.ADVANCED.equals(rc)) return simple ? "Custom setup (window and 3D scene set apart)" : "Advanced: window and 3D scene apart";
        ResPresets.Preset p = ResPresets.of(ps, rc);
        if (p == null) return "";
        String l = ResPresets.label(ps, rc);
        File pd = g.packDir != null ? new File(g.packDir) : null;
        return !p.overlay() || SceneRes.overlay(packScenes(g), pd, p.id) != null ? l : l + " \u00b7 needs Prepare again";
    }

    /**
     * Resolution: the profile's presets (ResPresets), each setting the window and the 3D scene together and labelled by what the player gets (its second line: the window and
     * the scene's size, "about" until measured), and Advanced, which keeps Window and 3D scene apart for testers (Game settings then shows both). From the next start; the
     * in-game panel changes the window at once
     */
    private static void presets(MainActivity a, Game g, Runnable done) {
        List<ResPresets.Preset> ps = ResPresets.presets(Profile.of(g.titleId));
        if (ps.isEmpty()) return;
        java.util.Map<String, String> rows = packScenes(g);
        File pd = g.packDir != null ? new File(g.packDir) : null;
        String recPreset = Prefs.rec(a, g.titleId, Recommend.RES_PRESET);   // this phone's recommended preset (Prefs.recommended), named in its line
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        boolean simple = Prefs.simpleRes(a, g.titleId);
        box.addView(Ui.small(a, ResPresets.NOTE + " Menus and text follow the window. Sizes marked about are estimates. The 3D pictures are made from your game files when the game is prepared."));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k <= (simple ? ps.size() - 1 : ps.size()); k++) {   // the tester rows add Advanced after the presets
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            if (k < ps.size()) {
                ResPresets.Preset p = ps.get(k);
                boolean missing = p.overlay() && SceneRes.overlay(rows, pd, p.id) == null;
                rb.setText(ResPresets.itemText(p, k == 0, p.id.equals(recPreset)) + (missing ? " \u00b7 needs Prepare again" : ""));
            } else rb.setText("Advanced (for testers)\nwindow and 3D scene set apart");
            rb.setId(k + 1);
            rg.addView(rb);
        }
        String rc = Prefs.resolutionChoice(a, g.titleId);
        ResPresets.Preset cur = ResPresets.of(ps, rc);
        if (cur != null) rg.check(ps.indexOf(cur) + 1);
        else if (!simple) rg.check(ps.size() + 1);   // Advanced; a player's saved custom setup shows none checked
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.resPreset(a, g.titleId, w <= ps.size() ? ps.get(w - 1).id : ResPresets.ADVANCED);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, "Resolution \u00b7 " + g.name, box).setNegativeButton("Cancel", null).show();
    }

    /** Game settings' value of Advanced › 3D scene: the choice's label, and when the pack has no overlay for it (made before the option, or the pack step refused the palette), what to do */
    private static String sceneValue(MainActivity a, Game g, List<SceneRes.Preset> ps) {
        String choice = Prefs.sceneRes(a, g.titleId), label = SceneRes.label(ps, choice);
        SceneRes.Preset p = SceneRes.of(ps, choice);
        if (p == null || p.arg()) return label;   // game arguments (scene_arg) need nothing from Prepare
        File pd = g.packDir != null ? new File(g.packDir) : null;
        return SceneRes.overlay(ps, packScenes(g), pd, choice) != null ? label : label + " · needs Prepare again";
    }

    /**
     * Advanced › 3D scene (or 3D scene, for a game with scene_scale rows and no presets): the 3D scene drawn at a scale of the window crop, by the game's own dynamic resolution palette rewritten when the game was prepared (dm/tools/scene_res.py,
     * DM_ROMFS_OVERLAY); the crop, the menus and the upscaler stay as they are. Game default: the game's own palette. From the next start. For a game with scene_arg rows, the
     * scale the game's engine takes as arguments (DM_GAME_ARGS_ADD), nothing made at Prepare
     */
    private static void scene(MainActivity a, Game g, Runnable done) {
        List<SceneRes.Preset> ps = SceneRes.presets(Profile.of(g.titleId));
        if (ps.isEmpty()) return;
        String[] labels = new String[ps.size() + 1];
        labels[0] = "Game default (its own dynamic resolution)";
        for (int k = 0; k < ps.size(); k++) labels[k + 1] = ps.get(k).label;
        SceneRes.Preset cur = SceneRes.of(ps, Prefs.sceneRes(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        boolean args = ps.stream().allMatch(SceneRes.Preset::arg);
        box.addView(Ui.small(a, args
                ? "The 3D scene as a share of the picture, set through the game's own settings: higher is sharper and slower, menus and text stay sharp. "
                  + "Sizes are the most the game draws; under load its dynamic resolution lowers them. Applies at the next start. A DM_GAME_ARGS_ADD line in env.txt wins."
                : "The 3D scene as a scale of the window's upscaler output, before the upscaler takes it to the window: lower is faster and softer. Menus and text stay sharp. "
                  + "Made from your game files when the game is prepared; applies at the next start. A DM_ROMFS_OVERLAY line in env.txt wins."));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(a);
        for (int k = 0; k < labels.length; k++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setText(labels[k]);
            rb.setId(k + 1);
            rg.addView(rb);
        }
        rg.check(cur == null ? 1 : ps.indexOf(cur) + 2);
        rg.setOnCheckedChangeListener((x, w) -> {
            Prefs.sceneRes(a, g.titleId, w <= 1 ? null : ps.get(w - 2).name);
            keepAdvanced(a, g);
            d[0].dismiss();
            done.run();
        });
        Ui.add(box, rg, 8);
        d[0] = Ui.sheet(a, "3D scene · " + g.name, box).setNegativeButton("Cancel", null).show();
    }

    static String phoneTag() { return java.util.Locale.getDefault().toLanguageTag(); }

    /** Game language (DM_LANG, GameLanguage): the phone's language first (the default), then the console's languages; a game without the one picked uses one it has */
    static void language(android.app.Activity a, Game g, Runnable done) {   // the in-game panel's shortcut too
        String[] labels = new String[GameLanguage.CODES.length + 1];
        labels[0] = GameLanguage.label(null, phoneTag());
        System.arraycopy(GameLanguage.NAMES, 0, labels, 1, GameLanguage.NAMES.length);
        String m = Prefs.gameLang(a, g.titleId);
        int checked = m == null ? 0 : 1 + Math.max(0, Arrays.asList(GameLanguage.CODES).indexOf(m));
        new PadFocus.Builder(a).setTitle("Game language · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameLang(a, g.titleId, w == 0 ? null : GameLanguage.CODES[w - 1]);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    /** A choice made under Advanced (a game with presets) stores Advanced, so the choices are not taken for a preset they happen to match (ResPresets.current) */
    private static void keepAdvanced(MainActivity a, Game g) {
        if (!ResPresets.presets(Profile.of(g.titleId)).isEmpty()) Prefs.resPreset(a, g.titleId, ResPresets.ADVANCED);
    }

    /** The window list's title: Window under a game's Advanced (it has presets), Resolution otherwise */
    private static String windowTitle(Game g) { return ResPresets.presets(Profile.of(g.titleId)).isEmpty() ? "Resolution" : "Window"; }

    private static void resolution(MainActivity a, Game g, Runnable done) {
        Profile mp = Profile.of(g.titleId);
        String[] modes = mp != null ? mp.modes() : null;
        if (modes != null) {   // a game with one docked and one handheld row (Profile.modes): those two choices only, as Display shows its two
            new PadFocus.Builder(a).setTitle(windowTitle(g) + " · " + g.name).setSingleChoiceItems(new String[] {mp.modeLabel(0), mp.modeLabel(1)},
                    mp.modeOf(Prefs.gameWindow(a, g.titleId)), (x, w) -> {
                Prefs.gameWindow(a, g.titleId, modes[w]);
                keepAdvanced(a, g);
                x.dismiss();
                done.run();
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        String[] labels = new String[Prefs.WINDOWS.length + 1];
        labels[0] = "Default (" + Prefs.windowName(Prefs.defaultWindow(a, g.titleId), false) + ")";
        { Profile lp = Profile.of(g.titleId); for (int li = 0; li < Prefs.WINDOWS.length; li++) labels[li + 1] = lp != null ? lp.windowLabel(Prefs.WINDOWS[li], Prefs.WINDOW_NAMES[li]) : Prefs.WINDOW_NAMES[li]; }   // a choice the game draws at another size says so (Profile.windowLabel)
        int checked = Prefs.ownWindow(a, g.titleId) ? 1 + Math.max(0, Arrays.asList(Prefs.WINDOWS).indexOf(Prefs.gameWindow(a, g.titleId))) : 0;
        new PadFocus.Builder(a).setTitle(windowTitle(g) + " · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.gameWindow(a, g.titleId, w == 0 ? null : Prefs.WINDOWS[w - 1]);
            keepAdvanced(a, g);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    /** Display: Fit keeps the game's 16:9 picture with bars on a screen of another shape; Stretch fills the screen (DM_VK_STRETCH), the touch mapping with it */
    private static void display(MainActivity a, Game g, Runnable done) {
        new PadFocus.Builder(a).setTitle("Display · " + g.name).setSingleChoiceItems(Prefs.DISPLAY_NAMES, Prefs.stretch(a, g.titleId) ? 1 : 0, (x, w) -> {
            Prefs.stretch(a, g.titleId, w == 1);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void xbox(MainActivity a, Game g, Runnable done) {   // Default first: the game follows Settings again
        String[] labels = new String[Prefs.XBOX_NAMES.length + 1];
        labels[0] = "Default (Settings: " + Prefs.xboxLabel(Prefs.xboxButtons(a)) + ")";
        System.arraycopy(Prefs.XBOX_NAMES, 0, labels, 1, Prefs.XBOX_NAMES.length);
        int checked = Prefs.ownXboxButtons(a, g.titleId) ? Prefs.xboxButtons(a, g.titleId) + 1 : 0;
        new PadFocus.Builder(a).setTitle("Xbox button label swap · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.xboxButtons(a, g.titleId, w == 0 ? null : w - 1);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void cap(MainActivity a, Game g, Runnable done) {
        if (Prefs.limited(g.titleId)) {   // a frame limit, no locking (Prefs.PACE_CAPS)
            String[] labels = new String[Prefs.PACE_CAPS.length + 1];
            int pl = Prefs.profileLimit(g.titleId);   // the profile's own default (fps_limit), else the main screen's
            Integer rc = Prefs.recCap(a, g.titleId);
            labels[0] = rc != null ? "Recommended (" + Prefs.paceLabel(rc) + ")" : pl > 0 ? "Default (this game's: " + Prefs.paceLabel(Prefs.paceOf(pl)) + ")" : "Default (Settings: " + Prefs.paceLabel(Prefs.paceOf(Prefs.fpsCap(a))) + ")";
            int checked = 0, cur = Prefs.paceCap(a, g.titleId);
            for (int k = 0; k < Prefs.PACE_CAPS.length; k++) { labels[k + 1] = Prefs.paceLabel(Prefs.PACE_CAPS[k]); if (Prefs.ownFpsCap(a, g.titleId) && Prefs.PACE_CAPS[k] == cur) checked = k + 1; }
            new PadFocus.Builder(a).setTitle("Frame rate cap · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
                Prefs.fpsCap(a, g.titleId, w == 0 ? null : Prefs.PACE_CAPS[w - 1]);
                x.dismiss(); done.run();
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        int n = Prefs.FPS_CAPS.length;
        String[] labels = new String[n + 2];
        Integer rc = Prefs.recCap(a, g.titleId);
        labels[0] = firstLabel(rc != null ? MainActivity.capLabel(rc) : null, MainActivity.capLabel(Prefs.fpsCap(a)));
        labels[1] = MainActivity.capLabel(-1);
        int checked = 0;
        boolean own = Prefs.ownFpsCap(a, g.titleId);
        int cur = Prefs.fpsCap(a, g.titleId);
        if (own && cur < 0) checked = 1;
        for (int k = 0; k < n; k++) {
            labels[k + 2] = MainActivity.capLabel(Prefs.FPS_CAPS[k]);
            if (own && Prefs.FPS_CAPS[k] == cur) checked = k + 2;
        }
        new PadFocus.Builder(a).setTitle("Frame rate cap · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.fpsCap(a, g.titleId, w == 0 ? null : w == 1 ? -1 : Prefs.FPS_CAPS[w - 2]);
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void driver(MainActivity a, Game g, Runnable done) {
        List<GpuDriver.Info> inst = GpuDriver.installed(a);
        GpuDriver.Info def = GpuDriver.selected(a);
        List<String> labels = new ArrayList<>();
        String rd = Prefs.recommendedDriver(a, g.titleId);   // the first choice is this phone's recommended driver when there is one (Prefs.recommendedDriver)
        labels.add(rd != null ? "Recommended (" + Prefs.recName(a, g.titleId, Recommend.DRIVER, Recommend.SYSTEM.equals(rd) ? "system" : rd) + ")" : "Default (the main screen's: " + (def == null ? "System" : def.label()) + ")");
        labels.add("System (the phone's own driver)");
        String c = GpuDriver.choice(a, g.titleId);
        int checked = c == null ? 0 : c.equals(GpuDriver.SYSTEM) ? 1 : 0;
        for (int k = 0; k < inst.size(); k++) {
            labels.add(inst.get(k).label());
            if (c != null && inst.get(k).dir.getName().equals(c)) checked = k + 2;
        }
        new PadFocus.Builder(a).setTitle("GPU driver · " + g.name).setSingleChoiceItems(labels.toArray(new String[0]), checked, (x, w) -> {
            GpuDriver.choice(a, g.titleId, w == 0 ? null : w == 1 ? GpuDriver.SYSTEM : inst.get(w - 2).dir.getName());
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }

    private static void env(MainActivity a, Game g, Runnable done) {
        EditText t = new EditText(a);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        t.setMinLines(4);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(13);
        t.setHint("DM_WATCH_STALL=25");
        t.setText(Prefs.gameEnv(a, g.titleId));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.small(a, "KEY=VALUE lines for the host, one a line (# starts a comment). They come after env.txt and win over it, for " + g.name + " only."));
        Ui.add(box, t, 8);
        Ui.sheet(a, "Extra environment · " + g.name, box).setPositiveButton("Save", (x, w) -> { Prefs.gameEnv(a, g.titleId, t.getText().toString()); done.run(); })
                .setNeutralButton("Clear", (x, w) -> { Prefs.gameEnv(a, g.titleId, null); done.run(); }).setNegativeButton("Cancel", null).show();
    }

    static String dflt(boolean own, String v) { return v; }   // a row shows the value alone; the list's first choice says Default (Settings: …)

    /** The first choice of a list, which removes the game's own value: "Recommended (x)" where this phone has a recommendation (Prefs.recommended), else "Default (Settings: y)" */
    private static String firstLabel(String recName, String settingsValue) { return recName != null ? "Recommended (" + recName + ")" : "Default (Settings: " + settingsValue + ")"; }
    private static String recLabel(String[] names, String[] words, String recommended) { int i = Recommend.index(words, recommended); return i >= 0 && i < names.length ? names[i] : null; }

    /** A choice with firstLabel's choice first: picking it removes the game's own value (set(null)) */
    private static void choose(MainActivity a, String title, String[] items, String first, boolean own, int cur, java.util.function.Consumer<Integer> set, Runnable done) {
        String[] labels = new String[items.length + 1];
        labels[0] = first;
        System.arraycopy(items, 0, labels, 1, items.length);
        new PadFocus.Builder(a).setTitle(title).setSingleChoiceItems(labels, own ? cur + 1 : 0, (x, w) -> { set.accept(w == 0 ? null : w - 1); x.dismiss(); done.run(); })
                .setNegativeButton("Cancel", null).show();
    }

    private static void upscaler(MainActivity a, Game g, Runnable done) {   // the Upscaler (GameUpscaler: DM_VK_NDSR and the present's scaling); the in-game panel changes it live
        choose(a, "Upscaler · " + g.name, GameUpscaler.MODES, firstLabel(recLabel(GameUpscaler.MODES, Recommend.UPSCALERS, Prefs.rec(a, g.titleId, Recommend.UPSCALER)), GameUpscaler.MODES[GameUpscaler.mode(a)]), GameUpscaler.ownMode(a, g.titleId), GameUpscaler.mode(a, g.titleId),
               v -> GameUpscaler.mode(a, g.titleId, v), done);
    }

    private static void sharpness(MainActivity a, Game g, Runnable done) {   // FSR's RCAS sharpening (GameUpscaler, DM_VK_NDSR_SHARP)
        choose(a, "FSR sharpness · " + g.name, GameUpscaler.SHARP_NAMES, firstLabel(null, GameUpscaler.sharpName(GameUpscaler.sharp(a))), GameUpscaler.ownSharp(a, g.titleId),
               GameUpscaler.sharpIndex(GameUpscaler.sharp(a, g.titleId)), v -> GameUpscaler.sharp(a, g.titleId, v == null ? null : GameUpscaler.SHARPS[v]), done);
    }

    private static void dlssMode(MainActivity a, Game g, Runnable done) {   // the DLSS stand-in's mode (GameUpscaler.dlssMode, DM_VK_DLSS); the in-game panel changes it live
        choose(a, "Upscaler · " + g.name, DlssTune.MODE_NAMES, firstLabel(recLabel(DlssTune.MODE_NAMES, Recommend.DLSS_MODES, Prefs.rec(a, g.titleId, Recommend.UPSCALER)), DlssTune.MODE_NAMES[DlssTune.MODE_DEFAULT]), GameUpscaler.ownDlssMode(a, g.titleId), GameUpscaler.dlssMode(a, g.titleId),
               v -> GameUpscaler.dlssMode(a, g.titleId, v), done);
    }

    static final String CLARITY_LEFT = "Steady", CLARITY_RIGHT = "Clear";   // the Motion clarity slider's ends: more history (steadier) / less ghosting
    private static void clarity(MainActivity a, Game g, Runnable done) {   // the GSR2 stand-in's clarity step (DlssTune, DM_VK_GSR2_CLARITY); the in-game panel changes it live
        StepSlider.dialog(a, "Motion clarity", " · " + g.name, DlssTune.clarityNames(), GameUpscaler.clarity(a, g.titleId), CLARITY_LEFT, CLARITY_RIGHT,
                          DlssTune.clarityName(DlssTune.CLARITY_DEFAULT), GameUpscaler.ownClarity(a, g.titleId), v -> GameUpscaler.clarity(a, g.titleId, v), done);
    }
    private static void dlssSharp(MainActivity a, Game g, Runnable done) {   // the DLSS stand-in's RCAS (DlssTune, DM_VK_DLSS_SHARP; GSR 2's and FSR 1's); the in-game panel changes it live
        StepSlider.dialog(a, DlssTune.sharpTitle(GameUpscaler.dlssMode(a, g.titleId)), " · " + g.name, DlssTune.sharpNames(), GameUpscaler.dlssSharp(a, g.titleId), "Softer", "Sharper",
                          DlssTune.sharpName(DlssTune.SHARP_DEFAULT), GameUpscaler.ownDlssSharp(a, g.titleId), v -> GameUpscaler.dlssSharp(a, g.titleId, v), done);
    }

    private static void drs(MainActivity a, Game g, Runnable done) {   // the render size's range (Prefs.DRS), from the next start; the in-game panel changes it live
        DrsSlider.dialog(a, " · " + g.name, Prefs.drs(a, g.titleId), firstLabel(recLabel(Prefs.DRS_NAMES, Recommend.DRS_VALUES, Prefs.rec(a, g.titleId, Recommend.DRS)), Prefs.DRS_NAMES[Prefs.drs(a)]), Prefs.ownDrs(a, g.titleId), v -> Prefs.drs(a, g.titleId, v), done);
    }

    /** The profile's aspect variants' labels ("4:3"): its variant rows labelled w:h */
    static List<String> aspects(Profile p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        for (String v : p.variants.split(",")) { int eq = v.indexOf('='); if (eq > 0 && v.substring(eq + 1).matches("[0-9]+:[0-9]+")) out.add(v.substring(eq + 1)); }
        for (String v : p.cameraAspects.split(",")) if (v.matches("[0-9]+:[0-9]+") && !out.contains(v)) out.add(v);   // the host follows these with the game's 3D cameras (no module variant)
        if (!p.cameraAspects.isEmpty()) out.add(ScreenShape.CHOICE);   // last: the window's own shape, for a game that follows any shape
        return out;
    }

    static String aspectLabel(android.content.Context a, String id) {
        String l = Prefs.aspect(a, id);
        Profile p = Profile.of(id);
        if (ScreenShape.CHOICE.equals(l) && aspects(p).contains(l)) return "Screen shape";
        return l == null || !aspects(p).contains(l) ? "16:9 (as shipped)" : l + (p.aspectVariant(l) != null ? " (native)" : " (3D view)");
    }

    /** Aspect ratio: as shipped, or a native aspect mod (the pack holds both, so it takes effect at the next start without Prepare); 4:3 shows the picture at 4:3, filling a
     *  4:3 screen and with side bars on a wider one (Display › Stretch still fills the screen). Screen shape (a game whose cameras follow any shape) is the game window's own
     *  shape, taken at the start (ScreenShape), so the picture fills the screen */
    private static void aspect(MainActivity a, Game g, Runnable done) {
        List<String> as = aspects(Profile.of(g.titleId));
        String[] labels = new String[as.size() + 1];
        labels[0] = "16:9 (as shipped)";
        for (int k = 0; k < as.size(); k++) labels[k + 1] = ScreenShape.CHOICE.equals(as.get(k)) ? "Screen shape (the picture fills the window; applies at the next start)"
                    : as.get(k) + (Profile.of(g.titleId).aspectVariant(as.get(k)) != null ? " (native: cameras and menus laid out for " + as.get(k) + ")"
                    : " (3D view follows it; HUD and menus keep their 16:9 layout)");
        String cur = Prefs.aspect(a, g.titleId);
        int checked = cur == null ? 0 : Math.max(0, as.indexOf(cur) + 1);
        new PadFocus.Builder(a).setTitle("Aspect ratio · " + g.name).setSingleChoiceItems(labels, checked, (x, w) -> {
            Prefs.aspect(a, g.titleId, w == 0 ? null : as.get(w - 1));
            x.dismiss();
            done.run();
        }).setNegativeButton("Cancel", null).show();
    }
}
