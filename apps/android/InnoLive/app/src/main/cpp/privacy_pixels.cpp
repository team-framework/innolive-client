#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <cstring>
#include <vector>
#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
void invalid(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
uint8_t clamp(int value) { return static_cast<uint8_t>(std::clamp(value, 0, 255)); }
bool bitmapInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo& info) {
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height) {
        invalid(env, "Expected software RGBA bitmap"); return false;
    }
    return true;
}
const uint8_t* plane(JNIEnv* env, jobject buffer, int stride, int width, int height) {
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data || stride < width || width <= 0 || height <= 0 ||
        capacity < static_cast<int64_t>(height - 1) * stride + width) {
        invalid(env, "Invalid I420 plane bounds"); return nullptr;
    }
    return data;
}
struct LockedBitmap {
    JNIEnv* env; jobject bitmap; void* pixels = nullptr;
    LockedBitmap(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels = nullptr; invalid(env, "Bitmap pixels unavailable");
        }
    }
    ~LockedBitmap() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
};
#if defined(__ARM_NEON) || defined(__aarch64__)
void rgba8(uint8x8_t y, uint8x8_t u, uint8x8_t v, uint8_t* output) {
    const auto c = vreinterpretq_s16_u16(vqsubq_u16(vmovl_u8(y), vdupq_n_u16(16)));
    const auto cu = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(u)), vdupq_n_s16(128));
    const auto cv = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(v)), vdupq_n_s16(128));
    uint16x4_t red[2], green[2], blue[2];
    for (int half = 0; half < 2; ++half) {
        const auto cy = half ? vget_high_s16(c) : vget_low_s16(c);
        const auto uu = half ? vget_high_s16(cu) : vget_low_s16(cu);
        const auto vv = half ? vget_high_s16(cv) : vget_low_s16(cv);
        const auto luminance = vmull_n_s16(cy, 298);
        red[half] = vqrshrun_n_s32(vmlal_n_s16(luminance, vv, 409), 8);
        green[half] = vqrshrun_n_s32(vmlsl_n_s16(vmlsl_n_s16(luminance, uu, 100), vv, 208), 8);
        blue[half] = vqrshrun_n_s32(vmlal_n_s16(luminance, uu, 516), 8);
    }
    const uint8x8x4_t rgba = {{vqmovn_u16(vcombine_u16(red[0], red[1])),
        vqmovn_u16(vcombine_u16(green[0], green[1])),
        vqmovn_u16(vcombine_u16(blue[0], blue[1])), vdup_n_u8(255)}};
    vst4_u8(output, rgba);
}
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_i420ToBitmap(
    JNIEnv* env, jobject, jobject yBuffer, jint yStride, jobject uBuffer, jint uStride,
    jobject vBuffer, jint vStride, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (!bitmapInfo(env, bitmap, info)) return;
    const int width = static_cast<int>(info.width), height = static_cast<int>(info.height);
    const auto* yp = plane(env, yBuffer, yStride, width, height);
    if (!yp) return;
    const auto* up = plane(env, uBuffer, uStride, (width + 1) / 2, (height + 1) / 2);
    if (!up) return;
    const auto* vp = plane(env, vBuffer, vStride, (width + 1) / 2, (height + 1) / 2);
    if (!vp) return;
    void* locked = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &locked) != ANDROID_BITMAP_RESULT_SUCCESS) {
        invalid(env, "Bitmap pixels unavailable"); return;
    }
    auto* pixels = static_cast<uint8_t*>(locked);
    for (int y = 0; y < height; ++y) {
        auto* row = pixels + y * info.stride;
        int x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        for (; x + 16 <= width; x += 16) {
            const auto ys = vld1q_u8(yp + y * yStride + x);
            const auto u = vld1_u8(up + (y / 2) * uStride + x / 2);
            const auto v = vld1_u8(vp + (y / 2) * vStride + x / 2);
            const auto us = vzip_u8(u, u), vs = vzip_u8(v, v);
            rgba8(vget_low_u8(ys), us.val[0], vs.val[0], row + 4 * x);
            rgba8(vget_high_u8(ys), us.val[1], vs.val[1], row + 4 * (x + 8));
        }
#endif
        for (; x < width; ++x) {
            const int c = 298 * std::max(0, static_cast<int>(yp[y * yStride + x]) - 16);
            const int u = static_cast<int>(up[(y / 2) * uStride + x / 2]) - 128;
            const int v = static_cast<int>(vp[(y / 2) * vStride + x / 2]) - 128;
            row[4 * x] = clamp((c + 409 * v + 128) >> 8);
            row[4 * x + 1] = clamp((c - 100 * u - 208 * v + 128) >> 8);
            row[4 * x + 2] = clamp((c + 516 * u + 128) >> 8);
            row[4 * x + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_copyPlane(
    JNIEnv* env, jobject, jobject source, jint rowStride, jint pixelStride, jint width,
    jint height, jobject target, jint targetStride) {
    const auto* input = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    const int64_t rowBytes = (static_cast<int64_t>(width) - 1) * pixelStride + 1;
    if (!input || !output || width <= 0 || height <= 0 || pixelStride <= 0 ||
        rowStride < rowBytes || targetStride < width ||
        env->GetDirectBufferCapacity(source) < static_cast<int64_t>(height - 1) * rowStride + rowBytes ||
        env->GetDirectBufferCapacity(target) < static_cast<int64_t>(height - 1) * targetStride + width) {
        invalid(env, "Invalid camera plane bounds"); return;
    }
    for (int y = 0; y < height; ++y) {
        const auto* row = input + y * rowStride;
        auto* dest = output + y * targetStride;
        if (pixelStride == 1) {
            std::memcpy(dest, row, width);
            continue;
        }
        int x = 0;
#if defined(__ARM_NEON) || defined(__aarch64__)
        // Leave the final group to scalar code: camera buffers may omit the last padding byte.
        if (pixelStride == 2) for (; x + 16 < width; x += 16) {
            vst1q_u8(dest + x, vld2q_u8(row + 2 * x).val[0]);
        }
#endif
        for (; x < width; ++x) dest[x] = row[x * pixelStride];
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_bitmapToTensor(
    JNIEnv* env, jobject, jobject bitmap, jobject buffer) {
    AndroidBitmapInfo info{};
    if (!bitmapInfo(env, bitmap, info)) return;
    const int64_t area = static_cast<int64_t>(info.width) * info.height;
    auto* output = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (!output || env->GetDirectBufferCapacity(buffer) < area * 3 * static_cast<int64_t>(sizeof(float))) {
        invalid(env, "Invalid tensor buffer bounds"); return;
    }
    void* locked = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &locked) != ANDROID_BITMAP_RESULT_SUCCESS) {
        invalid(env, "Bitmap pixels unavailable"); return;
    }
    const auto* pixels = static_cast<const uint8_t*>(locked);
    for (uint32_t y = 0; y < info.height; ++y) {
        const auto* row = pixels + y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            const auto i = y * info.width + x;
            output[i] = row[4 * x] / 255.f;
            output[area + i] = row[4 * x + 1] / 255.f;
            output[2 * area + i] = row[4 * x + 2] / 255.f;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_finiteFloats(
    JNIEnv* env, jobject, jfloatArray values) {
    const auto size = env->GetArrayLength(values);
    const auto* data = env->GetFloatArrayElements(values, nullptr);
    if (!data) return JNI_FALSE;
    bool finite = true;
    for (int i = 0; i < size; ++i) if (!std::isfinite(data[i])) { finite = false; break; }
    env->ReleaseFloatArrayElements(values, const_cast<float*>(data), JNI_ABORT);
    return finite ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_composite(
    JNIEnv* env, jobject, jobject source, jobject blurred, jbyteArray alpha,
    jint left, jint top, jint resizedWidth, jint resizedHeight, jobject output) {
    AndroidBitmapInfo si{}, bi{}, oi{};
    if (!bitmapInfo(env, source, si) || !bitmapInfo(env, blurred, bi) || !bitmapInfo(env, output, oi)) return;
    if (si.width != bi.width || si.height != bi.height || si.width != oi.width || si.height != oi.height ||
        env->GetArrayLength(alpha) != 160 * 160 || left < 0 || top < 0 ||
        resizedWidth <= 0 || resizedHeight <= 0 || resizedWidth > 640 || resizedHeight > 640) {
        invalid(env, "Invalid privacy composite geometry"); return;
    }
    LockedBitmap sp(env, source);
    if (!sp.pixels) return;
    LockedBitmap bp(env, blurred);
    if (!bp.pixels) return;
    LockedBitmap op(env, output);
    if (!op.pixels) return;
    const auto* mask = env->GetByteArrayElements(alpha, nullptr);
    if (!mask) return;
    std::vector<uint8_t> maskX(si.width);
    for (uint32_t x = 0; x < si.width; ++x) {
        maskX[x] = static_cast<uint8_t>(std::clamp(
            static_cast<int>((left + x * resizedWidth / si.width) / 4), 0, 159));
    }
    for (uint32_t y = 0; y < si.height; ++y) {
        const auto* sr = static_cast<const uint8_t*>(sp.pixels) + y * si.stride;
        const auto* br = static_cast<const uint8_t*>(bp.pixels) + y * bi.stride;
        auto* target = static_cast<uint8_t*>(op.pixels) + y * oi.stride;
        const int my = std::clamp(static_cast<int>((top + y * resizedHeight / si.height) / 4), 0, 159);
        const auto* maskRow = reinterpret_cast<const uint8_t*>(mask) + my * 160;
        if (std::all_of(maskRow, maskRow + 160, [](uint8_t value) { return value == 0; })) {
            std::memcpy(target, sr, si.width * 4);
            continue;
        }
        if (std::all_of(maskRow, maskRow + 160, [](uint8_t value) { return value == 255; })) {
            std::memcpy(target, br, si.width * 4);
            continue;
        }
        for (uint32_t x = 0; x < si.width; ++x) {
            const unsigned coverage = maskRow[maskX[x]];
            if (coverage == 0) {
                std::memcpy(target + 4 * x, sr + 4 * x, 4);
                continue;
            }
            if (coverage == 255) {
                std::memcpy(target + 4 * x, br + 4 * x, 4);
                continue;
            }
            for (int c = 0; c < 4; ++c) {
                target[4 * x + c] = (sr[4 * x + c] * (255 - coverage) + br[4 * x + c] * coverage + 127) / 255;
            }
        }
    }
    env->ReleaseByteArrayElements(alpha, const_cast<jbyte*>(mask), JNI_ABORT);
}

// NNAPI CPU fallback is not an NPU. Probe actual exposed neural accelerators before
// compiling another copy of the detector; absence is normal on newer Android builds.
#include <android/NeuralNetworks.h>
extern "C" JNIEXPORT jint JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_neuralAcceleratorCount(
    JNIEnv*, jobject) {
    uint32_t count = 0;
    if (ANeuralNetworks_getDeviceCount(&count) != ANEURALNETWORKS_NO_ERROR) return 0;
    int accelerators = 0;
    for (uint32_t i = 0; i < count; ++i) {
        ANeuralNetworksDevice* device = nullptr;
        int32_t type = ANEURALNETWORKS_DEVICE_UNKNOWN;
        if (ANeuralNetworks_getDevice(i, &device) == ANEURALNETWORKS_NO_ERROR &&
            ANeuralNetworksDevice_getType(device, &type) == ANEURALNETWORKS_NO_ERROR &&
            type == ANEURALNETWORKS_DEVICE_ACCELERATOR) ++accelerators;
    }
    return accelerators;
}

// Vectorize across adjacent prototype pixels. Separate multiply/add preserves the
// Kotlin channel accumulation order, including values very close to the zero threshold.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_computeInstanceMasks(
    JNIEnv* env, jobject, jfloatArray coefficients, jintArray bounds, jfloatArray prototypes, jboolean prototypesValidated) {
    constexpr int pixels = 160 * 160, channels = 32;
    const int count = env->GetArrayLength(bounds) / 4;
    if (count > 100 || env->GetArrayLength(bounds) != count * 4 ||
        env->GetArrayLength(coefficients) != count * channels ||
        env->GetArrayLength(prototypes) != pixels * channels) {
        invalid(env, "Invalid instance mask buffers"); return nullptr;
    }
    const auto* proto = env->GetFloatArrayElements(prototypes, nullptr);
    if (!proto) return nullptr;
    bool valid = prototypesValidated || std::all_of(proto, proto + pixels * channels, [](float v) { return std::isfinite(v); });
    std::vector<float> weights(count * channels);
    std::vector<jint> boxes(count * 4);
    if (count) {
        env->GetFloatArrayRegion(coefficients, 0, count * channels, weights.data());
        env->GetIntArrayRegion(bounds, 0, count * 4, boxes.data());
    }
    valid = valid && std::all_of(weights.begin(), weights.end(), [](float v) { return std::isfinite(v); });
    auto arrays = valid ? env->NewObjectArray(count, env->FindClass("[B"), nullptr) : nullptr;
    if (valid && !arrays) { env->ReleaseFloatArrayElements(prototypes, const_cast<float*>(proto), JNI_ABORT); return nullptr; }
    for (int n = 0; n < count && valid && !env->ExceptionCheck(); ++n) {
        const int x0 = boxes[n * 4], y0 = boxes[n * 4 + 1];
        const int x1 = boxes[n * 4 + 2], y1 = boxes[n * 4 + 3];
        if (x0 < 0 || y0 < 0 || x1 > 160 || y1 > 160 || x0 > 160 || y0 > 160 || x1 < 0 || y1 < 0) {
            valid = false; break;
        }
        std::vector<jbyte> mask(pixels, 0);
        bool covered = false;
        for (int y = y0; y < y1 && valid; ++y) {
            int x = x0;
#if defined(__ARM_NEON) || defined(__aarch64__)
            for (; x + 4 <= x1; x += 4) {
                auto logits = vdupq_n_f32(0.f);
                for (int c = 0; c < channels; ++c) {
                    logits = vaddq_f32(logits, vmulq_n_f32(vld1q_f32(proto + c * pixels + y * 160 + x), weights[n * channels + c]));
                }
                float values[4]; vst1q_f32(values, logits);
                for (int lane = 0; lane < 4; ++lane) {
                    if (!std::isfinite(values[lane])) { valid = false; break; }
                    if (values[lane] > 0) { mask[y * 160 + x + lane] = -1; covered = true; }
                }
            }
#endif
            for (; x < x1 && valid; ++x) {
                float logit = 0;
                for (int c = 0; c < channels; ++c) logit += weights[n * channels + c] * proto[c * pixels + y * 160 + x];
                if (!std::isfinite(logit)) { valid = false; break; }
                if (logit > 0) { mask[y * 160 + x] = -1; covered = true; }
            }
        }
        if (!covered && valid && x0 < x1 && y0 < y1) for (int y = y0; y < y1; ++y)
            std::fill(mask.begin() + y * 160 + x0, mask.begin() + y * 160 + x1, -1);
        if (valid) {
            const auto bytes = env->NewByteArray(pixels);
            if (!bytes) break;
            env->SetByteArrayRegion(bytes, 0, pixels, mask.data());
            env->SetObjectArrayElement(arrays, n, bytes);
            env->DeleteLocalRef(bytes);
        }
    }
    env->ReleaseFloatArrayElements(prototypes, const_cast<float*>(proto), JNI_ABORT);
    if (!valid) { invalid(env, "Non-finite instance mask or invalid bounds"); return nullptr; }
    return arrays;
}

// Recognize adjacent NV12/NV21 camera views without reading or copying their pixels.
extern "C" JNIEXPORT jint JNICALL
Java_com_framework_innolive_feature_live_privacy_PrivacyNativePixels_cameraChromaLayout(
    JNIEnv* env,jobject,jobject u,jobject v,jint uStride,jint vStride,jint width,jint height) {
    if(width<=0 || height<=0 || uStride!=vStride || uStride<width*2 || uStride%2) return 0;
    const auto up=reinterpret_cast<uintptr_t>(env->GetDirectBufferAddress(u));
    const auto vp=reinterpret_cast<uintptr_t>(env->GetDirectBufferAddress(v));
    if(!up || !vp) return 0;
    const int64_t required=(height-1LL)*uStride+width*2LL;
    if(vp==up+1 && env->GetDirectBufferCapacity(u)>=required-1) return 1;
    if(up==vp+1 && env->GetDirectBufferCapacity(v)>=required-1) return 2;
    return 0;
}
