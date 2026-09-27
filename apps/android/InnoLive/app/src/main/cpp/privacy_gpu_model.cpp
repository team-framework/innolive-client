#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <dlfcn.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <cstring>
#include <cstdlib>
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/options/litert_gpu_options.h"

namespace {
#define API_LIST(X) \
 X(LiteRtCreateEnvironment) X(LiteRtDestroyEnvironment) X(LiteRtEnvironmentSupportsClGlInterop) \
 X(LiteRtCreateModelFromFile) X(LiteRtDestroyModel) X(LiteRtGetModelSignature) \
 X(LiteRtGetSignatureInputTensorByIndex) X(LiteRtGetSignatureOutputTensorByIndex) X(LiteRtGetRankedTensorType) \
 X(LiteRtCreateOptions) X(LiteRtDestroyOptions) X(LiteRtSetOptionsHardwareAccelerators) X(LiteRtAddOpaqueOptions) \
 X(LiteRtCreateOpaqueOptions) X(LiteRtDestroyOpaqueOptions) \
 X(LiteRtCreateCompiledModel) X(LiteRtDestroyCompiledModel) X(LiteRtCompiledModelIsFullyAccelerated) \
 X(LiteRtGetCompiledModelOutputBufferRequirements) X(LiteRtCreateManagedTensorBufferFromRequirements) \
 X(LiteRtCreateTensorBufferFromGlBuffer) X(LiteRtDestroyTensorBuffer) X(LiteRtRunCompiledModel) \
 X(LiteRtLockTensorBuffer) X(LiteRtUnlockTensorBuffer)
struct Api {
    void* library;
#define MEMBER(name) decltype(&name) name;
    API_LIST(MEMBER)
#undef MEMBER
    Api() {
        library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("LiteRT runtime unavailable");
#define LOAD(name) name = reinterpret_cast<decltype(name)>(dlsym(library,#name)); if (!name) throw std::runtime_error("Missing LiteRT symbol " #name);
        API_LIST(LOAD)
#undef LOAD
    }
    // Runtime also serves Kotlin model instances. Keep this process-global handle loaded.
};
Api& api() { static Api value; return value; }
void check(LiteRtStatus status, const char* operation) {
    if (status != kLiteRtStatusOk) throw std::runtime_error(std::string(operation) + " status=" + std::to_string(status));
}
GLuint shader(const char* code) {
    GLuint id = glCreateShader(GL_COMPUTE_SHADER);
    glShaderSource(id,1,&code,nullptr); glCompileShader(id);
    GLint ok=0; glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if (!ok) { char log[500]{}; glGetShaderInfoLog(id,sizeof(log),nullptr,log); glDeleteShader(id); throw std::runtime_error(std::string("Input shader: ")+log); }
    return id;
}
struct Model {
    Model() { api(); }
    LiteRtEnvironment environment=nullptr;
    LiteRtModel model=nullptr;
    LiteRtCompiledModel compiled=nullptr;
    LiteRtTensorBuffer input=nullptr, outputs[2]{};
    GLuint buffer=0, program=0;
    bool inputInteropSync=false;
    ~Model() {
        auto& a=api();
        if(input) a.LiteRtDestroyTensorBuffer(input);
        for(auto output:outputs) if(output) a.LiteRtDestroyTensorBuffer(output);
        if(compiled) a.LiteRtDestroyCompiledModel(compiled);
        if(model) a.LiteRtDestroyModel(model);
        if(environment) a.LiteRtDestroyEnvironment(environment);
        if(buffer) glDeleteBuffers(1,&buffer);
        if(program) glDeleteProgram(program);
    }
    void initialize(const char* path) {
        auto& a=api();
        if(eglGetCurrentContext()==EGL_NO_CONTEXT) throw std::runtime_error("No current privacy EGL context");
        LiteRtEnvOption options[2]{};
        options[0].tag=kLiteRtEnvOptionTagEglDisplay; options[0].value.type=kLiteRtAnyTypeVoidPtr; options[0].value.ptr_value=eglGetCurrentDisplay();
        options[1].tag=kLiteRtEnvOptionTagEglContext; options[1].value.type=kLiteRtAnyTypeVoidPtr; options[1].value.ptr_value=eglGetCurrentContext();
        check(a.LiteRtCreateEnvironment(2,options,&environment),"environment");
        check(a.LiteRtCreateModelFromFile(environment,path,&model),"model");
        LiteRtOptions compileOptions=nullptr;
        check(a.LiteRtCreateOptions(&compileOptions),"options");
        try {
            check(a.LiteRtSetOptionsHardwareAccelerators(compileOptions,kLiteRtHwAcceleratorGpu),"accelerator");
            // The SDK's LrtGetOpaqueGpuOptionsData serializes this public GPU option as TOML.
            const std::string toml = "precision = " + std::to_string(kLiteRtDelegatePrecisionFp32) + "\n";
            char* data = strdup(toml.c_str());
            if (!data) throw std::bad_alloc();
            LiteRtOpaqueOptions opaque=nullptr;
            const auto opaqueStatus=a.LiteRtCreateOpaqueOptions("gpu_options",data,free,&opaque);
            if(opaqueStatus!=kLiteRtStatusOk) { free(data); check(opaqueStatus,"opaque"); }
            const auto attachStatus=a.LiteRtAddOpaqueOptions(compileOptions,opaque);
            if(attachStatus!=kLiteRtStatusOk) { a.LiteRtDestroyOpaqueOptions(opaque); check(attachStatus,"GPU options attach"); }
            check(a.LiteRtCreateCompiledModel(environment,model,compileOptions,&compiled),"compile");
        } catch(...) { a.LiteRtDestroyOptions(compileOptions); throw; }
        a.LiteRtDestroyOptions(compileOptions);
        bool full=false, interop=false;
        check(a.LiteRtCompiledModelIsFullyAccelerated(compiled,&full),"GPU partition");
        check(a.LiteRtEnvironmentSupportsClGlInterop(environment,&interop),"interop");
        if(!full || !interop) throw std::runtime_error("GPU graph or CL/GL interop unavailable");
        LiteRtSignature signature=nullptr; LiteRtTensor tensor=nullptr; LiteRtRankedTensorType type{};
        check(a.LiteRtGetModelSignature(model,0,&signature),"signature");
        check(a.LiteRtGetSignatureInputTensorByIndex(signature,0,&tensor),"input");
        check(a.LiteRtGetRankedTensorType(tensor,&type),"input type");
        if(type.element_type!=kLiteRtElementTypeFloat32 || type.layout.rank!=4 || type.layout.dimensions[0]!=1 ||
           type.layout.dimensions[1]!=3 || type.layout.dimensions[2]!=640 || type.layout.dimensions[3]!=640)
            throw std::runtime_error("Unexpected pinned input shape");
        glGenBuffers(1,&buffer); glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER,3*640*640*sizeof(float),nullptr,GL_DYNAMIC_DRAW);
        check(a.LiteRtCreateTensorBufferFromGlBuffer(environment,&type,GL_SHADER_STORAGE_BUFFER,buffer,3*640*640*sizeof(float),0,nullptr,&input),"GL input");
        const int sizes[2]={38*8400,32*160*160};
        for(int i=0;i<2;++i) {
            check(a.LiteRtGetSignatureOutputTensorByIndex(signature,i,&tensor),"output");
            check(a.LiteRtGetRankedTensorType(tensor,&type),"output type");
            int64_t elements=1; for(unsigned d=0;d<type.layout.rank;++d) elements*=type.layout.dimensions[d];
            if(type.element_type!=kLiteRtElementTypeFloat32 || elements!=sizes[i]) throw std::runtime_error("Unexpected pinned output shape");
            LiteRtTensorBufferRequirements requirements=nullptr;
            check(a.LiteRtGetCompiledModelOutputBufferRequirements(compiled,0,i,&requirements),"output requirements");
            check(a.LiteRtCreateManagedTensorBufferFromRequirements(environment,&type,requirements,&outputs[i]),"output buffer");
        }
        const char* code=R"(#version 310 es
          precision highp float;
          layout(local_size_x=16,local_size_y=16) in;
          layout(std430,binding=0) buffer Input { float values[]; };
          uniform sampler2D image;
          void main(){ uint x=gl_GlobalInvocationID.x,y=gl_GlobalInvocationID.y;
            if(x>=640u||y>=640u)return;
            vec3 rgb=floor(texelFetch(image,ivec2(x,y),0).rgb*255.0+0.5)/255.0;
            uint i=y*640u+x; values[i]=rgb.r; values[409600u+i]=rgb.g; values[819200u+i]=rgb.b; }
        )";
        GLuint compute=shader(code); program=glCreateProgram(); glAttachShader(program,compute); glLinkProgram(program); glDeleteShader(compute);
        GLint ok=0; glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok || glGetError()!=GL_NO_ERROR) throw std::runtime_error("GPU input program unavailable");
    }
    jobjectArray predict(JNIEnv* env, GLuint texture,bool useFence) {
        glUseProgram(program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture);
        glUniform1i(glGetUniformLocation(program,"image"),0); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glDispatchCompute(40,40,1); glMemoryBarrier(GL_ALL_BARRIER_BITS);
        auto& a=api();
        // LiteRT 2.2 OpenCL's GlInteropFabricLiteRt::Start creates the EGL fence,
        // imports it to the CL queue when supported, then acquires the GL buffer.
        // Do not attach a separate EGL input event: that backend rejects it.
        // Run is synchronous at output completion, allowing this SSBO to be reused.
        inputInteropSync=useFence;
        if(useFence) glFlush(); else glFinish(); // Explicit baseline for device A/B.
        if(glGetError()!=GL_NO_ERROR) throw std::runtime_error("GPU input dispatch failed");
        check(a.LiteRtRunCompiledModel(compiled,0,1,&input,2,outputs),"run");
        auto result=env->NewObjectArray(2,env->FindClass("[F"),nullptr);
        if(!result) return nullptr;
        const int sizes[2]={38*8400,32*160*160};
        for(int i=0;i<2;++i) {
            void* address=nullptr;
            check(a.LiteRtLockTensorBuffer(outputs[i],&address,kLiteRtTensorBufferLockModeRead),"output read");
            auto array=env->NewFloatArray(sizes[i]);
            if(array) env->SetFloatArrayRegion(array,0,sizes[i],static_cast<float*>(address));
            const auto status=a.LiteRtUnlockTensorBuffer(outputs[i]);
            check(status,"output unlock");
            if(!array) return nullptr;
            env->SetObjectArrayElement(result,i,array); env->DeleteLocalRef(array);
        }
        return result;
    }
};
void fail(JNIEnv* env,const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what()); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_create(JNIEnv* env,jobject,jstring path) {
    const char* file=env->GetStringUTFChars(path,nullptr); if(!file)return 0;
    try { auto model=std::make_unique<Model>(); model->initialize(file); env->ReleaseStringUTFChars(path,file); return reinterpret_cast<jlong>(model.release()); }
    catch(const std::exception& error){ env->ReleaseStringUTFChars(path,file); fail(env,error); return 0; }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_predict(JNIEnv* env,jobject,jlong handle,jint texture,jboolean useFence) {
    try { if(!handle || texture<=0) throw std::runtime_error("Invalid GPU model/input"); return reinterpret_cast<Model*>(handle)->predict(env,texture,useFence); }
    catch(const std::exception& error){ fail(env,error); return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_usesManagedInputSync(JNIEnv*,jobject,jlong handle) {
    return handle && reinterpret_cast<Model*>(handle)->inputInteropSync;
}
extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_destroy(JNIEnv*,jobject,jlong handle) { delete reinterpret_cast<Model*>(handle); }
