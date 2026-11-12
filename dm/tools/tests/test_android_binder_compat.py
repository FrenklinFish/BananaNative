"""The driver-namespace Binder annotation shim: an absent API is optional, while
an available API receives every argument, including the length, unchanged."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'android/host/adreno_binder_compat.c'


@unittest.skipUnless(shutil.which('clang'), 'no clang')
class BinderCompat(unittest.TestCase):
    def test_annotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / 'test.c'
            src.write_text(r'''
#include <assert.h>
#include <dlfcn.h>
#include <stddef.h>
#include <string.h>
static int available, opens, resolves, calls;
static void *seen_class;
static const char *const *seen_names;
static size_t seen_length;
static void annotation(void *clazz, const char *const *names, size_t length) {
    calls++; seen_class = clazz; seen_names = names; seen_length = length;
}
static void *probe_open(const char *name, int flags) {
    assert(!strcmp(name, "libbinder_ndk.so"));
    assert(flags == (RTLD_NOW | RTLD_LOCAL));
    opens++; return (void *)1;
}
static void *probe_symbol(void *lib, const char *name) {
    assert(lib == (void *)1);
    assert(!strcmp(name, "AIBinder_Class_setTransactionCodeToFunctionNameMap"));
    resolves++; return available ? (void *)annotation : NULL;
}
#define dlopen probe_open
#define dlsym probe_symbol
''' + '#include "' + str(SOURCE) + '"\n' + r'''
int main(int argc, char **argv) {
    available = argc > 1;
    const char *names[] = {"first", NULL, "third"};
    AIBinder_Class *clazz = (AIBinder_Class *)(void *)names;
    AIBinder_Class_setTransactionCodeToFunctionNameMap(clazz, names, 3);
    AIBinder_Class_setTransactionCodeToFunctionNameMap(clazz, names, 1);
    assert(opens == 1 && resolves == 1);
    assert(calls == (available ? 2 : 0));
    if (available) assert(seen_class == clazz && seen_names == names && seen_length == 1);
    return 0;
}
''')
            exe = Path(tmp) / 'test'
            subprocess.run(['clang', '-std=c11', '-Wall', '-Wextra', '-Werror',
                            '-Wno-unused-parameter', '-pthread', str(src), '-o', str(exe)],
                           check=True, capture_output=True, text=True)
            for args in ([], ['available']):
                subprocess.run([str(exe), *args], check=True, capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
