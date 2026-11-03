# libadrenotools (vendored)

[libadrenotools](https://github.com/bylaws/libadrenotools) by Billy Laws (BSD 2-Clause, `LICENSE`), as Eden uses it
(`eden-emulator/libadrenotools` at `8ba23b42d7`), with [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass)
(`aa3975893d`, BSD 2-Clause, `lib/linkernsbypass/LICENSE`) in `lib/linkernsbypass/`.

Only the files the Android host needs to load a custom Vulkan driver (`ADRENOTOOLS_DRIVER_CUSTOM`) are here, unmodified:
`src/driver.cpp`, `include/adrenotools/{driver,priv}.h`, the hook libraries' sources `src/hook/{hook_impl.cpp,hook_impl.h,hook_impl_params.h,main_hook.c}`
and the kgsl UAPI header `src/hook/kgsl.h` (Linux UAPI, `GPL-2.0 WITH Linux-syscall-note`, as its SPDX line says). Left out: the BCn enabler, the file-redirect and
GSL-allocation hooks, the tools.

`android/host/CMakeLists.txt` builds `libnxadreno.so` (driver.cpp + linkernsbypass) and the two hook libraries `libhook_impl.so` and `libmain_hook.so`. The host
(`dm/gfx/vk_android/vka_driver.c`) opens `libnxadreno.so` only when `DM_VK_DRIVER` names a driver, so the system driver path loads none of this code
(linkernsbypass's constructor changes the protection of `dlopen`'s page).
