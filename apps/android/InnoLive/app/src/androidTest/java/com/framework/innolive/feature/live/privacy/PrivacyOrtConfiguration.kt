package com.framework.innolive.feature.live.privacy

import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet

/** Explicit alternatives for device comparison; model weights and inference contracts stay identical. */
internal enum class PrivacyOrtConfiguration {
    DEFAULT, CPU_TWO, CPU_FOUR, CPU_EIGHT, CPU_EIGHT_SPIN, XNNPACK_FOUR, NNAPI_FOUR, NNAPI_ALLOW_CPU;

    fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().also { options ->
        try {
            when (this) {
                DEFAULT -> Unit
                CPU_TWO -> options.setIntraOpNumThreads(2)
                CPU_FOUR -> options.setIntraOpNumThreads(4)
                CPU_EIGHT, CPU_EIGHT_SPIN -> options.setIntraOpNumThreads(8)
                XNNPACK_FOUR -> {
                    options.setIntraOpNumThreads(1)
                    options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                }
                NNAPI_FOUR -> {
                    options.setIntraOpNumThreads(4)
                    options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                }
                NNAPI_ALLOW_CPU -> { options.setIntraOpNumThreads(4); options.addNnapi() }
            }
            if (this != DEFAULT && this != CPU_EIGHT_SPIN) {
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.inter_op.allow_spinning", "0")
            }
        } catch (error: Exception) { options.close(); throw error }
    }
}
