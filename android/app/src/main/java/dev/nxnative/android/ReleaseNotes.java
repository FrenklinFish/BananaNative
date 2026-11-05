package dev.nxnative.android;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's notes (its GitHub body, Markdown) as blocks of plain text with style ranges, plain Java (no Android; checked on the JVM by
 * dm/tools/tests/test_android_update.py with UpdateCheckJvmTest). Updater turns them into styled text for the update popup. The subset GitHub release notes
 * use: headings (#, ##, ###), paragraphs (lines joined, a blank line between), bullet (- * +) and numbered (1. 1)) lists with their nesting, **bold** / __bold__,
 * *italic* / _italic_, `code`, links [text](url) (their text), backslash escapes; HTML comments, images and rules (---) are left out.
 */
final class ReleaseNotes {
    private ReleaseNotes() {}

    enum Kind { HEADING, PARAGRAPH, BULLET, NUMBER }
    enum Style { BOLD, ITALIC, CODE, LINK }

    /** A style over text[start, end); url for LINK */
    static final class Span {
        final Style style; final int start, end; final String url;
        Span(Style style, int start, int end, String url) { this.style = style; this.start = start; this.end = end; this.url = url; }
        @Override public String toString() { return style + "[" + start + "," + end + ")" + (url != null ? url : ""); }
    }

    /** One block: level is the heading's level (1-6), or a list item's depth (0 = top level); label is a numbered item's "1." */
    static final class Block {
        final Kind kind; final int level; final String label, text; final List<Span> spans;
        Block(Kind kind, int level, String label, String text, List<Span> spans) { this.kind = kind; this.level = level; this.label = label; this.text = text; this.spans = spans; }
    }

    private static final int MAX = 20000;   // a body longer than this is cut (GitHub allows 125,000 characters)
    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.*?)\\s*#*\\s*");
    private static final Pattern BULLET = Pattern.compile("(\\s*)[-*+]\\s+(.*)");
    private static final Pattern NUMBER = Pattern.compile("(\\s*)(\\d{1,9})([.)])\\s+(.*)");
    private static final Pattern RULE = Pattern.compile("\\s*([-*_])(\\s*\\1){2,}\\s*");

    static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        if (md == null) return out;
        String s = md.length() > MAX ? md.substring(0, MAX) : md;
        s = s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("(?s)<!--.*?(-->|$)", "");
        Kind kind = null; int level = 0; String label = null; StringBuilder text = null;
        for (String line : s.split("\n", -1)) {
            Matcher m;
            if (line.trim().isEmpty() || RULE.matcher(line).matches()) {
                flush(out, kind, level, label, text); kind = null; text = null;
            } else if ((m = HEADING.matcher(line.trim())).matches() && indent(line) < 4) {
                flush(out, kind, level, label, text);
                flush(out, Kind.HEADING, m.group(1).length(), null, new StringBuilder(m.group(2)));
                kind = null; text = null;
            } else if ((m = BULLET.matcher(line)).matches()) {
                flush(out, kind, level, label, text);
                kind = Kind.BULLET; level = depth(m.group(1)); label = null; text = new StringBuilder(m.group(2));
            } else if ((m = NUMBER.matcher(line)).matches()) {
                flush(out, kind, level, label, text);
                kind = Kind.NUMBER; level = depth(m.group(1)); label = m.group(2) + m.group(3); text = new StringBuilder(m.group(4));
            } else if (text != null) {
                text.append(' ').append(line.trim());   // the paragraph's or the list item's next line
            } else {
                kind = Kind.PARAGRAPH; level = 0; label = null; text = new StringBuilder(line.trim());
            }
        }
        flush(out, kind, level, label, text);
        return out;
    }

    private static int indent(String line) { int k = 0; while (k < line.length() && line.charAt(k) == ' ') k++; return k; }

    /** Two spaces (or a tab) a level, as GitHub nests lists */
    private static int depth(String ws) { return Math.min(4, ws.replace("\t", "    ").length() / 2); }

    private static void flush(List<Block> out, Kind kind, int level, String label, StringBuilder md) {
        if (kind == null || md == null) return;
        StringBuilder t = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        inline(md.toString().trim(), t, spans);
        String text = t.toString().trim();
        if (text.isEmpty() && kind != Kind.BULLET && kind != Kind.NUMBER) return;   // a paragraph that was only an image or a comment
        out.add(new Block(kind, level, label, text, spans));
    }

    /** Inline Markdown of s appended to t as plain text, its styles to spans (positions in t) */
    static void inline(String s, StringBuilder t, List<Span> spans) {
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_[]()#+-.!<>".indexOf(s.charAt(i + 1)) >= 0) { t.append(s.charAt(i + 1)); i += 2; continue; }
            if (c == '`') {
                int run = run(s, i, '`'), close = s.indexOf(s.substring(i, i + run), i + run);
                if (close > 0) {
                    int st = t.length();
                    t.append(s, i + run, close);
                    spans.add(new Span(Style.CODE, st, t.length(), null));
                    i = close + run;
                    continue;
                }
                t.append(s, i, i + run); i += run; continue;
            }
            if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[') {   // an image: left out
                int[] l = link(s, i + 1);
                if (l != null) { i = l[3]; continue; }
            }
            if (c == '[') {
                int[] l = link(s, i);
                if (l != null) {
                    int st = t.length();
                    inline(s.substring(i + 1, l[0]), t, spans);
                    spans.add(new Span(Style.LINK, st, t.length(), s.substring(l[1], l[2]).trim()));
                    i = l[3];
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c && opens(s, i, 2)) {
                int close = closing(s, i + 2, String.valueOf(c) + c);
                if (close > i + 2) {
                    int st = t.length();
                    inline(s.substring(i + 2, close), t, spans);
                    spans.add(new Span(Style.BOLD, st, t.length(), null));
                    i = close + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && opens(s, i, 1)) {
                int close = closing(s, i + 1, String.valueOf(c));
                if (close > i + 1) {
                    int st = t.length();
                    inline(s.substring(i + 1, close), t, spans);
                    spans.add(new Span(Style.ITALIC, st, t.length(), null));
                    i = close + 1;
                    continue;
                }
            }
            t.append(c);
            i++;
        }
    }

    private static int run(String s, int i, char c) { int k = i; while (k < s.length() && s.charAt(k) == c) k++; return k - i; }

    /** A marker of width w at i opens an emphasis: something other than a space follows it, and an underscore does not stand inside a word (snake_case) */
    private static boolean opens(String s, int i, int w) {
        if (i + w >= s.length() || Character.isWhitespace(s.charAt(i + w))) return false;
        return s.charAt(i) != '_' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
    }

    /** The closing marker after from (not inside `code`, not after a space, an underscore not inside a word), or -1 */
    private static int closing(String s, int from, String marker) {
        char c = marker.charAt(0);
        for (int k = from; k < s.length(); k++) {
            char x = s.charAt(k);
            if (x == '\\') { k++; continue; }
            if (x == '`') { int r = run(s, k, '`'), close = s.indexOf(s.substring(k, k + r), k + r); if (close > 0) { k = close + r - 1; continue; } }
            if (!s.startsWith(marker, k) || Character.isWhitespace(s.charAt(k - 1))) continue;
            int after = k + marker.length();
            if (marker.length() == 1 && after < s.length() && s.charAt(after) == c) { k = after; continue; }   // part of a ** run: not a single closing *
            if (c == '_' && after < s.length() && Character.isLetterOrDigit(s.charAt(after))) continue;
            return k;
        }
        return -1;
    }

    /** [text](url) at i: { the ']' index, url start, url end, the index after ')' }, or null */
    private static int[] link(String s, int i) {
        int depth = 0;
        for (int k = i; k < s.length(); k++) {
            char x = s.charAt(k);
            if (x == '\\') { k++; continue; }
            if (x == '[') depth++;
            else if (x == ']' && --depth == 0) {
                if (k + 1 >= s.length() || s.charAt(k + 1) != '(') return null;
                int close = s.indexOf(')', k + 2);
                if (close < 0) return null;
                int urlEnd = close;
                int q = s.indexOf(" \"", k + 2);   // [text](url "title")
                if (q > 0 && q < close) urlEnd = q;
                return new int[] {k, k + 2, urlEnd, close + 1};
            }
        }
        return null;
    }

    /** The notes as plain text (for logs and tests): headings and paragraphs, "• " or "1. " before list items, two spaces a level */
    static String plain(List<Block> bs) {
        StringBuilder b = new StringBuilder();
        for (Block x : bs) {
            if (b.length() > 0) b.append('\n');
            if (x.kind == Kind.BULLET || x.kind == Kind.NUMBER) b.append("  ".repeat(x.level)).append(x.kind == Kind.BULLET ? "• " : x.label + " ");
            b.append(x.text);
        }
        return b.toString();
    }
}
