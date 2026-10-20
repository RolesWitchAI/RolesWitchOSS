cmake_minimum_required(VERSION 3.22.1)
project(roleswitch-native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page size alignment (required for Android 15+)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ============================================================
# Live2D Cubism SDK
# ============================================================
set(CUBISM_SDK_DIR ${CMAKE_SOURCE_DIR}/../../../../cubism/CubismSdkForNative-5-r.4.1)
set(CUBISM_CORE_PATH ${CUBISM_SDK_DIR}/Core)
set(CUBISM_FRAMEWORK_PATH ${CUBISM_SDK_DIR}/Framework)
set(CUBISM_COMMON_PATH ${CUBISM_SDK_DIR}/Samples/Common)
set(CUBISM_STB_PATH ${CUBISM_SDK_DIR}/Samples/OpenGL/thirdParty/stb)

# Cubism Core (static library)
add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore PROPERTIES
    IMPORTED_LOCATION ${CUBISM_CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a
    INTERFACE_INCLUDE_DIRECTORIES ${CUBISM_CORE_PATH}/include
)

# Cubism Framework with OpenGL ES2 rendering
set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory(${CUBISM_FRAMEWORK_PATH} ${CMAKE_CURRENT_BINARY_DIR}/CubismFramework)
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)

# Cubism JNI bridge library
add_library(cubism-android SHARED
    cubism/CubismBridge.cpp
    cubism/LAppDefine.cpp
    cubism/LAppMinimumDelegate.cpp
    cubism/LAppMinimumLive2DManager.cpp
    cubism/LAppMinimumModel.cpp
    cubism/LAppPal.cpp
    cubism/LAppSprite.cpp
    cubism/LAppSpriteShader.cpp
    cubism/LAppTextureManager.cpp
    cubism/LAppMinimumView.cpp
    # Common base classes from SDK
    ${CUBISM_COMMON_PATH}/LAppModel_Common.cpp
    ${CUBISM_COMMON_PATH}/LAppSprite_Common.cpp
    ${CUBISM_COMMON_PATH}/LAppTextureManager_Common.cpp
    ${CUBISM_COMMON_PATH}/LAppView_Common.cpp
    ${CUBISM_COMMON_PATH}/LAppAllocator_Common.cpp
    ${CUBISM_COMMON_PATH}/TouchManager_Common.cpp
    ${CUBISM_COMMON_PATH}/CubismSampleViewMatrix_Common.cpp
)

target_include_directories(cubism-android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cubism
    ${CUBISM_COMMON_PATH}
    ${CUBISM_STB_PATH}
)

target_link_libraries(cubism-android
    Framework
    Live2DCubismCore
    GLESv2
    log
)

target_link_options(cubism-android PRIVATE "-Wl,-z,max-page-size=16384")
