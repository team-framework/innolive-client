#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <cstring>

namespace {
struct Fence { EGLDisplay display; EGLSyncKHR sync; };
auto createSync = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
auto destroySync = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
auto clientWait = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
auto serverWait = reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(eglGetProcAddress("eglWaitSyncKHR"));
void failed(JNIEnv* env) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "GPU fence wait failed"); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuFence_create(JNIEnv*, jobject) {
    const auto display=eglGetCurrentDisplay();
    // EGL 기능은 display마다 고정입니다. 캐시는 이를 소유한 생산자 스레드에 둡니다.
    thread_local EGLDisplay cachedDisplay=EGL_NO_DISPLAY;
    thread_local bool supported=false;
    if(display!=cachedDisplay) {
        const char* extensions=eglQueryString(display,EGL_EXTENSIONS);
        supported=extensions && createSync && destroySync && clientWait && serverWait &&
            std::strstr(extensions,"EGL_KHR_fence_sync") && std::strstr(extensions,"EGL_KHR_wait_sync");
        cachedDisplay=display;
    }
    if(!supported) return 0;
    const auto sync=createSync(display,EGL_SYNC_FENCE_KHR,nullptr);
    if(sync==EGL_NO_SYNC_KHR) return 0;
    glFlush(); // 생산자가 fence를 제출한 뒤에야 소비자 큐가 기다릴 수 있습니다.
    return reinterpret_cast<jlong>(new Fence{display,sync});
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuFence_awaitReady(JNIEnv* env,jobject,jlong handle) {
    const auto* fence=reinterpret_cast<Fence*>(handle);
    if(!fence) return;
    if(eglGetCurrentDisplay()==fence->display && eglGetCurrentContext()!=EGL_NO_CONTEXT &&
        serverWait(fence->display,fence->sync,0)==EGL_TRUE) return;
    // CPU 변환이나 지원하지 않는 소비자 컨텍스트도 완료된 보호 이미지를 받습니다.
    if(clientWait(fence->display,fence->sync,0,EGL_FOREVER_KHR)!=EGL_CONDITION_SATISFIED_KHR) failed(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuFence_destroy(JNIEnv*,jobject,jlong handle) {
    auto* fence=reinterpret_cast<Fence*>(handle);
    if(fence) { destroySync(fence->display,fence->sync);delete fence; }
}
