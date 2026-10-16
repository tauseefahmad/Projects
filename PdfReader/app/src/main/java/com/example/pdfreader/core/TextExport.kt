package com.example.pdfreader.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Formats a document's text can be exported to. */
enum class TextFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    HTML("html", "text/html"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}

/**
 * Writes the text of a PDF (one string per page, lines separated by '\n') as plain text,
 * Markdown, HTML or a Word document. Layout is not kept: each page becomes a section with
 * its paragraphs.
 */
object TextExport {

    /** Paragraphs of a page: lines joined, broken at blank lines and at lines ending a sentence early. */
    fun paragraphs(page: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        val lines = page.replace("\r", "").split('\n').map { it.trim() }
        val longest = lines.maxOfOrNull { it.length } ?: 0
        for (line in lines) {
            if (line.isEmpty()) {
                if (current.isNotEmpty()) result += current.toString()
                current.clear()
                continue
            }
            if (current.isNotEmpty()) {
                // Words split with a hyphen at the end of a line are joined again.
                if (current.endsWith("-") && line.first().isLowerCase()) current.setLength(current.length - 1) else current.append(' ')
            }
            current.append(line)
            // A clearly short line that ends a sentence finishes the paragraph.
            if (line.length < longest * 0.7 && line.last() in ".:!?") {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    fun text(pages: List<String>): String = pages.joinToString("\n\n\u000C\n\n") { paragraphs(it).joinToString("\n\n") }.trim() + "\n"

    fun markdown(title: String, pages: List<String>): String = buildString {
        append("# ").append(escapeMarkdown(title)).append("\n")
        pages.forEachIndexed { i, page ->
            append("\n## Page ").append(i + 1).append("\n\n")
            for (p in paragraphs(page)) append(escapeMarkdown(p)).append("\n\n")
        }
    }.trimEnd() + "\n"

    fun html(title: String, pages: List<String>): String = buildString {
        append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>").append(escapeXml(title)).append("</title>\n</head>\n<body>\n")
        append("<h1>").append(escapeXml(title)).append("</h1>\n")
        pages.forEachIndexed { i, page ->
            append("<section id=\"page-").append(i + 1).append("\">\n<h2>Page ").append(i + 1).append("</h2>\n")
            for (p in paragraphs(page)) append("<p>").append(escapeXml(p)).append("</p>\n")
            append("</section>\n")
        }
        append("</body>\n</html>\n")
    }

    /** A minimal but valid .docx: a heading per page, its paragraphs, and a page break between pages. */
    fun docx(title: String, pages: List<String>, output: OutputStream) {
        val body = StringBuilder()
        fun paragraph(text: String, style: String? = null) {
            body.append("<w:p>")
            if (style != null) body.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
            body.append("<w:r><w:t xml:space=\"preserve\">").append(escapeXml(text)).append("</w:t></w:r></w:p>")
        }
        paragraph(title, "Title")
        pages.forEachIndexed { i, page ->
            if (i > 0) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            paragraph("Page ${i + 1}", "Heading1")
            for (p in paragraphs(page)) paragraph(p)
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:spacing w:after="160"/></w:pPr><w:rPr><w:sz w:val="22"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style></w:styles>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val documentRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        ZipOutputStream(output).use { zip ->
            for ((name, content) in listOf(
                "[Content_Types].xml" to contentTypes,
                "_rels/.rels" to rels,
                "word/document.xml" to document,
                "word/_rels/document.xml.rels" to documentRels,
                "word/styles.xml" to styles,
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** Escapes text for XML and HTML; characters XML can't hold are dropped. */
    fun escapeXml(text: String): String = buildString {
        for (c in text) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c < ' ' && c != '\t' && c != '\n' -> Unit
            else -> append(c)
        }
    }

    private fun escapeMarkdown(text: String): String =
        text.replace(Regex("""([\\`*_\[\]#|])"""), "\\\\$1").replace(Regex("""^(\d+)\. """), "$1\\\\. ")
}

/** A word on a page with its box (points, top-left origin). */
data class PositionedWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Finds a table in the words of a page area: rows are words sharing a line, columns are
 * vertical gaps that no row crosses. Good for simple grid tables (statements, invoices).
 */
object TableExtraction {

    fun rows(words: List<PositionedWord>): List<List<String>> {
        if (words.isEmpty()) return emptyList()
        // Group into lines.
        val sorted = words.sortedWith(compareBy({ (it.top + it.bottom) / 2 }, { it.left }))
        val lines = mutableListOf<MutableList<PositionedWord>>()
        for (w in sorted) {
            val mid = (w.top + w.bottom) / 2
            val line = lines.lastOrNull()
            if (line != null && line.any { mid >= it.top && mid <= it.bottom }) line += w else lines += mutableListOf(w)
        }
        // Column boundaries: x positions not covered by any word, with a minimum gap.
        val minX = words.minOf { it.left }
        val maxX = words.maxOf { it.right }
        val height = words.map { it.bottom - it.top }.sorted()[words.size / 2]
        val minGap = height * 0.8f
        val spans = words.map { it.left to it.right }.sortedBy { it.first }
        val gaps = mutableListOf<Float>()
        var coveredTo = minX
        for ((l, r) in spans) {
            if (l - coveredTo >= minGap) gaps += (coveredTo + l) / 2
            coveredTo = maxOf(coveredTo, r)
        }
        val bounds = listOf(minX - 1) + gaps + listOf(maxX + 1)
        return lines.map { line ->
            (0 until bounds.size - 1).map { c ->
                line.filter { val mid = (it.left + it.right) / 2; mid >= bounds[c] && mid < bounds[c + 1] }
                    .sortedBy { it.left }.joinToString(" ") { it.text }
            }
        }
    }

    /** RFC 4180 CSV. */
    fun csv(rows: List<List<String>>): String = rows.joinToString("\r\n") { row ->
        row.joinToString(",") { cell -> if (cell.any { it == ',' || it == '"' || it == '\n' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell }
    } + "\r\n"
}
