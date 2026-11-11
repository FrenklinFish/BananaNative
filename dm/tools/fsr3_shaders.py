#!/usr/bin/env python3
"""Rebuilds the FSR 3.1 upscaler's SPIR-V in dm/gfx/vk_android/fsr3/*.h (vka_fsr3.c, DM_VK_DLSS=4) from AMD's FidelityFX SDK, unmodified, so anyone can check the committed shaders.
The SDK is pinned to tag v1.1.4 (FSR 3.1.4), commit SDK_COMMIT; its GLSL pass files (sdk/src/backends/vk/shaders/fsr3upscaler) are compiled with glslang against the SDK's
GPU headers (sdk/include/FidelityFX/gpu) with one set of options (FLAGS: no half precision but in reprojection, HDR colour, render-size motion vectors, reversed depth, the
reference Lanczos), plus the sharpening and the wave-free SPD variants (VARIANTS). Target SPIR-V 1.3 (vulkan1.1). Each header is glslang's --variable-name output with its
`#pragma once` line dropped and the array made static. The committed headers were made with glslang 16.6.0; another glslang version may emit different (equally valid) words.
Usage: fsr3_shaders.py [--sdk DIR] [--glslang PATH] [--check] [--out DIR]
  --sdk     a FidelityFX-SDK checkout at SDK_COMMIT (default: fetch only the needed folders of that commit with git into a temporary directory)
  --glslang the glslang (or glslangValidator) binary (default: from PATH)
  --check   compile and compare with the committed headers; write nothing, exit 1 if one differs
  --out     where the headers go (default: dm/gfx/vk_android/fsr3)"""
import argparse, os, shutil, subprocess, sys, tempfile

SDK_URL = 'https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git'
SDK_TAG = 'v1.1.4'
SDK_COMMIT = 'c6efa6bf7f2027b3ec94f28578bb5965eabb9e55'
GLSLANG_VERSION = '16.6.0'
GPU_DIR = 'sdk/include/FidelityFX/gpu'
PASS_DIR = 'sdk/src/backends/vk/shaders/fsr3upscaler'

FLAGS = [
    '-DFFX_GPU=1', '-DFFX_GLSL=1',
    '-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_SAMPLERS_USE_DATA_HALF=0',
    '-DFFX_FSR3UPSCALER_OPTION_ACCUMULATE_SAMPLERS_USE_DATA_HALF=0',
    '-DFFX_FSR3UPSCALER_OPTION_REPROJECT_SAMPLERS_USE_DATA_HALF=1',
    '-DFFX_FSR3UPSCALER_OPTION_POSTPROCESSLOCKSTATUS_SAMPLERS_USE_DATA_HALF=0',
    '-DFFX_FSR3UPSCALER_OPTION_UPSAMPLE_USE_LANCZOS_TYPE=2',
    '-DFFX_FSR3UPSCALER_OPTION_REPROJECT_USE_LANCZOS_TYPE=0',
    '-DFFX_FSR3UPSCALER_OPTION_HDR_COLOR_INPUT=1',
    '-DFFX_FSR3UPSCALER_OPTION_LOW_RESOLUTION_MOTION_VECTORS=1',
    '-DFFX_FSR3UPSCALER_OPTION_JITTERED_MOTION_VECTORS=0',
    '-DFFX_FSR3UPSCALER_OPTION_INVERTED_DEPTH=1',
]
NO_SHARPEN = '-DFFX_FSR3UPSCALER_OPTION_APPLY_SHARPENING=0'
NO_WAVE = '-DFFX_SPD_NO_WAVE_OPERATIONS=1'
# (header name, the SDK pass it compiles, its extra defines)
VARIANTS = [
    ('prepare_inputs', 'prepare_inputs', []),
    ('luma_pyramid', 'luma_pyramid', []),
    ('luma_pyramid_nowave', 'luma_pyramid', [NO_WAVE]),
    ('shading_change_pyramid', 'shading_change_pyramid', []),
    ('shading_change_pyramid_nowave', 'shading_change_pyramid', [NO_WAVE]),
    ('shading_change', 'shading_change', []),
    ('prepare_reactivity', 'prepare_reactivity', []),
    ('luma_instability', 'luma_instability', []),
    ('accumulate', 'accumulate', []),
    ('accumulate_sharpen', 'accumulate', ['-DFFX_FSR3UPSCALER_OPTION_APPLY_SHARPENING=1']),
    ('rcas', 'rcas', []),
]

def git(*args, cwd=None):
    return subprocess.run(['git', *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

def fetch_sdk(dest):
    """Only the two folders the passes need, at SDK_COMMIT (a blobless sparse checkout: a few MB instead of the whole SDK)."""
    git('init', '-q', dest)
    git('remote', 'add', 'origin', SDK_URL, cwd=dest)
    git('sparse-checkout', 'set', '--no-cone', GPU_DIR, PASS_DIR, cwd=dest)
    git('fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', 'refs/tags/' + SDK_TAG, cwd=dest)
    git('checkout', '-q', 'FETCH_HEAD', cwd=dest)

def header(glslang, sdk, name, pass_name, defines, tmp):
    gpu = os.path.join(sdk, GPU_DIR)
    var = f'FSR3_UPSCALER_{name}_COMP_SPV'.upper()
    out = os.path.join(tmp, name + '.h')
    sharpen = [] if any('APPLY_SHARPENING' in d for d in defines) else [NO_SHARPEN]
    cmd = [glslang, '-V', '--quiet', '-S', 'comp', '--target-env', 'vulkan1.1', '-I' + gpu, '-I' + os.path.join(gpu, 'fsr3upscaler'), *FLAGS, *defines, *sharpen,
           '--variable-name', var, '-o', out, os.path.join(sdk, PASS_DIR, f'ffx_fsr3upscaler_{pass_name}_pass.glsl')]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: sys.exit(f'fsr3_shaders: {name}: glslang failed:\n{r.stdout}{r.stderr}')
    with open(out, encoding='utf-8') as f: lines = f.read().splitlines(keepends=True)
    return ''.join('static ' + l if l.startswith('const uint32_t') else l for l in lines if l.strip() != '#pragma once')

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--sdk'); ap.add_argument('--glslang'); ap.add_argument('--check', action='store_true')
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gfx', 'vk_android', 'fsr3'))
    a = ap.parse_args(argv)
    glslang = a.glslang or shutil.which('glslang') or shutil.which('glslangValidator')
    if not glslang: sys.exit('fsr3_shaders: glslang not found (--glslang PATH)')
    ver = subprocess.run([glslang, '--version'], capture_output=True, text=True).stdout
    if GLSLANG_VERSION not in ver: print(f'fsr3_shaders: warning: glslang is not {GLSLANG_VERSION}; the words may differ from the committed ones', file=sys.stderr)
    with tempfile.TemporaryDirectory() as tmp:
        sdk = a.sdk
        if not sdk:
            sdk = os.path.join(tmp, 'sdk'); print(f'fsr3_shaders: fetching FidelityFX SDK {SDK_TAG}', file=sys.stderr); fetch_sdk(sdk)
        if os.path.isdir(os.path.join(sdk, '.git')):
            head = git('rev-parse', 'HEAD', cwd=sdk)
            if head != SDK_COMMIT: sys.exit(f'fsr3_shaders: the SDK is at {head}, not {SDK_TAG} ({SDK_COMMIT})')
        differ = 0
        for name, pass_name, defines in VARIANTS:
            text = header(glslang, sdk, name, pass_name, defines, tmp)
            path = os.path.join(a.out, f'fsr3_{name}.h')
            old = open(path, encoding='utf-8').read() if os.path.exists(path) else None
            if a.check:
                same = old == text; differ += not same
                print(f"{'same' if same else 'DIFFERS'}  fsr3_{name}.h")
            elif old != text:
                with open(path, 'w', encoding='utf-8', newline='') as f: f.write(text)
                print(f'wrote fsr3_{name}.h')
        if a.check and differ: sys.exit(f'fsr3_shaders: {differ} header(s) differ from the SDK build')
    return 0

if __name__ == '__main__':
    sys.exit(main())
