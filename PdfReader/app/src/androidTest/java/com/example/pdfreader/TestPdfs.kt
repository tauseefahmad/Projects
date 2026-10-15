package com.example.pdfreader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Builds small PDFs on the device for tests, shared through the app's FileProvider. */
object TestPdfs {

    fun create(context: Context, name: String, pageCount: Int, width: Int = 595, height: Int = 842): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        val document = android.graphics.pdf.PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
        }
        repeat(pageCount) { index ->
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, index + 1).create())
            page.canvas.drawText("Page ${index + 1}", 72f, 144f, paint)
            document.finishPage(page)
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
