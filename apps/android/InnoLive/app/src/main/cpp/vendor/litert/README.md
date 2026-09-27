# LiteRT C API headers

Unmodified public headers from Google LiteRT v2.2.0 `litert_cc_sdk.zip`.
Source: https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_cc_sdk.zip
SDK ZIP SHA-256: 0aa619d80aef27303ad9c6e3759a20110f77e7b11ade9b68061b8c6e5904b0c6

Each header retains its Apache-2.0 copyright/license notice. Only the dependency
closure of the C API used by privacy inference is included, without vendor binaries.
CMake generates `litert/build_common/build_config.h` from the original template.
Runtime symbols are resolved from the same pinned Maven `libLiteRt.so` already
packaged in the APK; no Kotlin internal handles are accessed.

GL input synchronization follows the pinned backend's
[GlInteropFabricLiteRt::Start](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/ml_drift_delegate/delegate/gpu_backend_opencl_litert.cc):
it creates the EGL fence internally, converts it into a CL queue dependency when
supported, and otherwise waits on that fence. External EGLSyncFence tensor events
are explicitly rejected by this backend. The bridge flushes GL and calls the
synchronous C API; completed output reads precede SSBO reuse. No asynchronous
Adreno execution or private CL context access is assumed.
