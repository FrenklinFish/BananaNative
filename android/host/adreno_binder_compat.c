// Qualcomm 891.x imports this API 36 tracing annotation on API 35. libadrenotools
// loads hook_impl into each vendor-driver namespace, whereas main_hook lives in
// the Vulkan loader's separate namespace: its existing stub cannot satisfy the
// driver's import. Export the annotation here (DF_1_GLOBAL), without changing the
// vendored loader or the driver. No Binder transactions are replaced.
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>

typedef struct AIBinder_Class AIBinder_Class;
typedef void (*set_names_fn)(AIBinder_Class *, const char *const *, size_t);
static set_names_fn system_set_names;
static pthread_once_t names_once = PTHREAD_ONCE_INIT;

static void find_system_set_names(void) {
    // Keep the library loaded for the function pointer's lifetime. On API 36+
    // preserve the system's annotation; older systems trace transaction numbers.
    void *lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (lib) system_set_names = (set_names_fn)dlsym(lib, "AIBinder_Class_setTransactionCodeToFunctionNameMap");
}

__attribute__((visibility("default")))
void AIBinder_Class_setTransactionCodeToFunctionNameMap(AIBinder_Class *clazz, const char *const *names, size_t length) {
    pthread_once(&names_once, find_system_set_names);
    if (system_set_names) system_set_names(clazz, names, length);
}
