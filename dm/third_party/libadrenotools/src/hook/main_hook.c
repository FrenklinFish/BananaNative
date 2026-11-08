#include "hook_impl.h"

__attribute__((visibility("default"))) void *android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo) {
	return hook_android_dlopen_ext(filename, flags, extinfo);
}

__attribute__((visibility("default"))) void *android_load_sphal_library(const char *filename, int flags) {
	return hook_android_load_sphal_library(filename, flags);
}

// Android 16 (API 36) Binder NDK stub for Qualcomm 891.x custom drivers running on Android 15 (API 35)
__attribute__((visibility("default"))) void AIBinder_Class_setTransactionCodeToFunctionNameMap(void *clazz, void *map) {
	(void)clazz; (void)map;
}