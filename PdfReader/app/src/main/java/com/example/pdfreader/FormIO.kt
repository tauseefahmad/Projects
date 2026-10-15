package com.example.pdfreader

import com.example.pdfreader.core.Box
import com.example.pdfreader.core.FieldFormat
import com.example.pdfreader.core.FieldKind
import com.example.pdfreader.core.FormField
import com.example.pdfreader.core.FormFormats
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
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
}
