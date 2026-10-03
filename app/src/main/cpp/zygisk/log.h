#pragma once

#include <android/log.h>

#include "log_file.h"

#define QAUXV_TAG "QAuxv-Zygisk"

#define LOGD(...)                                                          \
    do {                                                                   \
        qauxv_log_write(ANDROID_LOG_DEBUG, __VA_ARGS__);                    \
        __android_log_print(ANDROID_LOG_DEBUG, QAUXV_TAG, __VA_ARGS__);     \
    } while (0)
#define LOGI(...)                                                          \
    do {                                                                   \
        qauxv_log_write(ANDROID_LOG_INFO, __VA_ARGS__);                     \
        __android_log_print(ANDROID_LOG_INFO, QAUXV_TAG, __VA_ARGS__);      \
    } while (0)
#define LOGW(...)                                                          \
    do {                                                                   \
        qauxv_log_write(ANDROID_LOG_WARN, __VA_ARGS__);                     \
        __android_log_print(ANDROID_LOG_WARN, QAUXV_TAG, __VA_ARGS__);      \
    } while (0)
#define LOGE(...)                                                          \
    do {                                                                   \
        qauxv_log_write(ANDROID_LOG_ERROR, __VA_ARGS__);                    \
        __android_log_print(ANDROID_LOG_ERROR, QAUXV_TAG, __VA_ARGS__);     \
    } while (0)
