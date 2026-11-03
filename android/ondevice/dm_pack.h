// The on-device packer's entry point (android/ONDEVICE.md): the repository's Python tools (pack_android.py, patch_nso.py, shader_split.py, ...) run unchanged
// in CPython 3.14 embedded in the calling process, one tool at a time, each as `python3 <tool> <args>` would run it.
//
// Library: libnxpack.so (android/ondevice/build.sh), needs libpython3.14.so (loaded first, or found by the dynamic linker).
// The Python home is a folder with lib/python3.14/ (the standard library, lib-dynload/ and site-packages/ with capstone and lz4) as build.sh stages it;
// the app copies it from its assets into its private storage (extension modules are loaded with dlopen from there; nothing is executed as a program).
//
// Threads: any thread may call dm_pack_run; runs are serialized (one tool at a time per process, they share one interpreter). A tool must not fork:
// pass DM_REWRITE_J=1 to patch_nso.py and J=1 to shader_split.py (pack_android.py --in-process does both).
#ifndef DM_PACK_H
#define DM_PACK_H
#ifdef __cplusplus
extern "C" {
#endif

// Starts Python once in this process with home as its prefix. Returns 0, or -1 with the reason in the log (stderr). A second call is a no-op returning 0
// (Python cannot be restarted in a process: a different home needs a new process).
int dm_pack_init(const char *python_home);

// Runs the tool (a .py path) with sys.argv = [tool, argv[0..argc-1]] in a fresh __main__ namespace (runpy.run_path), the tool's folder first on sys.path,
// as the command line does. env: NULL or a NULL-terminated list of "KEY=VALUE" set in os.environ for this run only (restored after).
// log_path: the tool's stdout and stderr are appended there (NULL: Python's own sys.stdout / sys.stderr).
// Returns the tool's exit code: 0, SystemExit's code (a message as sys.exit('...') gives prints it to the log and returns 1), 1 on an uncaught exception
// (its traceback in the log), -1 when Python is not started. Modules the tool imported (outside the standard library) are dropped after the run, so the next
// run imports the tools afresh.
int dm_pack_run(const char *tool, int argc, char **argv, const char *const *env, const char *log_path);

// Asks the run in hand to stop: a KeyboardInterrupt raised in its thread at its next bytecode (PyThreadState_SetAsyncExc), after which dm_pack_run returns 130.
// Any thread; waits for the GIL (milliseconds, longer while the tool is in a C call that holds it), so the app calls it off its main thread.
// Returns 1 when a run was interrupted, 0 when none was running.
int dm_pack_interrupt(void);

// JNI form for the app (class dev.nxnative.android.PackNative, the app's side owns it):
//   static native int init0(String pythonHome);
//   static native int run0(String tool, String[] args, String[] env, String logPath);   // env: "KEY=VALUE" or null; logPath: null for none
//   static native int interrupt0();
// They are dm_pack_init / dm_pack_run / dm_pack_interrupt; run0 blocks until the tool ends (call it from a worker thread of the job service).
// Python's os.environ is read once, at dm_pack_init: a variable the app sets later (Os.setenv) reaches a tool only through run0's env.

#ifdef __cplusplus
}
#endif
#endif
