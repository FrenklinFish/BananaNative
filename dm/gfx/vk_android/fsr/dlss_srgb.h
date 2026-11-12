// NVN2's DLSS stand-in (vka_dlss.c): the sRGB encoding (IEC 61966-2-1) of a linear colour for an sRGB output texture, which the replay stores as its UNORM twin
vec3 dlss_srgb_enc(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, 12.92 * c, lessThanEqual(c, vec3(0.0031308)));
}
