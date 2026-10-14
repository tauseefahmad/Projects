package de.jobcopilot.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import de.jobcopilot.app.domain.CoverLetter
import de.jobcopilot.app.domain.FitResult
import de.jobcopilot.app.domain.Job
import de.jobcopilot.app.domain.LetterLanguage
import de.jobcopilot.app.domain.Profile
import java.io.File

/**
 * Renders the Anschreiben in DIN 5008 Form B layout and a tailored one-page CV, both A4.
 * Coordinates are in millimetres and converted to PDF points (1 mm = 72 / 25.4 pt).
 */
class PdfExporter(private val context: Context) {

    private fun mm(v: Float) = v * 72f / 25.4f

    private val pageWidth = 595
    private val pageHeight = 842
    private val left = mm(25f)
    private val right = mm(20f)
    private val textWidth = pageWidth - left - right
    private val bottom = pageHeight - mm(25f)

    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
    private val bold = TextPaint(body).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val small = TextPaint(body).apply { textSize = 7f; color = Color.DKGRAY }
    private val title = TextPaint(bold).apply { textSize = 18f }
    private val section = TextPaint(bold).apply { textSize = 12f; color = Color.rgb(0x1F, 0x4E, 0x79) }
    private val rule = Paint().apply { color = Color.GRAY; strokeWidth = 0.5f }

    /** Simple flowing writer that starts new pages when needed. */
    private inner class Writer(private val doc: PdfDocument) {
        private var pageNo = 0
        lateinit var page: PdfDocument.Page
        val canvas: Canvas get() = page.canvas
        var y = 0f

        fun newPage(top: Float = mm(20f)) {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
            y = top
        }

        /** Draws wrapped text starting at the current y (top of first line), breaking pages between lines. */
        fun paragraph(text: String, paint: TextPaint = body, x: Float = left, width: Float = textWidth, spacingAfter: Float = 0f) {
            if (text.isEmpty()) { y += paint.fontSpacing; return }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()
            for (i in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(i) - layout.getLineTop(i)).toFloat()
                if (y + h > bottom) newPage()
                val line = text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd('\n')
                canvas.drawText(line, x, y - layout.getLineAscent(i), paint)
                y += h
            }
            y += spacingAfter
        }

        fun finish() = doc.finishPage(page)
    }

    fun exportLetter(letter: CoverLetter, profile: Profile, job: Job): File {
        val doc = PdfDocument()
        val w = Writer(doc)
        w.newPage(top = mm(15f))
        val c = w.canvas

        // Letterhead (Briefkopf, 0–45 mm).
        w.paragraph(profile.fullName.ifBlank { " " }, title)
        w.paragraph(listOf(profile.street, profile.postalCity).filter { it.isNotBlank() }.joinToString(" · "), body)
        w.paragraph(listOf(profile.phone, profile.email).filter { it.isNotBlank() }.joinToString(" · "), body)

        // Address field (Anschriftfeld) at 45 mm, 85 × 45 mm; return line in the top zone.
        val returnLine = listOf(profile.fullName, profile.street, profile.postalCity).filter { it.isNotBlank() }.joinToString(", ")
        c.drawText(returnLine.take(70), left, mm(59f), small)
        c.drawLine(left, mm(60f), left + mm(80f), mm(60f), rule)
        w.y = mm(63f)
        w.paragraph(job.company.ifBlank { "Personalabteilung" }, body)
        if (letter.language == LetterLanguage.DE) w.paragraph("Personalabteilung", body)
        w.paragraph(job.location, body)

        // Information block (Informationsblock) at 125 mm.
        val infoX = mm(125f)
        val infoW = pageWidth - infoX - right
        w.y = mm(50f)
        val isDe = letter.language == LetterLanguage.DE
        if (profile.phone.isNotBlank()) w.paragraph("${if (isDe) "Telefon" else "Phone"}: ${profile.phone}", body, infoX, infoW)
        if (profile.email.isNotBlank()) w.paragraph("E-Mail: ${profile.email}", body, infoX, infoW)
        w.y += body.fontSpacing
        w.paragraph(letter.dateLine(profile.postalCity), body, infoX, infoW)

        // Fold and hole marks (Falzmarken 105/210 mm, Lochmarke 148.5 mm).
        listOf(105f, 210f).forEach { c.drawLine(0f, mm(it), mm(5f), mm(it), rule) }
        c.drawLine(0f, mm(148.5f), mm(7f), mm(148.5f), rule)

        // Subject at ~98 mm, then salutation, body, closing.
        w.y = mm(98.5f)
        w.paragraph(letter.subject, bold, spacingAfter = body.fontSpacing * 2)
        w.paragraph(letter.salutation, spacingAfter = body.fontSpacing)
        letter.body.split(Regex("\\n\\s*\\n")).forEach { w.paragraph(it.trim(), spacingAfter = body.fontSpacing) }
        w.paragraph(letter.closing, spacingAfter = body.fontSpacing * 3)
        w.paragraph(profile.fullName, spacingAfter = body.fontSpacing * 2)
        w.paragraph(if (isDe) "Anlagen: Lebenslauf, Zeugnisse" else "Enclosures: CV, references", body)
        w.finish()

        return write(doc, "Anschreiben_${safe(job.company)}_${safe(job.title)}.pdf")
    }

    fun exportCv(profile: Profile, job: Job, fit: FitResult, language: LetterLanguage): File {
        val de = language == LetterLanguage.DE
        val doc = PdfDocument()
        val w = Writer(doc)
        w.newPage()

        w.paragraph(profile.fullName.ifBlank { if (de) "Lebenslauf" else "Curriculum Vitae" }, title, spacingAfter = 4f)
        if (profile.headline.isNotBlank()) w.paragraph(profile.headline, bold)
        w.paragraph(
            listOf(profile.street, profile.postalCity, profile.phone, profile.email).filter { it.isNotBlank() }.joinToString(" · "),
            body, spacingAfter = 8f,
        )

        fun header(text: String) {
            w.y += 6f
            w.paragraph(text, section)
            w.canvas.drawLine(left, w.y + 1f, pageWidth - right, w.y + 1f, rule)
            w.y += 6f
        }

        if (profile.summary.isNotBlank()) {
            header(if (de) "Profil" else "Profile")
            w.paragraph(profile.summary)
        }

        // Tailoring: skills that match this posting come first.
        val ordered = (fit.matchedSkills + profile.skills.filterNot { s -> fit.matchedSkills.any { it.equals(s, true) } }).distinct()
        if (ordered.isNotEmpty()) {
            header(if (de) "Kernkompetenzen" else "Key skills")
            w.paragraph(ordered.joinToString(" · "))
        }
        if (profile.experience.isNotBlank()) {
            header(if (de) "Berufserfahrung" else "Experience")
            profile.experience.split(Regex("\\n\\s*\\n")).forEach { w.paragraph(it.trim(), spacingAfter = 4f) }
        }
        if (profile.education.isNotBlank()) {
            header(if (de) "Ausbildung" else "Education")
            profile.education.split(Regex("\\n\\s*\\n")).forEach { w.paragraph(it.trim(), spacingAfter = 4f) }
        }
        if (profile.languages.isNotEmpty()) {
            header(if (de) "Sprachen" else "Languages")
            w.paragraph(profile.languages.joinToString(" · ") { "${it.language}: ${it.level.label}" })
        }
        w.finish()
        return write(doc, "${if (de) "Lebenslauf" else "CV"}_${safe(profile.fullName)}_${safe(job.company)}.pdf")
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß]+"), "_").trim('_').take(40).ifBlank { "x" }

    private fun write(doc: PdfDocument, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun clearExports() {
        File(context.cacheDir, "exports").deleteRecursively()
    }
}
