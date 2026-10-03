#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <dlfcn.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>
#include <stdexcept>
#include <string>
#include <cstring>
#include <cstdlib>
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
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
    GLuint outputGlBuffers[2]{};
    bool nhwcInput=false;
    GLint imageLocation=-1,layoutLocation=-1;
    bool inputInteropSync=false;
    ~Model() {
        auto& a=api();
        if(input) a.LiteRtDestroyTensorBuffer(input);
        for(auto output:outputs) if(output) a.LiteRtDestroyTensorBuffer(output);
        if(compiled) a.LiteRtDestroyCompiledModel(compiled);
        if(model) a.LiteRtDestroyModel(model);
        if(environment) a.LiteRtDestroyEnvironment(environment);
        if(buffer) glDeleteBuffers(1,&buffer);
        glDeleteBuffers(2,outputGlBuffers);
        if(program) glDeleteProgram(program);
    }
    void initialize(const char* path,bool glOutputs=false) {
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
        nhwcInput=type.layout.rank==4 && type.layout.dimensions[1]==640 && type.layout.dimensions[2]==640 && type.layout.dimensions[3]==3;
        bool nchw=type.layout.rank==4 && type.layout.dimensions[1]==3 && type.layout.dimensions[2]==640 && type.layout.dimensions[3]==640;
        if(type.element_type!=kLiteRtElementTypeFloat32 || type.layout.rank!=4 || type.layout.dimensions[0]!=1 || (!nhwcInput && !nchw))
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
            if(glOutputs) {
                glGenBuffers(1,&outputGlBuffers[i]);glBindBuffer(GL_SHADER_STORAGE_BUFFER,outputGlBuffers[i]);
                glBufferData(GL_SHADER_STORAGE_BUFFER,sizes[i]*sizeof(float),nullptr,GL_DYNAMIC_READ);
                check(a.LiteRtCreateTensorBufferFromGlBuffer(environment,&type,GL_SHADER_STORAGE_BUFFER,outputGlBuffers[i],sizes[i]*sizeof(float),0,nullptr,&outputs[i]),"GL output buffer");
            } else check(a.LiteRtCreateManagedTensorBufferFromRequirements(environment,&type,requirements,&outputs[i]),"output buffer");
        }
        const char* code=R"(#version 310 es
          precision highp float;
          layout(local_size_x=16,local_size_y=16) in;
          layout(std430,binding=0) buffer Input { float values[]; };
          uniform sampler2D image;
          uniform bool nhwc;
          void main(){ uint x=gl_GlobalInvocationID.x,y=gl_GlobalInvocationID.y;
            if(x>=640u||y>=640u)return;
            vec3 rgb=floor(texelFetch(image,ivec2(x,y),0).rgb*255.0+0.5)/255.0;
            uint i=y*640u+x;
            if(nhwc){values[i*3u]=rgb.r;values[i*3u+1u]=rgb.g;values[i*3u+2u]=rgb.b;}
            else {values[i]=rgb.r;values[409600u+i]=rgb.g;values[819200u+i]=rgb.b;} }
        )";
        GLuint compute=shader(code); program=glCreateProgram(); glAttachShader(program,compute); glLinkProgram(program); glDeleteShader(compute);
        GLint ok=0; glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok || glGetError()!=GL_NO_ERROR) throw std::runtime_error("GPU input program unavailable");
        imageLocation=glGetUniformLocation(program,"image");layoutLocation=glGetUniformLocation(program,"nhwc");
    }
    void run(GLuint texture,bool useFence) {
        glUseProgram(program); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,texture);
        glUniform1i(imageLocation,0); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glUniform1i(layoutLocation,nhwcInput);
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
    }
    void* readOutput(int index,int elements) {
        if(outputGlBuffers[index]) {
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,outputGlBuffers[index]);
            auto address=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,elements*sizeof(float),GL_MAP_READ_BIT);
            if(!address || glGetError()!=GL_NO_ERROR)throw std::runtime_error("GL output read failed");
            return address;
        }
        void* address=nullptr;
        check(api().LiteRtLockTensorBuffer(outputs[index],&address,kLiteRtTensorBufferLockModeRead),"output read");
        return address;
    }
    void endReadOutput(int index) {
        if(outputGlBuffers[index]) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,outputGlBuffers[index]);
            if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("GL output became invalid");
        } else check(api().LiteRtUnlockTensorBuffer(outputs[index]),"output unlock");
    }
    jobjectArray predict(JNIEnv* env, GLuint texture,bool useFence) {
        run(texture,useFence);
        auto result=env->NewObjectArray(2,env->FindClass("[F"),nullptr);
        if(!result) return nullptr;
        const int sizes[2]={38*8400,32*160*160};
        for(int i=0;i<2;++i) {
            void* address=readOutput(i,sizes[i]);
            auto array=env->NewFloatArray(sizes[i]);
            if(array) env->SetFloatArrayRegion(array,0,sizes[i],static_cast<float*>(address));
            endReadOutput(i);
            if(!array) return nullptr;
            env->SetObjectArrayElement(result,i,array); env->DeleteLocalRef(array);
        }
        return result;
    }
    void predictInto(JNIEnv* env,GLuint texture,jobject predictions,jobject prototypes,bool useFence) {
        jobject destinations[2]={predictions,prototypes};
        const int sizes[2]={38*8400,32*160*160};
        void* target[2]{};
        for(int i=0;i<2;++i) {
            target[i]=env->GetDirectBufferAddress(destinations[i]);
            if(!target[i] || env->GetDirectBufferCapacity(destinations[i])<sizes[i]*static_cast<int64_t>(sizeof(float)))
                throw std::runtime_error("Invalid direct output buffer");
        }
        run(texture,useFence);
        for(int i=0;i<2;++i) {
            void* address=readOutput(i,sizes[i]);
            if(!address) {
                endReadOutput(i);
                throw std::runtime_error("Output address unavailable");
            }
            std::memcpy(target[i],address,sizes[i]*sizeof(float));
            endReadOutput(i);
        }
    }
    jobjectArray outputBufferTypes(JNIEnv* env) {
        auto& a=api();
        // Diagnostic queries are optional; older runtimes must keep the validated path.
        auto count=reinterpret_cast<decltype(&LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes)>(dlsym(a.library,"LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes"));
        auto supported=reinterpret_cast<decltype(&LiteRtGetTensorBufferRequirementsSupportedTensorBufferType)>(dlsym(a.library,"LiteRtGetTensorBufferRequirementsSupportedTensorBufferType"));
        auto actual=reinterpret_cast<decltype(&LiteRtGetTensorBufferType)>(dlsym(a.library,"LiteRtGetTensorBufferType"));
        auto result=env->NewObjectArray(2,env->FindClass("[I"),nullptr);
        if(!result) return nullptr;
        for(int i=0;i<2;++i) {
            LiteRtTensorBufferRequirements requirements=nullptr;
            check(a.LiteRtGetCompiledModelOutputBufferRequirements(compiled,0,i,&requirements),"output requirements");
            int size=0; LiteRtTensorBufferType selected=kLiteRtTensorBufferTypeUnknown;
            if(count && supported) check(count(requirements,&size),"supported output types");
            if(size<0 || size>100) throw std::runtime_error("Invalid output buffer type count");
            if(actual) check(actual(outputs[i],&selected),"selected output type");
            std::vector<jint> values(size+1); values[0]=selected;
            for(int j=0;j<size;++j) {
                LiteRtTensorBufferType type=kLiteRtTensorBufferTypeUnknown;
                check(supported(requirements,j,&type),"supported output type"); values[j+1]=type;
            }
            auto array=env->NewIntArray(size+1); if(!array)return nullptr;
            env->SetIntArrayRegion(array,0,size+1,values.data());
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
extern "C" JNIEXPORT jlong JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_createVariant(JNIEnv* env,jobject,jstring path,jboolean glOutputs) {
    const char* file=env->GetStringUTFChars(path,nullptr);if(!file)return 0;
    try {auto model=std::make_unique<Model>();model->initialize(file,glOutputs);env->ReleaseStringUTFChars(path,file);return reinterpret_cast<jlong>(model.release());}
    catch(const std::exception& error){env->ReleaseStringUTFChars(path,file);fail(env,error);return 0;}
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_predict(JNIEnv* env,jobject,jlong handle,jint texture,jboolean useFence) {
    try { if(!handle || texture<=0) throw std::runtime_error("Invalid GPU model/input"); return reinterpret_cast<Model*>(handle)->predict(env,texture,useFence); }
    catch(const std::exception& error){ fail(env,error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_predictInto(JNIEnv* env,jobject,jlong handle,jint texture,jobject predictions,jobject prototypes,jboolean useFence) {
    try { if(!handle || texture<=0) throw std::runtime_error("Invalid GPU model/input"); reinterpret_cast<Model*>(handle)->predictInto(env,texture,predictions,prototypes,useFence); }
    catch(const std::exception& error){ fail(env,error); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_usesManagedInputSync(JNIEnv*,jobject,jlong handle) {
    return handle && reinterpret_cast<Model*>(handle)->inputInteropSync;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_outputBufferTypes(JNIEnv* env,jobject,jlong handle) {
    try { if(!handle)throw std::runtime_error("Invalid GPU model"); return reinterpret_cast<Model*>(handle)->outputBufferTypes(env); }
    catch(const std::exception& error){fail(env,error);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativeGpuModel_destroy(JNIEnv*,jobject,jlong handle) { delete reinterpret_cast<Model*>(handle); }
