package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.PromptImage
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/** Real encoding and decoding through Robolectric's native renderer, the same one the screenshot tests use. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttachmentImagesTest {

    /**
     * A screenshot-like fixture: dark UI with light text, plus per-pixel noise over the top-left [noisyFraction] of
     * the image so PNG has real work to do (a flat synthetic image compresses to almost nothing, unlike a screenshot
     * of a real UI with imagery and anti-aliasing). With [alpha] the background stays transparent outside the noise.
     */
    private fun bitmap(width: Int, height: Int, alpha: Boolean = false, noisyFraction: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (!alpha) canvas.drawColor(Color.rgb(30, 30, 30))
        val paint = Paint().apply { color = Color.rgb(240, 240, 240); textSize = height / 12f }
        for (y in 0 until height step max(1, height / 12)) canvas.drawText("merge and chat states often fail to sync", 8f, y.toFloat(), paint)
        val nw = (width * noisyFraction).toInt().coerceIn(1, width)
        val nh = (height * noisyFraction).toInt().coerceIn(1, height)
        val region = IntArray(nw * nh)
        bmp.getPixels(region, 0, nw, 0, 0, nw, nh)
        val random = Random(42)
        for (i in region.indices) {
            val p = region[i]
            val d = random.nextInt(-14, 15)
            region[i] = Color.argb(255, (Color.red(p) + d).coerceIn(0, 255), (Color.green(p) + d).coerceIn(0, 255), (Color.blue(p) + d).coerceIn(0, 255))
        }
        bmp.setPixels(region, 0, nw, 0, 0, nw, nh)
        return bmp
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat, quality: Int = 100): ByteArray =
        ByteArrayOutputStream().also { compress(format, quality, it) }.toByteArray()

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** What every prepared image has to be for the agent to receive a picture: a format the API takes, named as what it is, intact, within the edge and the weight. */
    private fun assertReceivable(image: PromptImage, maxBytes: Int = AttachmentImages.MAX_SEND_BYTES) {
        val format = FileFormat.sniff(image.bytes)
        assertThat(format).isIn(listOf(FileFormat.PNG, FileFormat.JPEG, FileFormat.GIF, FileFormat.WEBP))
        assertThat(image.mimeType).isEqualTo(format!!.mimeType)
        assertThat(AttachmentImages.isIntact(image.bytes, format)).isTrue()
        val (w, h) = dimensions(image.bytes)
        assertThat(w).isGreaterThan(0)
        assertThat(max(w, h)).isAtMost(AttachmentImages.MAX_EDGE_PX)
        assertThat(image.sizeBytes).isAtMost(maxBytes)
        assertThat(BitmapFactory.decodeByteArray(image.bytes, 0, image.sizeBytes)).isNotNull()
    }

    /** A Samsung screenshot's tail as One UI writes it after `IEND`: `SEFH` index records and the `SEFT` marker. */
    private val samsungTrailer: ByteArray =
        "\u0000\u0000Q\u000C\u0014\u0000\u0000\u0000Samsung_Capture_InfoScreenshot\u0000\u0000SEFHk\u0000\u0000\u0000\u0002\u0000\u0000\u0000SEFT"
            .toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a phone screenshot is scaled to the model's ceiling and re-encoded as a much smaller JPEG`() {
        val original = bitmap(1440, 3200).encode(Bitmap.CompressFormat.PNG)
        assertThat(original.size).isGreaterThan(AttachmentImages.PASSTHROUGH_MAX_BYTES)

        val prepared = AttachmentImages.prepare(original, "image/png")

        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val (w, h) = dimensions(prepared.bytes)
        assertThat(h).isEqualTo(AttachmentImages.MAX_EDGE_PX)
        assertThat(w).isEqualTo(706) // 1440 * 1568 / 3200, aspect ratio kept
        assertThat(prepared.sizeBytes).isLessThan(original.size / 4)
        assertReceivable(prepared)
    }

    @Test
    fun `a photo wider than the ceiling is downscaled and keeps its aspect ratio`() {
        val original = bitmap(3200, 2400).encode(Bitmap.CompressFormat.JPEG, 92)
        val prepared = AttachmentImages.prepare(original, "image/jpeg")
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val (w, h) = dimensions(prepared.bytes)
        assertThat(w).isEqualTo(AttachmentImages.MAX_EDGE_PX)
        assertThat(h).isEqualTo(1176)
        assertThat(prepared.sizeBytes).isLessThan(original.size)
        assertReceivable(prepared)
    }

    @Test
    fun `small intact images and GIFs pass through untouched`() {
        val small = bitmap(640, 360, noisyFraction = 0.25f).encode(Bitmap.CompressFormat.PNG)
        assertThat(small.size).isAtMost(AttachmentImages.PASSTHROUGH_MAX_BYTES)
        val prepared = AttachmentImages.prepare(small, "image/png")
        assertThat(prepared.bytes).isEqualTo(small)
        assertThat(prepared.mimeType).isEqualTo("image/png")

        val animated = AttachmentImages.prepare(TINY_GIF, "image/GIF")
        assertThat(animated.bytes).isSameInstanceAs(TINY_GIF)
        assertThat(animated.mimeType).isEqualTo("image/gif")
    }

    /**
     * The report: a Samsung screenshot is a valid PNG with records after its `IEND`, which a strict decoder refuses.
     * Sent byte for byte it reached the agent as an image it could not open; it is re-encoded instead.
     */
    @Test
    fun `a PNG with data after its end marker is re-encoded rather than sent as it is`() {
        val clean = bitmap(720, 1400, noisyFraction = 0.1f).encode(Bitmap.CompressFormat.PNG)
        assertThat(clean.size).isAtMost(AttachmentImages.PASSTHROUGH_MAX_BYTES)
        val samsung = clean + samsungTrailer
        assertThat(AttachmentImages.isIntact(samsung, FileFormat.PNG)).isFalse()

        val prepared = AttachmentImages.prepare(samsung, "image/png")

        assertThat(prepared.bytes).isNotEqualTo(samsung)
        assertThat(String(prepared.bytes, Charsets.ISO_8859_1)).doesNotContain("SEFT")
        assertThat(dimensions(prepared.bytes)).isEqualTo(720 to 1400)
        assertReceivable(prepared)
    }

    @Test
    fun `a camera JPEG with a trailer after its EOI is re-encoded`() {
        val photo = bitmap(800, 600, noisyFraction = 0.05f).encode(Bitmap.CompressFormat.JPEG, 80)
        assertThat(AttachmentImages.isIntact(photo, FileFormat.JPEG)).isTrue()
        val prepared = AttachmentImages.prepare(photo + samsungTrailer, "image/jpeg")
        assertThat(String(prepared.bytes, Charsets.ISO_8859_1)).doesNotContain("SEFT")
        assertReceivable(prepared)
    }

    /** The model refuses an image whose bytes are not the type named beside them; the bytes decide here. */
    @Test
    fun `the type sent is the one the bytes are, whatever the picker or clipboard declared`() {
        val jpeg = bitmap(320, 240, noisyFraction = 0.1f).encode(Bitmap.CompressFormat.JPEG, 85)
        assertThat(AttachmentImages.prepare(jpeg, "image/png").mimeType).isEqualTo("image/jpeg")
        assertThat(AttachmentImages.prepare(jpeg, "image/*").mimeType).isEqualTo("image/jpeg")
        assertThat(AttachmentImages.prepare(jpeg, null).mimeType).isEqualTo("image/jpeg")

        val webp = bitmap(320, 240, noisyFraction = 0.1f).encode(Bitmap.CompressFormat.WEBP_LOSSY, 80)
        val prepared = AttachmentImages.prepare(webp, "image/png")
        assertThat(prepared.mimeType).isEqualTo("image/webp")
        assertThat(prepared.bytes).isEqualTo(webp)
    }

    @Test
    fun `transparency too heavy for PNG is flattened onto white rather than black`() {
        val original = bitmap(2400, 2400, alpha = true, noisyFraction = 0.5f).encode(Bitmap.CompressFormat.PNG)
        val ceiling = 400 * 1024
        val prepared = AttachmentImages.prepare(original, "image/png", maxBytes = ceiling)
        assertThat(prepared.mimeType).isEqualTo("image/jpeg")
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.sizeBytes)
        val corner = decoded.getPixel(decoded.width - 1, decoded.height - 1)
        assertThat(Color.red(corner)).isAtLeast(250)
        assertThat(Color.green(corner)).isAtLeast(250)
        assertThat(Color.blue(corner)).isAtLeast(250)
        assertReceivable(prepared, ceiling)
    }

    /** The account path takes at most 4 MB an image and the model 5 MB of base64: nothing heavier ever goes out. */
    @Test
    fun `an image still over the byte ceiling at the edge cap steps its quality and then its size down to fit`() {
        val original = bitmap(1500, 1500).encode(Bitmap.CompressFormat.PNG)
        val ceiling = 120 * 1024
        val prepared = AttachmentImages.prepare(original, "image/png", maxBytes = ceiling)
        assertReceivable(prepared, ceiling)
    }

    /** Before, anything that would not decode went out as it was picked, under the type it was declared as. */
    @Test
    fun `bytes that are no image, or an image that will not decode, are refused with an actionable reason`() {
        val video = assertThrows(UnreadableImageException::class.java) { AttachmentImages.prepare(ByteArray(64) { 0x1F }, "video/mp4") }
        assertThat(video.message).isEqualTo("Unsupported image type (video/mp4). Use PNG, JPEG, GIF or WebP.")

        val truncated = bitmap(400, 300).encode(Bitmap.CompressFormat.PNG).copyOf(40)
        val broken = assertThrows(UnreadableImageException::class.java) { AttachmentImages.prepare(truncated, "image/png") }
        assertThat(broken.message).isEqualTo("Couldn't read this PNG image. Re-save it as PNG or JPEG and attach it again.")

        val junkGif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) + ByteArray(2 * 1024 * 1024)
        assertThrows(UnreadableImageException::class.java) { AttachmentImages.prepare(junkGif, "image/gif") }

        val heicHeader = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(4) + "mif1heic".toByteArray() + ByteArray(64)
        assertThat(FileFormat.sniff(heicHeader)).isEqualTo(FileFormat.HEIC)
        val heic = assertThrows(UnreadableImageException::class.java) { AttachmentImages.prepare(heicHeader, "image/heic") }
        assertThat(heic.message).isEqualTo("Unsupported image type (image/heic). Use PNG, JPEG, GIF or WebP.")
    }

    @Test
    fun `EXIF orientation is baked into the pixels of a re-encoded photo`() {
        val file = File.createTempFile("attachment", ".jpg")
        try {
            file.writeBytes(bitmap(3000, 2000).encode(Bitmap.CompressFormat.JPEG, 90))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val original = file.readBytes()
            assertThat(ExifInterface(original.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)

            val prepared = AttachmentImages.prepare(original, "image/jpeg")

            // Landscape 3:2 rotated by 90° comes out portrait 2:3, so the long edge is now the height.
            val (w, h) = dimensions(prepared.bytes)
            assertThat(h).isEqualTo(AttachmentImages.MAX_EDGE_PX)
            assertThat(w).isEqualTo(1045)
        } finally {
            file.delete()
        }
    }

    /** A small photo that only needs turning is turned too: a model is not obliged to read the EXIF tag. */
    @Test
    fun `a small rotated JPEG is not passed through with its orientation left to the reader`() {
        val file = File.createTempFile("attachment", ".jpg")
        try {
            file.writeBytes(bitmap(400, 200, noisyFraction = 0.1f).encode(Bitmap.CompressFormat.JPEG, 80))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
                saveAttributes()
            }
            val prepared = AttachmentImages.prepare(file.readBytes(), "image/jpeg")
            assertThat(dimensions(prepared.bytes)).isEqualTo(200 to 400)
            assertReceivable(prepared)
        } finally {
            file.delete()
        }
    }

    private companion object {
        val TINY_GIF: ByteArray = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
    }
}
