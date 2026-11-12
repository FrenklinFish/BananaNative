// Game arguments added at run time: DM_GAME_ARGS_ADD's words, split at spaces, go after those of the command line or the game profile (main.c). The Android launcher passes
// an engine's own settings this way (profile key scene_arg: Star Fox's Viper engine takes -<cvar with '.' as '_'>=<value> for any of its settings).
#pragma once
#include <string.h>

// Appends the words of s (split in place at spaces and tabs) to argv[argc..max-1] and returns the new count; words past max are dropped. argv[max] stays free for a NULL.
static inline int game_args_add(char **argv, int argc, int max, char *s) {
    if (!s) return argc;
    for (char *t = strtok(s, " \t"); t && argc < max; t = strtok(NULL, " \t")) argv[argc++] = t;
    return argc;
}
