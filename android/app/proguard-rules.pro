# OkHttp bringt eigene Regeln mit.

# whisper.cpp (JNI): die native Seite ruft Methoden über ihren Namen auf (onProgress, isCancelled) –
# R8 darf sie nicht umbenennen, sonst NoSuchMethodError → Absturz beim Transkribieren (nur in der Release-Version).
-keep class de.hoefer.nookbot.whisper.WhisperLib { *; }
-keep interface de.hoefer.nookbot.whisper.WhisperLib$ProgressListener { *; }
-keep class * implements de.hoefer.nookbot.whisper.WhisperLib$ProgressListener { *; }
