// AAC for the movie decoder (hle_movie.c) on Android: the phone's AMediaCodec "audio/mp4a-latm" decoder, fed raw AAC frames with the AudioSpecificConfig as csd-0
#pragma once
#include <stdint.h>
#include <stddef.h>

typedef struct maac maac;
// rate / ch: the track's (Configure's MediaData), 0 when not known; filled from the AudioSpecificConfig, or 48 kHz stereo. Without a configuration an AAC-LC one is made from them.
// NULL when the phone has no AAC decoder or it refuses the configuration
maac *maac_new(int *rate, int *ch, const uint8_t *asc, int asc_n);
void maac_free(maac *m);
// One AAC frame in; PCM16 interleaved in the track's channel count out, at most max_vals values (one output's worth). Returns the values written: 0 while the decoder is still
// priming. Decoded samples beyond max_vals wait for the next call
int maac_decode(maac *m, const uint8_t *p, int n, int64_t pts_us, int16_t *out, int max_vals);
