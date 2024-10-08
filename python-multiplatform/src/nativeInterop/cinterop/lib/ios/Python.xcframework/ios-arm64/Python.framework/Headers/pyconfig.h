#ifdef __APPLE__
    #ifdef __arm64__
        #include "pyconfig-ios-arm64.h"
    #endif

    #ifdef __x86_64__
        #include "pyconfig-ios-x86_64.h"
    #endif
#endif

#ifdef __ANDROID__
    #ifdef __aarch64__
        #include "pyconfig-android-aarch64.h"
    #endif

    #ifdef __x86_64__
        #include "pyconfig-android-x86_64.h"
    #endif
#endif
