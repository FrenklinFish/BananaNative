package dev.nxnative.android;

import java.util.Locale;

/**
 * The game's language (Game settings › Game language): one of the console's language codes (nn::settings::LanguageCode), passed to the host as DM_LANG, or the
 * phone's language (the default), mapped to the console's code as the host maps DM_LANG (hle_oe.c dm_lang_code: fr-FR is fr, es-MX is es-419, zh-TW is zh-Hant).
 * A phone language the console has no code for sets nothing: the host's default, en-US. A DM_LANG line of env.txt or the game's extra environment comes later and
 * wins. Whether the game has that language is the game's own matter (it falls back to one it has). No Android classes: GameLanguageJvmTest runs it.
 */
final class GameLanguage {
    private GameLanguage() {}

    /** The console's language codes, in the order the picker lists them, and their names */
    static final String[] CODES = {"en-US", "en-GB", "ja", "fr", "fr-CA", "de", "it", "es", "es-419", "nl", "pt", "pt-BR", "ru", "ko", "zh-Hans", "zh-Hant"};
    static final String[] NAMES = {"English (US)", "English (UK)", "Japanese", "French", "French (Canada)", "German", "Italian", "Spanish (Spain)", "Spanish (Latin America)",
            "Dutch", "Portuguese", "Portuguese (Brazil)", "Russian", "Korean", "Chinese (Simplified)", "Chinese (Traditional)"};

    /** The console's code for a BCP-47 language tag (Locale.toLanguageTag: "fr-FR", "zh-Hant-TW"), null when the console has no such language */
    static String code(String tag) {
        if (tag == null) return null;
        String[] p = tag.replace('_', '-').split("-");
        String lang = p[0].toLowerCase(Locale.ROOT), script = "", region = "";
        for (int i = 1; i < p.length; i++) {
            if (p[i].length() == 4 && script.isEmpty() && region.isEmpty()) script = p[i].toLowerCase(Locale.ROOT);
            else if ((p[i].length() == 2 || p[i].length() == 3) && region.isEmpty()) region = p[i].toLowerCase(Locale.ROOT);
        }
        switch (lang) {
            case "ja": return "ja";
            case "en": return region.isEmpty() || region.equals("us") || region.equals("ca") || region.equals("ph") ? "en-US" : "en-GB";
            case "fr": return region.equals("ca") ? "fr-CA" : "fr";
            case "de": return "de";
            case "it": return "it";
            case "es": return region.isEmpty() || region.equals("es") ? "es" : "es-419";
            case "nl": return "nl";
            case "pt": return region.equals("br") ? "pt-BR" : "pt";
            case "ru": return "ru";
            case "ko": return "ko";
            case "zh": return script.equals("hant") || script.isEmpty() && (region.equals("tw") || region.equals("hk") || region.equals("mo")) ? "zh-Hant" : "zh-Hans";
            default: return null;
        }
    }

    static String name(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code;
    }

    /** The DM_LANG line for a choice (null: the phone's language; else a code of CODES), null when nothing is set (the phone's language has no console code) */
    static String env(String choice, String phoneTag) {
        String c = choice != null ? choice : code(phoneTag);
        return c == null ? null : "DM_LANG=" + c;
    }

    /** The setting's value line: "Phone's language · French", "Phone's language · none, English (US)", or the chosen language */
    static String label(String choice, String phoneTag) {
        if (choice != null) return name(choice);
        String c = code(phoneTag);
        return "Phone's language · " + (c != null ? name(c) : "not a console language, so English (US)");
    }
}
