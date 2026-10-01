// The framework ExifInterface reads the one tag needed here on every supported API level (26+); the AndroidX copy
// is not worth a dependency for that.
@file:Suppress("ExifInterface")

package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.PromptImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Bytes that cannot go out as an image, with the reason worded for the composer. */
class UnreadableImageException(message: String) : IllegalArgumentException(message)

/**
 * Prepares a picked image for the API, so what an agent receives is always an image its model can read: the bytes are
 * one of [SENDABLE]'s formats, the MIME type is the one those bytes actually are, the long edge is at most
 * [MAX_EDGE_PX], and the size is at most [MAX_SEND_BYTES].
 *
 * Attachments are base64-inlined into the create / follow-up request body, or uploaded and named by reference on the
 * account path (Extended mode, Project coordinators), and both end up in front of a vision model that downsamples to
 * roughly [MAX_EDGE_PX] anyway — so a 12 MP photo or a full-resolution phone screenshot is decoded, oriented, scaled
 * to fit and re-encoded. Only an image that already meets every condition above, is no heavier than
 * [PASSTHROUGH_MAX_BYTES] (or a GIF within [MAX_SEND_BYTES], for its animation) and is intact — nothing after its
 * end marker, as a Samsung screenshot's `SEFT` trailer is — goes out byte for byte. Anything that will not decode,
 * or is no format the API takes, is refused with an [UnreadableImageException] rather than sent as it is.
 */
object AttachmentImages {
    /** Long-edge ceiling in pixels; matches what vision models consume, so nothing the model would see is lost. */
    const val MAX_EDGE_PX = 1568
    /** Images at or under this many bytes that already fit [MAX_EDGE_PX] are sent as picked. */
    const val PASSTHROUGH_MAX_BYTES = 768 * 1024
    /**
     * The most an image may weigh as sent: 3 MB is 4 MB of base64, inside both cursor.com/agents' 4 MB per image
     * (the account path's limit) and the 5 MB of base64 a vision model takes per image.
     */
    const val MAX_SEND_BYTES = 3 * 1024 * 1024
    const val JPEG_QUALITY = 88

    /** The formats the API takes as an image (`image/png`, `image/jpeg`, `image/gif`, `image/webp`). */
    private val SENDABLE = setOf(FileFormat.PNG, FileFormat.JPEG, FileFormat.GIF, FileFormat.WEBP)

    /** Lower JPEG qualities tried, in turn, when an encode at [JPEG_QUALITY] is still over the byte ceiling. */
    private val FALLBACK_QUALITIES = intArrayOf(80, 70, 60)

    /** Whether [bytes] are a picture [prepare] turns into a prompt image, by what they are rather than what they are called. */
    fun isPicture(bytes: ByteArray): Boolean = FileFormat.sniff(bytes) in SENDABLE

    /**
     * [bytes] as the prompt carries them. [declaredMime] is only what the picker or clipboard said, used to name a
     * refusal: the bytes decide the format. [maxBytes] is the byte ceiling, [MAX_SEND_BYTES] outside tests.
     */
    fun prepare(bytes: ByteArray, declaredMime: String?, maxBytes: Int = MAX_SEND_BYTES): PromptImage {
        val format = FileFormat.sniff(bytes)?.takeIf { it in SENDABLE }
            ?: throw UnreadableImageException("Unsupported image type (${declaredMime?.takeIf { it.isNotBlank() } ?: "unknown"}). Use PNG, JPEG, GIF or WebP.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw unreadable(format)
        val orientation = exifOrientation(bytes, format)
        val sendable = isIntact(bytes, format) && orientation == ExifInterface.ORIENTATION_NORMAL && longEdge <= MAX_EDGE_PX
        if (sendable && (bytes.size <= PASSTHROUGH_MAX_BYTES || format == FileFormat.GIF) && bytes.size <= maxBytes) {
            return PromptImage(bytes, format.mimeType)
        }

        // Decode at the largest power-of-two reduction that still leaves at least MAX_EDGE_PX, then scale exactly.
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw unreadable(format)
        val fitted = decoded.oriented(orientation).fitWithin(MAX_EDGE_PX)
        val encoded = encode(fitted, maxBytes)
        // Never make it worse: an intact original that only exceeded the passthrough weight stays as it was.
        return if (sendable && bytes.size <= maxBytes && bytes.size <= encoded.sizeBytes) PromptImage(bytes, format.mimeType) else encoded
    }

    private fun unreadable(format: FileFormat) =
        UnreadableImageException("Couldn't read this ${format.label}. Re-save it as PNG or JPEG and attach it again.")

    /**
     * Whether the file ends where its format says it does. Phones append their own records after the end marker (a
     * Samsung screenshot's `SEFT` trailer after a PNG's `IEND`, the same after a camera JPEG's EOI), which lenient
     * decoders skip and strict ones refuse; such a file is re-encoded rather than sent with them.
     */
    internal fun isIntact(bytes: ByteArray, format: FileFormat): Boolean = when (format) {
        FileFormat.PNG -> bytes.size >= PNG_IEND.size + 8 && PNG_IEND.indices.all { bytes[bytes.size - PNG_IEND.size + it] == PNG_IEND[it] }
        FileFormat.JPEG -> bytes.size >= 4 && bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
        FileFormat.GIF -> bytes.isNotEmpty() && bytes.last() == 0x3B.toByte()
        FileFormat.WEBP -> bytes.size >= 12 && riffLength(bytes).let { it == bytes.size.toLong() || it + 1 == bytes.size.toLong() }
        else -> false
    }

    /** The RIFF container's own length: its little-endian chunk size at 4, plus the 8 bytes of the header it does not count. */
    private fun riffLength(bytes: ByteArray): Long =
        (0..3).fold(0L) { acc, i -> acc or ((bytes[4 + i].toLong() and 0xFF) shl (8 * i)) } + 8

    /** [bitmap] flattened onto white and written as JPEG within [maxBytes], stepping the quality down and then the size until it fits. */
    private fun encode(bitmap: Bitmap, maxBytes: Int): PromptImage {
        var current = if (bitmap.hasAlpha()) bitmap.flattenOnWhite() else bitmap
        while (true) {
            for (quality in intArrayOf(JPEG_QUALITY) + FALLBACK_QUALITIES) {
                val jpeg = current.compressed(Bitmap.CompressFormat.JPEG, quality)
                if (jpeg.isNotEmpty() && jpeg.size <= maxBytes) return PromptImage(jpeg, FileFormat.JPEG.mimeType)
            }
            if (max(current.width, current.height) <= MIN_EDGE_PX) throw UnreadableImageException("This image is too detailed to send. Crop it and try again.")
            current = current.fitWithin((max(current.width, current.height) * 3) / 4)
        }
    }

    private fun Bitmap.compressed(format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    private fun exifOrientation(bytes: ByteArray, format: FileFormat): Int {
        if (format != FileFormat.JPEG) return ExifInterface.ORIENTATION_NORMAL
        return runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            .let { if (it == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else it }
    }

    /** Bakes the EXIF orientation into the pixels; a re-encoded JPEG carries no EXIF, so it must not rely on it. */
    private fun Bitmap.oriented(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.fitWithin(maxEdge: Int): Bitmap {
        val edge = max(width, height)
        if (edge <= maxEdge) return this
        val factor = maxEdge.toFloat() / edge
        return scale((width * factor).roundToInt().coerceAtLeast(1), (height * factor).roundToInt().coerceAtLeast(1))
    }

    /** JPEG has no alpha; transparent regions would otherwise come out black. */
    private fun Bitmap.flattenOnWhite(): Bitmap {
        val flat = createBitmap(width, height)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(this, 0f, 0f, null)
        return flat
    }

    /** A PNG's last chunk: `IEND`, empty, with its fixed CRC. */
    private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    /** The smallest long edge the size steps go down to before an image is refused as too heavy to send. */
    private const val MIN_EDGE_PX = 256
}
