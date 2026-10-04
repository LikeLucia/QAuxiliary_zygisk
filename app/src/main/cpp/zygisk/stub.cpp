// Stub for non-arm64 ABIs.
// The real Zygisk injector only targets arm64-v8a (the zygote is always 64-bit).
// This stub exists so that `ninja qauxv-zygisk` does not fail on 32-bit ABIs;
// the resulting .so is excluded from packaging (see jniLibs.excludes in
// build.gradle.kts) and is never loaded by anything.
extern "C" void qauxv_zygisk_stub_unused() {}
