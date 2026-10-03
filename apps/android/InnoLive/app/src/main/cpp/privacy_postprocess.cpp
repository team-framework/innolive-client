#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
constexpr int kPixels = 160 * 160, kCandidates = 8400, kMaximum = 100;
struct Box { float left, top, right, bottom; };
float iou(const Box& a, const Box& b) {
    const float width = std::max(0.f, std::min(a.right,b.right)-std::max(a.left,b.left));
    const float height = std::max(0.f, std::min(a.bottom,b.bottom)-std::max(a.top,b.top));
    const float area = width*height;
    return area/std::max((a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-area,.0001f);
}
struct Detection { Box box; float score; int label, index; std::array<float,32> weights{}; };
struct Instance { Box box{}; int label=0; std::vector<uint8_t> bytes; };
struct State {
    std::vector<Detection> found, kept;
    std::vector<Instance> previous, current;
    std::vector<uint8_t> united = std::vector<uint8_t>(kPixels);
    const float* prototypes=nullptr;
    int previousCount=0, maskPixels=0;
    double previousTime=0;
    bool hasTime=false, ready=false;
    State() { found.reserve(kCandidates); kept.reserve(kMaximum); }
    void reset() { previousCount=0; hasTime=false; ready=false; prototypes=nullptr; maskPixels=0; }
};
State& state(jlong handle) {
    if (!handle) throw std::invalid_argument("Postprocessor is closed");
    return *reinterpret_cast<State*>(handle);
}
const float* buffer(JNIEnv* env,jobject value,int count) {
    auto* data=static_cast<const float*>(env->GetDirectBufferAddress(value));
    if (!data || env->GetDirectBufferCapacity(value)<static_cast<int64_t>(count)*4)
        throw std::invalid_argument("Invalid postprocessing buffer");
    return data;
}
void fail(JNIEnv* env,const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error.what());
}
void validate(const float* values,int count) {
    if (!std::all_of(values,values+count,[](float x){return std::isfinite(x);}))
        throw std::invalid_argument("Non-finite privacy output");
}
void decode(State& s,const float* values,const float* prototypes) {
    s.ready=false;
    validate(values,38*kCandidates);
    validate(prototypes,32*kPixels);
    s.found.clear(); s.kept.clear();
    for(int i=0;i<kCandidates;++i) {
        const float face=values[4*kCandidates+i], plate=values[5*kCandidates+i];
        const float score=std::max(face,plate);
        if(score<.25f) continue;
        const float x=values[i],y=values[kCandidates+i],w=values[2*kCandidates+i],h=values[3*kCandidates+i];
        if(w<=0 || h<=0) throw std::invalid_argument("Invalid detection dimensions");
        Box box{std::max(0.f,x-w/2),std::max(0.f,y-h/2),std::min(640.f,x+w/2),std::min(640.f,y+h/2)};
        if(box.right<=box.left || box.bottom<=box.top) continue;
        s.found.push_back({box,score,face>=plate?0:1,i,{}});
    }
    std::stable_sort(s.found.begin(),s.found.end(),[](const auto& a,const auto& b){return a.score>b.score;});
    for(auto candidate:s.found) {
        bool suppressed=false;
        for(const auto& kept:s.kept) if(kept.label==candidate.label && iou(kept.box,candidate.box)>.45f) {
            suppressed=true;break;
        }
        if(suppressed) continue;
        if(s.kept.size()==kMaximum) throw std::invalid_argument("Too many objects to protect");
        for(int c=0;c<32;++c) candidate.weights[c]=values[(6+c)*kCandidates+candidate.index];
        s.kept.push_back(candidate);
    }
    s.prototypes=prototypes;s.ready=true;
}
int lower(float value) { return std::clamp(static_cast<int>(std::floor(value/4.f)),0,160); }
int upper(float value) { return std::clamp(static_cast<int>(std::ceil(value/4.f)),0,160); }
void instance(Instance& output,const Detection& detection,const float* prototypes) {
    output.box=detection.box;output.label=detection.label;
    output.bytes.resize(kPixels);std::fill(output.bytes.begin(),output.bytes.end(),0);
    const int x0=lower(output.box.left),x1=upper(output.box.right);
    const int y0=lower(output.box.top),y1=upper(output.box.bottom);
    bool covered=false;
    for(int y=y0;y<y1;++y) {
        int x=x0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        for(;x+4<=x1;x+=4) {
            auto logits=vdupq_n_f32(0.f);
            for(int c=0;c<32;++c) logits=vaddq_f32(logits,vmulq_n_f32(vld1q_f32(prototypes+c*kPixels+y*160+x),detection.weights[c]));
            float values[4];vst1q_f32(values,logits);
            for(int lane=0;lane<4;++lane) {
                if(!std::isfinite(values[lane])) throw std::invalid_argument("Non-finite instance mask");
                if(values[lane]>0) {output.bytes[y*160+x+lane]=255;covered=true;}
            }
        }
#endif
        for(;x<x1;++x) {
            float logit=0;
            for(int c=0;c<32;++c) logit+=detection.weights[c]*prototypes[c*kPixels+y*160+x];
            if(!std::isfinite(logit)) throw std::invalid_argument("Non-finite instance mask");
            if(logit>0) {output.bytes[y*160+x]=255;covered=true;}
        }
    }
    if(!covered) for(int y=y0;y<y1;++y) std::fill(output.bytes.begin()+y*160+x0,output.bytes.begin()+y*160+x1,255);
}
float sample(const std::vector<uint8_t>& bytes,float x,float y) {
    if(x<0 || y<0 || x>159 || y>159) return 0;
    const int x0=static_cast<int>(std::floor(x)),y0=static_cast<int>(std::floor(y));
    const int x1=std::min(x0+1,159),y1=std::min(y0+1,159);
    const float dx=x-x0,dy=y-y0;
    const float top=bytes[y0*160+x0]*(1-dx)+bytes[y0*160+x1]*dx;
    const float bottom=bytes[y1*160+x0]*(1-dx)+bytes[y1*160+x1]*dx;
    return top*(1-dy)+bottom*dy;
}
void stabilize(Instance& output,const Instance& older,float decay) {
    const auto& d=output.box;const auto& o=older.box;
    const float left=d.left*.25f,top=d.top*.25f,right=d.right*.25f,bottom=d.bottom*.25f;
    const float sourceLeft=o.left*.25f,sourceTop=o.top*.25f;
    const float sourceWidth=(o.right-o.left)*.25f,sourceHeight=(o.bottom-o.top)*.25f;
    // Same independent multiply/add order as the Kotlin/iOS reference.
    for(int y=lower(d.top);y<upper(d.bottom);++y) {
        const float oldY=sourceTop+(y+.5f-top)/(bottom-top)*sourceHeight-.5f;
        for(int x=lower(d.left);x<upper(d.right);++x) {
            const float oldX=sourceLeft+(x+.5f-left)/(right-left)*sourceWidth-.5f;
            const int faded=std::clamp(static_cast<int>(sample(older.bytes,oldX,oldY)*decay),0,255);
            auto& value=output.bytes[y*160+x];value=std::max<int>(value,faded);
        }
    }
}
void unite(State& s,const Instance& instance) {
    for(int y=lower(instance.box.top);y<upper(instance.box.bottom);++y) {
        int x=lower(instance.box.left),end=upper(instance.box.right);
#if defined(__ARM_NEON) || defined(__aarch64__)
        for(;x+16<=end;x+=16) {
            const int offset=y*160+x;
            vst1q_u8(s.united.data()+offset,vmaxq_u8(vld1q_u8(s.united.data()+offset),vld1q_u8(instance.bytes.data()+offset)));
        }
#endif
        for(;x<end;++x) s.united[y*160+x]=std::max(s.united[y*160+x],instance.bytes[y*160+x]);
    }
}
void masks(State& s,const std::array<bool,kMaximum>& exempt,double timestamp) {
    if(!s.ready || !std::isfinite(timestamp)) throw std::invalid_argument("Invalid mask state or timestamp");
    s.ready=false;
    const double interval=s.hasTime?timestamp-s.previousTime:.20;
    if(interval<=0 || interval>=.20) s.previousCount=0;
    const float decay=static_cast<float>(std::exp(-std::max(0.,interval)/.12));
    std::array<bool,kMaximum> available{};std::fill(available.begin(),available.begin()+s.previousCount,true);
    const int count=std::count_if(s.kept.begin(),s.kept.end(),[&](const auto& d){return d.label!=0 || !exempt[&d-s.kept.data()];});
    if(static_cast<int>(s.current.size())<count) s.current.resize(count);
    std::fill(s.united.begin(),s.united.end(),0);
    int n=0;
    for(size_t i=0;i<s.kept.size();++i) {
        if(s.kept[i].label==0 && exempt[i]) continue;
        auto& current=s.current[n++];instance(current,s.kept[i],s.prototypes);
        float best=.30f;int match=-1;
        for(int j=0;j<s.previousCount;++j) if(available[j] && s.previous[j].label==current.label) {
            const float similarity=iou(s.previous[j].box,current.box);
            if(similarity>=.30f && (match<0 || similarity>best)) {best=similarity;match=j;}
        }
        if(match>=0) {available[match]=false;stabilize(current,s.previous[match],decay);}
        unite(s,current);
    }
    s.maskPixels=std::count_if(s.united.begin(),s.united.end(),[](uint8_t x){return x!=0;});
    s.previous.swap(s.current);s.previousCount=count;s.previousTime=timestamp;s.hasTime=true;
}
}

#define JNI(name) Java_com_framework_innolive_feature_live_privacy_PrivacyNativePostprocessor_00024Native_##name
extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv* env,jobject) {
    try{return reinterpret_cast<jlong>(new State());}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT jfloatArray JNICALL JNI(decode)(JNIEnv* env,jobject,jlong handle,jobject prediction,jobject prototypes) {
    try {
        auto& s=state(handle);decode(s,buffer(env,prediction,38*kCandidates),buffer(env,prototypes,32*kPixels));
        std::vector<float> output(s.kept.size()*38);
        for(size_t i=0;i<s.kept.size();++i) {
            const auto& d=s.kept[i];const int o=i*38;
            output[o]=d.box.left;output[o+1]=d.box.top;output[o+2]=d.box.right;output[o+3]=d.box.bottom;
            output[o+4]=d.score;output[o+5]=d.label;std::copy(d.weights.begin(),d.weights.end(),output.begin()+o+6);
        }
        auto result=env->NewFloatArray(output.size());if(result) env->SetFloatArrayRegion(result,0,output.size(),output.data());return result;
    }catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(mask)(JNIEnv* env,jobject,jlong handle,jintArray exempt,jdouble timestamp) {
    try {
        auto& s=state(handle);std::array<bool,kMaximum> allowed{};const int count=env->GetArrayLength(exempt);
        if(count>kMaximum) throw std::invalid_argument("Invalid exemptions");
        std::array<jint,kMaximum> indices{};env->GetIntArrayRegion(exempt,0,count,indices.data());
        for(int i=0;i<count;++i) {
            if(indices[i]<0 || indices[i]>=static_cast<int>(s.kept.size())) throw std::invalid_argument("Invalid exemption index");
            allowed[indices[i]]=true;
        }
        masks(s,allowed,timestamp);auto output=env->NewByteArray(kPixels);
        if(output) env->SetByteArrayRegion(output,0,kPixels,reinterpret_cast<const jbyte*>(s.united.data()));return output;
    }catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jint JNICALL JNI(maskPixels)(JNIEnv* env,jobject,jlong handle) {
    try{return state(handle).maskPixels;}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI(reset)(JNIEnv* env,jobject,jlong handle) {
    try{state(handle).reset();}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*,jobject,jlong handle) {delete reinterpret_cast<State*>(handle);}
