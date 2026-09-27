#ifndef SUPERVIDEO_NATIVE_LOG_H
#define SUPERVIDEO_NATIVE_LOG_H

// Native diagnostics: logcat on Android, stderr elsewhere (redirected to logs/native.log by the desktop app).
#ifdef __ANDROID__
#include <android/log.h>
#define SV_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SuperVideoNative", __VA_ARGS__)
#else
#include <cstdio>
#define SV_LOG(...)                                   \
    do {                                              \
        std::fprintf(stderr, "[SuperVideoNative] ");  \
        std::fprintf(stderr, __VA_ARGS__);            \
        std::fprintf(stderr, "\n");                   \
        std::fflush(stderr);                          \
    } while (0)
#endif

#endif //SUPERVIDEO_NATIVE_LOG_H
