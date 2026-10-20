package de.hoefer.nookbot.whisper

import java.io.File

/** JNI-Brücke zu whisper.cpp (app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("nook_whisper")
    }

    interface ProgressListener {
        fun onProgress(percent: Int)
        fun isCancelled(): Boolean
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun transcribe(ptr: Long, samples: FloatArray, language: String, threads: Int, listener: ProgressListener?): ByteArray?
    external fun systemInfo(): String

    /**
     * Die native Bibliothek ist für ARMv8.2 mit FP16 + Dot-Product gebaut (Handys ab ca. 2018).
     * Ältere CPUs würden mit „illegal instruction“ abstürzen – das fangen wir vorher ab.
     */
    fun isSupportedCpu(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .flatMap { it.substringAfter(':').trim().split(' ') }
            .toSet()
        "fphp" in features && "asimddp" in features
    }.getOrDefault(false)
}
