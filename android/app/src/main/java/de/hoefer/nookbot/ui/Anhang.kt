package de.hoefer.nookbot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/** Eine Datei vom Handy, fertig zum Hochladen: Name, Inhalt, bei Bildern der erkannte Text. */
class Datei(val name: String, val bytes: ByteArray, val ocr: String = "") {
    val b64: String get() = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

/** Was im Chat schon hochgeladen ist und mit der nächsten Nachricht mitgeht. */
data class Anhang(val name: String, val zeile: String, val bild: Boolean)

private val BILD = Regex("""(?i)\.(jpe?g|png|webp|heic|heif|gif)$""")
private const val MAX_BYTES = 20_000_000
private const val BILD_KANTE = 2048

object Dateien {
    /** Ziel für die Kamera-App (FileProvider, nur der Cache-Ordner „kamera“). */
    fun kameraUri(ctx: Context): Pair<File, Uri> {
        val f = File(File(ctx.cacheDir, "kamera").apply { mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(ctx, ctx.packageName + ".dateien", f)
    }

    fun name(ctx: Context, uri: Uri): String {
        var n = uri.lastPathSegment?.substringAfterLast('/') ?: "datei"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { n = c.getString(it) ?: n }
        }
        return n
    }

    /**
     * Datei lesen. Bilder werden verkleinert (lange Kante 2048 px, JPEG – Drehung aus EXIF übernimmt ImageDecoder)
     * und auf dem Handy nach Text durchsucht, damit auch Modelle ohne Bildverständnis etwas damit anfangen können.
     */
    suspend fun lesen(ctx: Context, uri: Uri, nameVorgabe: String? = null): Datei = withContext(Dispatchers.IO) {
        val name = nameVorgabe ?: name(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("image/") || BILD.containsMatchIn(name)) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val f = minOf(1.0, BILD_KANTE.toDouble() / maxOf(w, h))
                dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val text = runCatching { texterkennung(bmp) }.getOrDefault("")
            Datei(name.replace(BILD, "") + ".jpg", out.toByteArray(), text)
        } else {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { s ->
                val buf = ByteArrayOutputStream()
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    buf.write(b, 0, n)
                    if (buf.size() > MAX_BYTES) error("Datei zu groß (max. 20 MB)")
                }
                buf.toByteArray()
            }
            Datei(name, bytes)
        }
    }

    private suspend fun texterkennung(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        val r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        r.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it.text.trim()) }
            .addOnFailureListener { cont.resume("") }
            .addOnCompleteListener { r.close() }
    }
}
