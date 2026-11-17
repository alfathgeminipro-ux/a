package com.example.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class ScannedPdfResult(
    val fileName: String,
    val pageCount: Int,
    val sizeKb: Int,
)

object PdfScanHelper {
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842
    private const val RENDER_SCALE = 2 // 1190 x 1684 crisp document resolution

    fun createCameraOutputUri(context: Context): Pair<String, Uri> {
        val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val pageId = "page_${System.currentTimeMillis()}"
        val file = File(dir, "$pageId.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return pageId to uri
    }

    fun copyPickedUriToCache(context: Context, sourceUri: Uri): String? {
        val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val pageId = "page_${System.currentTimeMillis()}"
        val destFile = File(dir, "$pageId.jpg")
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.exists() && destFile.length() > 0L) pageId else null
        } catch (_: Exception) {
            null
        }
    }

    fun buildThumbnailDataUrl(context: Context, pageId: String): String? {
        val file = File(File(context.cacheDir, "camera_captures"), "$pageId.jpg")
        if (!file.exists()) return null
        val bitmap = decodeSampledBitmap(file, maxDim = 480) ?: return null
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 76, baos)
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    fun buildMultiPagePdf(
        context: Context,
        assignmentId: String,
        pageIds: List<String>,
        enhanceHandwriting: Boolean,
    ): ScannedPdfResult? {
        if (pageIds.isEmpty()) return null
        val cacheDir = File(context.cacheDir, "camera_captures")
        val pdfDir = File(context.filesDir, "scanned_pdfs").apply { mkdirs() }

        val safeAsg = assignmentId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(32)
        val fileName = "Scan_${safeAsg}_${System.currentTimeMillis() % 100000}.pdf"
        val outFile = File(pdfDir, fileName)

        val pdfDocument = PdfDocument()
        var renderedCount = 0

        try {
            val pageW = A4_WIDTH_PT * RENDER_SCALE
            val pageH = A4_HEIGHT_PT * RENDER_SCALE
            val margin = 36 * RENDER_SCALE

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (enhanceHandwriting) {
                val cm = ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.45f
                    val translate = (-0.5f * contrast + 0.5f) * 255f + 22f
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                contrast, 0f, 0f, 0f, translate,
                                0f, contrast, 0f, 0f, translate,
                                0f, 0f, contrast, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
                paint.colorFilter = ColorMatrixColorFilter(cm)
            }

            for (pageId in pageIds) {
                val safePage = pageId.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
                val imgFile = File(cacheDir, "$safePage.jpg")
                if (!imgFile.exists()) continue
                val bmp = decodeSampledBitmap(imgFile, maxDim = 1680) ?: continue

                renderedCount++
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, renderedCount).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                canvas.save()
                canvas.scale(1f / RENDER_SCALE, 1f / RENDER_SCALE)
                canvas.drawColor(android.graphics.Color.WHITE)

                val availW = (pageW - margin * 2).toFloat()
                val availH = (pageH - margin * 2).toFloat()
                val scale = minOf(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
                val drawW = bmp.width * scale
                val drawH = bmp.height * scale
                val left = (pageW - drawW) / 2f
                val top = (pageH - drawH) / 2f

                val drawMatrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(left, top)
                }
                canvas.drawBitmap(bmp, drawMatrix, paint)
                canvas.restore()
                pdfDocument.finishPage(page)
                bmp.recycle()
            }

            if (renderedCount == 0) {
                pdfDocument.close()
                return null
            }

            FileOutputStream(outFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            val sizeKb = maxOf(1, (outFile.length() / 1024L).toInt())
            return ScannedPdfResult(fileName = fileName, pageCount = renderedCount, sizeKb = sizeKb)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                pdfDocument.close()
            } catch (_: Exception) {}
        }
    }

    fun openPdfExternal(context: Context, fileName: String): Boolean {
        val safeName = File(fileName).name
        val pdfFile = File(File(context.filesDir, "scanned_pdfs"), safeName)
        if (!pdfFile.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Buka Dokumen PDF"))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearCachedPages(context: Context) {
        val dir = File(context.cacheDir, "camera_captures")
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun decodeSampledBitmap(file: File, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val maxSide = maxOf(opts.outWidth, opts.outHeight)
        var sample = 1
        while (maxSide / sample > maxDim * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val rotation = try {
            val exif = android.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (rotation == 0f) return raw
        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }
}
