package com.framework.innolive.feature.live.privacy

/**
 * Ends one on-device processing attempt without ending the process. JNI linkage failures
 * (a class or constructor stripped by R8, a native library that did not load) count like
 * exceptions; virtual machine errors such as OutOfMemoryError still propagate.
 */
internal fun Throwable.isPrivacyProcessingFailure(): Boolean = this is Exception || this is LinkageError
