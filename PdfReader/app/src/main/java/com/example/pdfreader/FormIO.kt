package com.example.pdfreader

import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.FormBuilder
import com.example.pdfreader.core.FieldFormat
import com.example.pdfreader.core.FieldKind
import com.example.pdfreader.core.FormField
import com.example.pdfreader.core.FormFormats
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDFormFieldAdditionalActions
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/**
 * Reads the interactive form (AcroForm) of a document as [FormField]s and writes filled values
 * back. Values are set through PdfBox so the fields get proper appearances that every PDF app
 * shows the same way.
 */
object FormIO {

    /** Every widget of every terminal field, in page coordinates (top-left origin). */
    fun read(doc: PDDocument): List<FormField> {
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        val pageIndex = HashMap<com.tom_roush.pdfbox.cos.COSDictionary, Int>()
        doc.pages.forEachIndexed { i, page -> pageIndex[page.cosObject] = i }
        val widgetPages = HashMap<com.tom_roush.pdfbox.cos.COSDictionary, Int>()
        // Widgets don't always point back at their page; find them in the pages' annotations.
        doc.pages.forEachIndexed { i, page ->
            runCatching { page.annotations }.getOrNull()?.forEach { widgetPages[it.cosObject] = i }
        }
        val result = mutableListOf<FormField>()
        for (field in form.fieldTree) {
            if (field !is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField) continue
            val kind = kindOf(field) ?: continue
            val script = (field.actions?.f as? PDActionJavaScript)?.action
            val label = field.alternateFieldName.orEmpty()
            val (format, datePattern) = if (field is PDTextField) FormFormats.detect(field.partialName.orEmpty(), label, script) else FieldFormat.PLAIN to ""
            for (widget in field.widgets) {
                val page = widget.page?.cosObject?.let(pageIndex::get) ?: widgetPages[widget.cosObject] ?: continue
                val rect = widget.rectangle ?: continue
                val pdPage = doc.getPage(page)
                val box = toBox(pdPage, rect)
                val onValue = if (field is PDButton) onStateOf(widget) ?: "Yes" else "Yes"
                val value = when (field) {
                    is PDCheckBox -> if (field.isChecked) onValue else ""
                    is PDRadioButton -> if (field.value == onValue) onValue else ""
                    is PDChoice -> field.value.firstOrNull()?.let { export ->
                        // Show the option's display text rather than its export value.
                        val i = field.optionsExportValues.indexOf(export)
                        field.optionsDisplayValues.getOrNull(i) ?: export
                    }.orEmpty()
                    else -> field.valueAsString.orEmpty()
                }
                result += FormField(
                    name = field.fullyQualifiedName ?: continue,
                    kind = kind,
                    page = page,
                    box = box,
                    value = value,
                    label = label,
                    options = (field as? PDChoice)?.optionsDisplayValues.orEmpty(),
                    onValue = onValue,
                    required = field.isRequired,
                    readOnly = field.isReadOnly,
                    multiline = (field as? PDTextField)?.isMultiline == true,
                    maxLength = (field as? PDTextField)?.maxLen?.coerceAtLeast(0) ?: 0,
                    format = format,
                    datePattern = datePattern,
                )
            }
        }
        return result
    }

    private fun kindOf(field: PDField): FieldKind? = when (field) {
        is PDTextField -> FieldKind.TEXT
        is PDCheckBox -> FieldKind.CHECKBOX
        is PDRadioButton -> FieldKind.RADIO
        is PDChoice -> FieldKind.CHOICE
        is PDSignatureField -> FieldKind.SIGNATURE
        is PDPushButton -> FieldKind.BUTTON
        else -> null
    }

    /** The name of a checkbox or radio widget's "on" appearance state (anything but Off). */
    private fun onStateOf(widget: PDAnnotationWidget): String? {
        val normal = widget.appearance?.normalAppearance ?: return null
        if (!normal.isSubDictionary) return null
        return normal.subDictionary.keys.map { it.name }.firstOrNull { it != COSName.Off.name }
    }

    private fun toBox(page: PDPage, rect: com.tom_roush.pdfbox.pdmodel.common.PDRectangle): Box {
        val crop = page.cropBox
        return Box(
            rect.lowerLeftX - crop.lowerLeftX, crop.upperRightY - rect.upperRightY,
            rect.upperRightX - crop.lowerLeftX, crop.upperRightY - rect.lowerLeftY,
        )
    }

    /**
     * Writes [values] (field name → value) into the form. Checkboxes and radio buttons take
     * their on-state name to check them and "" (or "Off") to clear them; choices take the
     * displayed option. Unknown names are ignored. With [flatten] the fields become plain page
     * content and can no longer be changed.
     */
    fun write(doc: PDDocument, values: Map<String, String>, flatten: Boolean = false) {
        val form = doc.documentCatalog.acroForm ?: return
        // Let PdfBox build appearances itself rather than asking viewers to.
        form.setNeedAppearances(false)
        for ((name, value) in values) {
            val field = form.getField(name) ?: continue
            if (field.isReadOnly) continue
            runCatching {
                when (field) {
                    is PDCheckBox -> if (value.isEmpty() || value == "Off") field.unCheck() else field.check()
                    is PDRadioButton -> field.setValue(if (value.isEmpty()) "Off" else value)
                    is PDChoice -> {
                        // Accept the displayed text and map it to the export value.
                        val display = field.optionsDisplayValues
                        val export = field.optionsExportValues
                        val i = display.indexOf(value)
                        field.setValue(if (i >= 0 && i < export.size) export[i] else value)
                    }
                    is PDTextField -> field.setValue(value)
                    else -> Unit
                }
            }
        }
        if (flatten) form.flatten()
    }

    /**
     * Adds the fields drawn with the form builder as real interactive fields, creating the
     * document's form if it has none. Names already in use get a number appended.
     */
    fun create(doc: PDDocument, fields: List<Annotation.Field>) {
        if (fields.isEmpty()) return
        val form = doc.documentCatalog.acroForm ?: PDAcroForm(doc).also { doc.documentCatalog.acroForm = it }
        val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        if (resources.getFont(HELV) == null) resources.put(HELV, PDType1Font.HELVETICA)
        if (form.defaultAppearance.isNullOrEmpty()) form.defaultAppearance = "/Helv 0 Tf 0 g"
        val names = form.fieldTree.mapNotNull { it.fullyQualifiedName }.toMutableSet()
        val added = form.fields.toMutableList()
        for (f in fields) {
            if (f.page !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(f.page)
            val rect = toRect(page, f.box)
            val widget = PDAnnotationWidget().apply {
                rectangle = rect
                this.page = page
                isPrinted = true
                appearanceCharacteristics = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
                    setBorderColour(PDColor(floatArrayOf(0.45f, 0.45f, 0.45f), PDDeviceRGB.INSTANCE))
                }
            }
            val name = FormBuilder.uniqueName(f.name, names).also { names += it }
            val field = when (f.kind) {
                FieldKind.CHECKBOX, FieldKind.RADIO -> PDCheckBox(form).apply {
                    widget.appearance = checkboxAppearance(doc, rect.width, rect.height)
                    widget.setAppearanceState(COSName.Off.name)
                }
                FieldKind.CHOICE -> PDComboBox(form).apply {
                    defaultAppearance = "/Helv 0 Tf 0 g"
                    setOptions(f.options.ifEmpty { listOf("") })
                }
                FieldKind.SIGNATURE -> PDSignatureField(form).apply {
                    widget.appearance = boxAppearance(doc, rect.width, rect.height)
                }
                else -> PDTextField(form).apply {
                    defaultAppearance = "/Helv 0 Tf 0 g"
                    if (f.format == FieldFormat.DATE) {
                        // The same format script Acrobat writes, so other apps know it is a date.
                        val actions = PDFormFieldAdditionalActions().apply { setF(PDActionJavaScript("AFDate_FormatEx(\"$DATE_PATTERN\");")) }
                        cosObject.setItem(COSName.AA, actions)
                    }
                }
            }
            field.partialName = name
            field.isRequired = f.required
            if (f.note.isNotBlank()) field.alternateFieldName = f.note
            field.widgets = listOf(widget)
            page.annotations = page.annotations.toMutableList().apply { add(widget) }
            added += field
        }
        form.fields = added
        // Builds the empty text and dropdown fields' appearances, with their borders.
        val variableText = added.filter { it is PDTextField || it is PDComboBox }.filter { f -> f.widgets.any { it.appearance == null } }
        runCatching { form.refreshAppearances(variableText) }
    }

    /** The date pattern of date fields made with the form builder. */
    const val DATE_PATTERN = "yyyy-mm-dd"

    private val HELV = COSName.getPDFName("Helv")

    private fun toRect(page: PDPage, box: Box): PDRectangle {
        val crop = page.cropBox
        return PDRectangle(crop.lowerLeftX + box.left, crop.upperRightY - box.bottom, box.width, box.height)
    }

    private fun stream(doc: PDDocument, w: Float, h: Float, draw: (PDPageContentStream) -> Unit) = PDAppearanceStream(doc).apply {
        bBox = PDRectangle(w, h)
        resources = PDResources()
        PDPageContentStream(doc, this).use(draw)
    }

    private fun border(s: PDPageContentStream, w: Float, h: Float) {
        s.setStrokingColor(0.45f, 0.45f, 0.45f)
        s.setLineWidth(1f)
        s.addRect(0.5f, 0.5f, w - 1, h - 1)
        s.stroke()
    }

    /** A bordered box with a tick for "Yes" and an empty one for "Off". */
    private fun checkboxAppearance(doc: PDDocument, w: Float, h: Float): PDAppearanceDictionary {
        val on = stream(doc, w, h) { s ->
            border(s, w, h)
            s.setStrokingColor(0f, 0f, 0f)
            s.setLineWidth((minOf(w, h) * 0.12f).coerceAtLeast(1f))
            s.moveTo(w * 0.2f, h * 0.5f)
            s.lineTo(w * 0.42f, h * 0.22f)
            s.lineTo(w * 0.8f, h * 0.8f)
            s.stroke()
        }
        val off = stream(doc, w, h) { s -> border(s, w, h) }
        val normal = COSDictionary().apply {
            setItem(COSName.getPDFName("Yes"), on)
            setItem(COSName.Off, off)
        }
        return PDAppearanceDictionary().apply { cosObject.setItem(COSName.N, normal) }
    }

    private fun boxAppearance(doc: PDDocument, w: Float, h: Float) =
        PDAppearanceDictionary().apply { setNormalAppearance(stream(doc, w, h) { s -> border(s, w, h) }) }
}
