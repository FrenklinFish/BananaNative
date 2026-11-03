// nxreplay: the frame replayer on the phone, run from adb shell with libnxhost.so beside it (android/TESTING.md "Frame capture and replay"). Everything is in the host
// library (dm/gfx/vk_android/vka_replay.c, nxhost_replay); this is only its entry point
int nxhost_replay(int argc, char **argv);
int main(int argc, char **argv) { return nxhost_replay(argc, argv); }
