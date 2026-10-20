/**
 * JNI bridge for Live2D Cubism integration.
 * Adapted from Cubism SDK Minimum sample JniBridgeC.cpp
 */

#include <jni.h>
#include "JniBridgeC.hpp"
#include "LAppMinimumDelegate.hpp"
#include "LAppMinimumLive2DManager.hpp"
#include "LAppPal.hpp"
#include <CubismFramework.hpp>

using namespace Csm;

static JavaVM* g_JVM;
static jclass  g_BridgeClass;
static jmethodID g_LoadFileMethodId;

JNIEnv* GetEnv()
{
    JNIEnv* env = NULL;
    g_JVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_JVM = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("ai/roleswitch/android/service/CubismBridge");
    g_BridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_LoadFileMethodId = env->GetStaticMethodID(g_BridgeClass, "LoadFile", "(Ljava/lang/String;)[B");

    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_BridgeClass);
}

char* JniBridgeC::LoadFileAsBytesFromJava(const char* filePath, unsigned int* outSize)
{
    JNIEnv *env = GetEnv();

    jbyteArray obj = (jbyteArray)env->CallStaticObjectMethod(g_BridgeClass, g_LoadFileMethodId, env->NewStringUTF(filePath));

    if (!obj)
    {
        return NULL;
    }

    *outSize = static_cast<unsigned int>(env->GetArrayLength(obj));

    char* buffer = new char[*outSize];
    env->GetByteArrayRegion(obj, 0, *outSize, reinterpret_cast<jbyte *>(buffer));

    return buffer;
}

void JniBridgeC::MoveTaskToBack()
{
    // Not needed in our integration
}

extern "C"
{
    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnStart(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnStart();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnPause(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnPause();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnStop(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnStop();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnDestroy(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnDestroy();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnSurfaceCreated(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnSurfaceCreate();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnSurfaceChanged(JNIEnv *env, jobject thiz, jint width, jint height)
    {
        LAppMinimumDelegate::GetInstance()->OnSurfaceChanged(width, height);
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnDrawFrame(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->Run();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnTouchesBegan(JNIEnv *env, jobject thiz, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnTouchesEnded(JNIEnv *env, jobject thiz, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnTouchesMoved(JNIEnv *env, jobject thiz, jfloat pointX, jfloat pointY)
    {
        LAppMinimumDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeStartMotion(JNIEnv *env, jobject thiz, jstring group, jint no, jint priority)
    {
        // The manager must not be built before CubismFramework::StartUp registered
        // the allocator (see nativeSetModelIndex below) — a queued motion that wins
        // the race with onSurfaceCreated would otherwise crash in the model ctor.
        if (!CubismFramework::IsStarted())
        {
            return;
        }
        const char* groupStr = env->GetStringUTFChars(group, nullptr);
        LAppMinimumLive2DManager* manager = LAppMinimumLive2DManager::GetInstance();
        if (manager)
        {
            manager->StartMotion(groupStr, no, priority);
        }
        env->ReleaseStringUTFChars(group, groupStr);
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeSetLipSyncValue(JNIEnv *env, jobject thiz, jfloat value)
    {
        if (!CubismFramework::IsStarted())
        {
            return;
        }
        LAppMinimumLive2DManager* manager = LAppMinimumLive2DManager::GetInstance();
        if (manager)
        {
            manager->SetLipSyncValue(value);
        }
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeOnResume(JNIEnv *env, jobject thiz)
    {
        LAppMinimumDelegate::GetInstance()->OnResume();
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeSwitchModel(JNIEnv *env, jobject thiz)
    {
        if (!CubismFramework::IsStarted())
        {
            return;
        }
        LAppMinimumLive2DManager* manager = LAppMinimumLive2DManager::GetInstance();
        if (manager)
        {
            int currentIndex = manager->GetModelIndex();
            manager->SwitchModel(currentIndex + 1);
        }
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeSetModelIndex(JNIEnv *env, jobject thiz, jint index)
    {
        // Building the manager runs LoadModel, whose model constructor allocates
        // through the framework allocator. Before the GL surface exists that
        // allocator is not registered (CubismFramework::StartUp runs in
        // OnSurfaceCreate) and the allocation is a null-deref — seen as a SIGSEGV
        // in operator new(CubismAllocationTag) when a queued switch raced
        // onSurfaceCreated. Until the framework is up, only the initial index can
        // be staged: the first frame after OnSurfaceCreate builds the manager with
        // it, so nothing is lost.
        if (!CubismFramework::IsStarted())
        {
            LAppMinimumLive2DManager::SetInitialModelIndex(index);
            return;
        }
        LAppMinimumLive2DManager* manager = LAppMinimumLive2DManager::GetInstance();
        if (manager)
        {
            manager->SwitchModel(index);
        }
    }

    JNIEXPORT jint JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeGetModelIndex(JNIEnv *env, jobject thiz)
    {
        if (!CubismFramework::IsStarted())
        {
            return 0;
        }
        LAppMinimumLive2DManager* manager = LAppMinimumLive2DManager::GetInstance();
        if (manager)
        {
            return manager->GetModelIndex();
        }
        return 0;
    }

    JNIEXPORT void JNICALL
    Java_ai_roleswitch_android_service_CubismBridge_nativeSetInitialModelIndex(JNIEnv *env, jobject thiz, jint index)
    {
        LAppMinimumLive2DManager::SetInitialModelIndex(index);
    }
}
