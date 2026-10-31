package com.framework.innolive.feature.live.privacy

/**
 * Ends one on-device processing attempt without ending the process. JNI linkage failures
 * (a class or constructor stripped by R8, a native library that did not load) count like
 * exceptions; virtual machine errors such as OutOfMemoryError still propagate.
 */
internal fun Throwable.isPrivacyProcessingFailure(): Boolean = this is Exception || this is LinkageError

/**
 * A frame whose content cannot be protected, such as a crowd beyond the object limit. The frame still
 * fails closed, but the GPU graph and native input stay eligible for retry once the scene changes.
 */
internal class PrivacyFrameContentException(message: String?, cause: Throwable? = null) :
    IllegalStateException(message, cause)
