// game_args_add (game_args.h): DM_GAME_ARGS_ADD's words after the game's other arguments. No game files.
#include "../game_args.h"
#include <assert.h>
#include <stdio.h>

int main(void) {
    char *argv[8] = {"game", "-a"};
    char s1[] = "-render_resolution_scale_console=0.75  -render_resolution_scale_handheld=0.75\t";
    int n = game_args_add(argv, 2, 7, s1);
    assert(n == 4 && !strcmp(argv[2], "-render_resolution_scale_console=0.75") && !strcmp(argv[3], "-render_resolution_scale_handheld=0.75"));   // after the profile's, extra spaces and a tab skipped
    char s2[] = "   ";
    assert(game_args_add(argv, n, 7, s2) == n);   // only spaces: nothing
    assert(game_args_add(argv, n, 7, NULL) == n);   // unset
    char s3[] = "x1 x2 x3 x4";
    n = game_args_add(argv, n, 7, s3);
    assert(n == 7 && !strcmp(argv[6], "x3") && argv[7] == NULL);   // room for three more only: the rest dropped, the last slot stays free
    puts("t_game_args: ok");
    return 0;
}
