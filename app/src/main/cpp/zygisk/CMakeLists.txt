# Zygisk injector for QAuxiliary.
#
# This library is NOT shipped inside the APK (see packaging.jniLibs.excludes in
# app/build.gradle.kts). The Gradle task `prepareZygiskModule<Variant>` picks it
# up from the CMake obj output, strips it with the NDK's llvm-strip and stages it
# as `zygisk/arm64-v8a.so` inside the Magisk/KernelSU/APatch module.
#
# Zygisk modules are always loaded by the zygote, so this target is arm64-only.

find_library(ZYGISK_LOG_LIB log)

add_library(qauxv-zygisk SHARED
        zygisk_entry.cpp
        payload.cpp
        art_hook.cpp
        elf_util.cpp
        trampoline.cpp
        jni_bridge.cpp
        log_file.cpp
        plt_hook.cpp
)

target_compile_features(qauxv-zygisk PRIVATE cxx_std_20)

target_compile_definitions(qauxv-zygisk PRIVATE
        QAUXV_ZYGISK_MODULE=1
)

target_compile_options(qauxv-zygisk PRIVATE
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -Wall
        -Wno-unused-parameter
        # The injector deliberately pokes at ART internals; the layout probes are
        # done at runtime, but the casts involved trip these warnings.
        -Wno-invalid-offsetof
)

target_link_options(qauxv-zygisk PRIVATE
        -Wl,--gc-sections
        # Zygisk modules are dlopen-ed by the zygote; some symbols are only
        # resolved at load time. Relax the strict flags inherited from the
        # top-level CMakeLists.
        -Wl,--allow-shlib-undefined
        -Wl,-z,undefs
)

target_include_directories(qauxv-zygisk PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(qauxv-zygisk
        ${ZYGISK_LOG_LIB}
)

# The .so is re-stripped by Gradle when staging the module; stripping here too
# keeps the intermediate artefact small and matches what CI produces.
add_custom_command(TARGET qauxv-zygisk POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:qauxv-zygisk>)
