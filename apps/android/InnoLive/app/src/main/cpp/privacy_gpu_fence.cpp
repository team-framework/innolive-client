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
    const char* extensions=eglQueryString(display,EGL_EXTENSIONS);
    if (!extensions || !createSync || !destroySync || !clientWait || !serverWait ||
        !std::strstr(extensions,"EGL_KHR_fence_sync") || !std::strstr(extensions,"EGL_KHR_wait_sync")) return 0;
    const auto sync=createSync(display,EGL_SYNC_FENCE_KHR,nullptr);
    if(sync==EGL_NO_SYNC_KHR) return 0;
    glFlush(); // The consumer's queue can wait only after the producer submits the fence.
    return reinterpret_cast<jlong>(new Fence{display,sync});
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuFence_awaitReady(JNIEnv* env,jobject,jlong handle) {
    const auto* fence=reinterpret_cast<Fence*>(handle);
    if(!fence) return;
    if(eglGetCurrentDisplay()==fence->display && eglGetCurrentContext()!=EGL_NO_CONTEXT &&
        serverWait(fence->display,fence->sync,0)==EGL_TRUE) return;
    // CPU conversion or an unsupported consumer context still gets a completed protected image.
    if(clientWait(fence->display,fence->sync,0,EGL_FOREVER_KHR)!=EGL_CONDITION_SATISFIED_KHR) failed(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuFence_destroy(JNIEnv*,jobject,jlong handle) {
    auto* fence=reinterpret_cast<Fence*>(handle);
    if(fence) { destroySync(fence->display,fence->sync);delete fence; }
}
