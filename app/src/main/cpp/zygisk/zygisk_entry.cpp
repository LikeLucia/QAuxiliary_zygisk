#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <string_view>

#include "jni_bridge.h"
#include "log.h"
#include "payload.h"
#include "plt_hook.h"
#include "zygisk.hpp"

namespace qauxv {

constexpr const char *SHARED_PAYLOAD_APK = "/data/adb/qauxv/main.apk";
constexpr const char *SHARED_PAYLOAD_HASH = "/data/adb/qauxv/main.apk.sha256";
constexpr const char *SCOPE_DIR = "/data/adb/qauxv";
constexpr const char *COMPAT_MARKER = "/data/adb/qauxv/compat.enable";
constexpr const char *ENTRY_CLASS = "io.github.qauxv.loader.zygisk.ZygiskEntry";
constexpr uint64_t APK_MAX_BYTES = 256ULL * 1024 * 1024;

// 支持的宿主包名，与 io.github.qauxv.loader.sbl.common.WellKnownConstants 保持一致。
constexpr std::array<const char *, 5> HOST_PACKAGES{
    "com.tencent.mobileqq",      // QQ
    "com.tencent.mobileqqi",     // QQ 国际版
    "com.tencent.qqlite",        // QQ 轻聊版
    "com.tencent.minihd.qq",     // QQ HD
    "com.tencent.tim",           // TIM
};

// 命中返回宿主包名，未命中返回 nullptr。
// 同时匹配主进程名与 "包名:子进程" 形式。
const char *match_host_package(std::string_view process_name) {
    for (const char *pkg : HOST_PACKAGES) {
        std::string_view package(pkg);
        if (process_name == package) {
            return pkg;
        }

        if (process_name.size() > package.size() &&
            process_name.starts_with(package) &&
            process_name[package.size()] == ':') {
            return pkg;
        }
    }

    return nullptr;
}

std::string get_jstring(JNIEnv *env, jstring str) {
    if (str == nullptr) return {};
    const char *chars = env->GetStringUTFChars(str, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

void log_impl_ident(zygisk::Api *api) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp != nullptr) {
        char line[512];
        int logged = 0;
        while (logged < 3 && fgets(line, sizeof(line), fp) != nullptr) {
            bool hit = false;
            for (const char *p = line; *p != '\0' && !hit; ++p) {
                const char needle[] = "zygisk";
                size_t i = 0;
                while (needle[i] != '\0' &&
                       std::tolower(static_cast<unsigned char>(p[i])) == needle[i]) {
                    ++i;
                }
                if (needle[i] == '\0') hit = true;
            }
            if (hit) {
                line[strcspn(line, "\n")] = '\0';
                LOGI("impl: %s", line);
                ++logged;
            }
        }
        fclose(fp);
    }
    uint32_t flags = api->getFlags();
    LOGI("impl: getFlags()=0x%x", flags);
}

class QAuxvZygisk : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api_ptr, JNIEnv *env_ptr) override {
        this->api = api_ptr;
        this->env = env_ptr;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        std::string nice_name = get_jstring(env, args->nice_name);
        const char *target = match_host_package(nice_name);
        if (target == nullptr) {
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        int dir_fd = api->getModuleDir();
        bool disabled = false;
        if (dir_fd >= 0) {
            disabled = faccessat(dir_fd, "disable", F_OK, 0) == 0 ||
                       faccessat(dir_fd, "remove", F_OK, 0) == 0;
            close(dir_fd);
        } else {
            LOGW("preAppSpecialize: getModuleDir failed, fallback to path check");
            constexpr const char *MODULE_DIR = "/data/adb/modules/zygisk_qauxv";
            disabled =
                    access((std::string(MODULE_DIR) + "/disable").c_str(), F_OK) == 0 ||
                    access((std::string(MODULE_DIR) + "/remove").c_str(), F_OK) == 0;
        }

        // 主动禁用模块时 disable 文件 → 本次跳过注入
        // 卸载标记为 remove → 本次跳过注入
        // 宿主（QQ/TIM）下次启动即不再注入
        if (disabled) {
            LOGI("preAppSpecialize: module disabled by user, skip injection");
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        std::string scope_base = SCOPE_DIR;
        int user_id = args->uid / 100000;
        if (user_id != 0) {
            scope_base += "/user_";
            scope_base += std::to_string(user_id);
        }
        // 按宿主包名独立控制注入，例如 /data/adb/qauxv/com.tencent.mobileqq.disable
        const std::string scope_path = scope_base + "/" + target + ".disable";
        if (access(scope_path.c_str(), F_OK) == 0) {
            LOGI("preAppSpecialize: %s disabled via WebUI (uid=%d), skip injection",
                 target, args->uid);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        log_impl_ident(api);

        bool compat_mode = (access(COMPAT_MARKER, F_OK) == 0 ||
                            access("/data/adb/qauxv/compat", F_OK) == 0);
        set_compat_mode(compat_mode);
        if (compat_mode) {
            LOGI("preAppSpecialize: compatibility mode enabled via WebUI");
        }

        // 收集本次进程要跳过的 PLT hook：逐个检查 <宿主包名>.<hook id>.disable
        // 标记文件。真正安装在 postAppSpecialize 进行（目标库那时才可能已加载）。
        host_package_ = target;
        disabled_hook_ids_.clear();
        const PltHookSpec *hook_specs = default_plt_hooks();
        const std::size_t hook_count = default_plt_hook_count();
        for (std::size_t i = 0; i < hook_count; ++i) {
            const std::string marker =
                    scope_base + "/" + target + "." + hook_specs[i].id + ".disable";
            if (access(marker.c_str(), F_OK) == 0) {
                disabled_hook_ids_.push_back(hook_specs[i].id);
                LOGI("preAppSpecialize: PLT hook %s disabled via WebUI (%s)",
                     hook_specs[i].id, marker.c_str());
            }
        }

        int apk_fd = open(SHARED_PAYLOAD_APK, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (apk_fd < 0) {
            LOGE("preAppSpecialize: open %s failed (errno=%d)", SHARED_PAYLOAD_APK, errno);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        struct stat st {};
        if (fstat(apk_fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
            static_cast<uint64_t>(st.st_size) > APK_MAX_BYTES) {
            LOGE("preAppSpecialize: invalid shared payload %s", SHARED_PAYLOAD_APK);
            close(apk_fd);
            api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        payload_hash_.clear();
        if (read_text_file(SHARED_PAYLOAD_HASH, payload_hash_)) {
            LOGI("preAppSpecialize: global payload hash %s", payload_hash_.c_str());
        } else {
            LOGW("preAppSpecialize: cannot read %s, cache will be refreshed", SHARED_PAYLOAD_HASH);
        }

        // 通知 Zygisk 实现保留该 fd：fork 路径下 ReZygisk 会在 pre 之后
        // 关闭所有未豁免的 fd（rz_sanitize_fds），不豁免则 post 阶段复制
        // main.apk 会因 fd 已关闭而失败。
        if (api->exemptFd(apk_fd)) {
            LOGI("preAppSpecialize: payload fd %d exempted", apk_fd);
        } else {
            LOGW("preAppSpecialize: exemptFd unavailable or rejected (fd %d)", apk_fd);
        }

        apk_fd_ = apk_fd;
        process_name_ = std::move(nice_name);
        data_dir_ = get_jstring(env, args->app_data_dir);
        enabled_ = true;
        LOGI("preAppSpecialize: target %s (uid=%d)", process_name_.c_str(), args->uid);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!enabled_) return;
        enabled_ = false;

        if (apk_fd_ < 0 || data_dir_.empty()) {
            LOGE("postAppSpecialize: incomplete state");
            if (apk_fd_ >= 0) close(apk_fd_);
            return;
        }
        int apk_fd = apk_fd_;
        apk_fd_ = -1;

        std::string target_dir = data_dir_ + "/files/.qauxv";
        if (!ensure_dir(target_dir)) {
            close(apk_fd);
            return;
        }

        // 本地文件日志：目录就绪后初始化
        log_file_init(target_dir + "/log.txt");

        // PLT/GOT hook：只在 MSF 进程安装。QQ 的反作弊库（libfekit.so）运行在
        // 该进程，且它是唯一需要抢在其读取 /proc 之前打补丁的地方。
        // 主进程不装：那里没有目标库，只会白跑轮询线程。
        if (process_name_.size() >= 4 &&
            process_name_.compare(process_name_.size() - 4, 4, ":MSF") == 0) {
            if (disabled_hook_ids_.size() >= default_plt_hook_count()) {
                LOGI("postAppSpecialize: all PLT hooks disabled by user, skip");
            } else {
                LOGI("postAppSpecialize: installing PLT hooks in %s", process_name_.c_str());
                install_default_plt_hooks(disabled_hook_ids_);
            }
        }

        // 先校验 fd 仍指向 payload：个别实现（如未豁免 fd 的 fork 路径）可能
        // 在 pre 之后把它关掉，此时跳过复制、不 close 可能已被复用的 fd 号，
        // 后面改为从已有路径读 dex（main.apk 已存在时同样能注入）。
        std::string apk_dst = target_dir + "/main.apk";
        std::string hash_dst = target_dir + "/main.apk.sha256";
        struct stat st {};
        bool fd_ok = fstat(apk_fd, &st) == 0 && S_ISREG(st.st_mode);
        if (fd_ok) {
            std::string cached_hash;
            bool cached_hash_ok = read_text_file(hash_dst, cached_hash);

            bool payload_changed =
                    payload_hash_.empty() || !cached_hash_ok ||
                    payload_hash_ != cached_hash;

            if (payload_changed) {
                LOGI("postAppSpecialize: payload changed, updating cached main.apk");
                if (!copy_fd_to_path(apk_fd, apk_dst, APK_MAX_BYTES)) {
                    LOGE("postAppSpecialize: failed to copy main.apk");
                    close(apk_fd);
                    return;
                }
                if (!payload_hash_.empty()) {
                    if (!write_text_file_atomic(hash_dst, payload_hash_)) {
                        LOGE("postAppSpecialize: failed to update payload hash");
                        close(apk_fd);
                        return;
                    }
                    LOGI("postAppSpecialize: payload updated: %s", payload_hash_.c_str());
                } else {
                    unlink(hash_dst.c_str());
                    LOGW("postAppSpecialize: global payload hash missing, "
                         "cache refreshed without version file");
                }
            } else {
                LOGI("postAppSpecialize: payload unchanged: %s", payload_hash_.c_str());
            }
            close(apk_fd);
        } else {
            LOGW("postAppSpecialize: payload fd %d no longer valid, skip copy", apk_fd);
        }

        // Read all classes*.dex from the copied package and build an
        // InMemoryDexClassLoader. An APK-path DexClassLoader is not usable
        // here: Android 10+ refuses to load dex from app-writable paths
        // ("Attempt to load writable dex file"), so the payload is loaded
        // from memory instead.
        // Note: because the payload is loaded from raw dex buffers, resources
        // and META-INF/services entries of the module APK are NOT visible to
        // the injected code (no AssetManager, no ServiceLoader). The Java side
        // must not rely on ServiceLoader-based discovery.
        // Note: dex_bufs is intentionally never freed — the class loader
        // holds direct ByteBuffers referencing the underlying dex memory.
        auto *dex_bufs = new std::vector<std::vector<uint8_t>>();
        if (!read_dex_from_apk(env, apk_dst, dex_bufs)) {
            LOGE("postAppSpecialize: failed to read dex from %s", apk_dst.c_str());
            return;
        }
        jobject loader = build_dex_classloader(env, *dex_bufs);
        if (loader == nullptr) {
            LOGE("postAppSpecialize: failed to build InMemoryDexClassLoader");
            return;
        }

        // Load the entry class and register its natives.
        jclass entry = load_class_from_loader(env, loader, ENTRY_CLASS);
        if (entry == nullptr) {
            LOGE("postAppSpecialize: ZygiskEntry class not found");
            env->DeleteLocalRef(loader);
            return;
        }
        if (!register_entry_natives(env, loader)) {
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(loader);
            return;
        }

        // Call ZygiskEntry.init(processName, dataDir, apkPath).
        jmethodID init = env->GetStaticMethodID(
                entry, "init", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (init == nullptr) {
            env->ExceptionClear();
            LOGE("postAppSpecialize: ZygiskEntry.init not found");
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(loader);
            return;
        }
        jstring j_process = env->NewStringUTF(process_name_.c_str());
        jstring j_data = env->NewStringUTF(data_dir_.c_str());
        jstring j_apk = env->NewStringUTF(apk_dst.c_str());
        if (j_process == nullptr || j_data == nullptr || j_apk == nullptr ||
            env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("postAppSpecialize: string allocation failed");
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(loader);
            return;
        }
        env->CallStaticVoidMethod(entry, init, j_process, j_data, j_apk);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGE("postAppSpecialize: ZygiskEntry.init failed");
        } else {
            LOGI("postAppSpecialize: ZygiskEntry.init completed for %s",
                 process_name_.c_str());
        }
        env->DeleteLocalRef(j_process);
        env->DeleteLocalRef(j_data);
        env->DeleteLocalRef(j_apk);
        env->DeleteLocalRef(entry);
        env->DeleteLocalRef(loader);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    int apk_fd_ = -1;
    bool enabled_ = false;
    std::string process_name_;
    std::string data_dir_;
    std::string payload_hash_;
    std::string host_package_;
    std::vector<std::string> disabled_hook_ids_;
};

}  // namespace qauxv

REGISTER_ZYGISK_MODULE(qauxv::QAuxvZygisk)
