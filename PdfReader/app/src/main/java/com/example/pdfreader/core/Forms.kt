package com.example.pdfreader.core

import kotlinx.serialization.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

enum class FieldKind { TEXT, CHECKBOX, RADIO, CHOICE, SIGNATURE, BUTTON }

/** What a text field expects, which picks the keyboard and the validation. */
enum class FieldFormat { PLAIN, NUMBER, EMAIL, PHONE, DATE }

/**
 * One fillable widget of an interactive (AcroForm) field. A field with several widgets (radio
 * buttons, or the same field shown on several pages) has one entry per widget sharing [name].
 *
 * [value] is the text, the selected choice, or for checkboxes and radio buttons the "on" state
 * name when checked ([onValue]) and "" when not.
 */
data class FormField(
    val name: String,
    val kind: FieldKind,
    val page: Int,
    val box: Box,
    val value: String = "",
    val label: String = "",
    val options: List<String> = emptyList(),
    val onValue: String = "Yes",
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
    val maxLength: Int = 0,
    val format: FieldFormat = FieldFormat.PLAIN,
    /** For dates: the pattern the form uses, e.g. "dd.MM.yyyy". */
    val datePattern: String = "",
) {
    val isChecked: Boolean get() = value.isNotEmpty() && value != "Off"

    /** Text the user typed or picked, shown in lists. */
    val displayValue: String
        get() = when (kind) {
            FieldKind.CHECKBOX, FieldKind.RADIO -> if (isChecked) "✓" else ""
            else -> value
        }

    /** Fields that take keyboard input, in the order the Next key visits them. */
    val isTextInput: Boolean get() = (kind == FieldKind.TEXT || kind == FieldKind.CHOICE) && !readOnly
}

object FormFormats {

    private val emailWords = listOf("email", "e-mail", "mail")
    private val phoneWords = listOf("phone", "telephone", "tel", "mobile", "fax", "telefon", "handy", "mobil")
    private val dateWords = listOf("date", "datum", "birthday", "geburtsdatum", "dob")
    private val numberWords = listOf("amount", "number", "zip", "postal", "plz", "betrag", "anzahl", "postleitzahl", "qty", "quantity")

    /**
     * Guesses a text field's format. A JavaScript format action (what Acrobat uses, e.g.
     * `AFDate_FormatEx("dd.mm.yyyy")`) wins; otherwise the field's name and label decide.
     */
    fun detect(name: String, label: String, formatScript: String?): Pair<FieldFormat, String> {
        val script = formatScript.orEmpty()
        Regex("AFDate_(?:Format|FormatEx|Keystroke|KeystrokeEx)\\(\\s*\"([^\"]+)\"").find(script)?.let {
            return FieldFormat.DATE to acrobatDatePattern(it.groupValues[1])
        }
        if (script.contains("AFNumber_") || script.contains("AFPercent_")) return FieldFormat.NUMBER to ""
        if (script.contains("AFSpecial_Format(2)") || script.contains("AFSpecial_Keystroke(2)")) return FieldFormat.PHONE to ""
        if (Regex("AFSpecial_(?:Format|Keystroke)\\(\\s*0\\s*\\)").containsMatchIn(script)) return FieldFormat.NUMBER to ""
        val words = words("$name $label")
        return when {
            words.any { it in emailWords } -> FieldFormat.EMAIL to ""
            words.any { it in dateWords } -> FieldFormat.DATE to ""
            words.any { it in phoneWords } -> FieldFormat.PHONE to ""
            words.any { it in numberWords } -> FieldFormat.NUMBER to ""
            else -> FieldFormat.PLAIN to ""
        }
    }

    /**
     * Lower-case words of a field name, splitting camelCase, digits and punctuation. Hyphenated
     * words are kept whole and also split ("e-mail-adresse", "e", "mail", "adresse").
     */
    fun words(text: String): List<String> =
        text.replace(Regex("([a-zäöü])([A-ZÄÖÜ])"), "$1 $2")
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}-]+"))
            .map { it.trim('-') }
            .filter { it.isNotEmpty() }
            .flatMap { word -> if ('-' in word) listOf(word) + word.split('-').filter { it.isNotEmpty() } else listOf(word) }

    /** Acrobat date patterns ("dd/mm/yyyy", "mmm d, yyyy") as Java patterns. */
    fun acrobatDatePattern(acrobat: String): String =
        acrobat.replace(Regex("m+|y+|d+|H+|h+|M+|t+")) { m ->
            val token = m.value
            when (token[0]) {
                'm' -> "M".repeat(token.length)   // Acrobat month
                'M' -> "m".repeat(token.length)   // Acrobat minutes
                'y' -> "y".repeat(token.length)
                'd' -> "d".repeat(token.length)
                'H' -> "H".repeat(token.length)
                'h' -> "h".repeat(token.length)
                't' -> "a"
                else -> token
            }
        }
}

/** Why a value can't be saved as it is. */
enum class FieldProblem { REQUIRED, TOO_LONG, NOT_A_NUMBER, NOT_AN_EMAIL, NOT_A_PHONE, NOT_A_DATE }

object FormValidation {

    private val email = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
    private val phone = Regex("^\\+?[0-9 ()/.-]{5,}$")

    fun problem(field: FormField, value: String = field.value): FieldProblem? {
        val v = value.trim()
        if (v.isEmpty() || (field.kind != FieldKind.TEXT && field.kind != FieldKind.CHOICE && v == "Off")) {
            return if (field.required) FieldProblem.REQUIRED else null
        }
        if (field.kind != FieldKind.TEXT) return null
        if (field.maxLength > 0 && value.length > field.maxLength) return FieldProblem.TOO_LONG
        return when (field.format) {
            FieldFormat.NUMBER -> if (parseNumber(v) == null) FieldProblem.NOT_A_NUMBER else null
            FieldFormat.EMAIL -> if (!email.matches(v)) FieldProblem.NOT_AN_EMAIL else null
            FieldFormat.PHONE -> if (!phone.matches(v) || v.count { it.isDigit() } < 5) FieldProblem.NOT_A_PHONE else null
            FieldFormat.DATE -> if (parseDate(v, field.datePattern) == null) FieldProblem.NOT_A_DATE else null
            FieldFormat.PLAIN -> null
        }
    }

    /** Numbers as people type them: "1234", "1,234.50", "1.234,50", "-3". */
    fun parseNumber(text: String): Double? {
        val t = text.replace(" ", "").replace(" ", "").removePrefix("€").removeSuffix("€").removePrefix("$")
        if (t.isEmpty() || !Regex("^[-+]?[0-9.,]+$").matches(t)) return null
        val lastComma = t.lastIndexOf(',')
        val lastDot = t.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            // A single comma followed by 1–2 digits is a decimal comma; otherwise thousands.
            lastComma >= 0 -> if (t.count { it == ',' } == 1 && t.length - lastComma - 1 in 1..2) t.replace(',', '.') else t.replace(",", "")
            lastDot >= 0 && t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        return normalized.toDoubleOrNull()
    }

    private val commonDatePatterns = listOf("dd.MM.yyyy", "d.M.yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "dd/MM/yyyy", "dd.MM.yy")

    /** Parses a date with the form's own pattern, or common ones; returns epoch millis. */
    fun parseDate(text: String, pattern: String = ""): Long? {
        val patterns = listOf(pattern).filter { it.isNotBlank() } + commonDatePatterns
        for (p in patterns) {
            val format = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
            try {
                val pos = java.text.ParsePosition(0)
                val date = format.parse(text.trim(), pos) ?: continue
                if (pos.index == text.trim().length) return date.time
            } catch (e: ParseException) {
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
        }
        return null
    }

    /** Formats a picked date the way the field expects (dd.MM.yyyy when it doesn't say). */
    fun formatDate(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern.ifBlank { "dd.MM.yyyy" }, Locale.US).format(java.util.Date(millis))
}

/** Next/previous field order: by page, then rows top to bottom, then left to right. */
object FormNavigation {
    /** Fields whose tops are within this many points are treated as one row. */
    private const val ROW_TOLERANCE = 6f

    fun order(fields: List<FormField>): List<FormField> {
        val result = mutableListOf<FormField>()
        for ((_, onPage) in fields.groupBy { it.page }.toSortedMap()) {
            val rows = mutableListOf<MutableList<FormField>>()
            for (f in onPage.sortedBy { it.box.top }) {
                val row = rows.lastOrNull()
                if (row != null && f.box.top - row.first().box.top <= ROW_TOLERANCE) row += f else rows += mutableListOf(f)
            }
            rows.forEach { row -> result += row.sortedBy { it.box.left } }
        }
        return result
    }

    fun next(fields: List<FormField>, current: FormField?, forward: Boolean = true): FormField? {
        val inputs = order(fields).filter { it.isTextInput }
        if (inputs.isEmpty()) return null
        val i = inputs.indexOfFirst { it.name == current?.name && it.page == current.page && it.box == current.box }
        if (i < 0) return if (forward) inputs.first() else inputs.last()
        val j = if (forward) i + 1 else i - 1
        return inputs.getOrNull(j)
    }
}

/** Personal details stored once to fill forms with one tap. */
@Serializable
data class AutofillProfile(
    val firstName: String = "",
    val lastName: String = "",
    val street: String = "",
    val postalCode: String = "",
    val city: String = "",
    val country: String = "",
    val email: String = "",
    val phone: String = "",
    val birthDate: String = "",
    val taxId: String = "",
    val company: String = "",
) {
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    val isEmpty: Boolean get() = this == AutofillProfile()

    fun valueOf(key: ProfileKey): String = when (key) {
        ProfileKey.FIRST_NAME -> firstName
        ProfileKey.LAST_NAME -> lastName
        ProfileKey.FULL_NAME -> fullName
        ProfileKey.STREET -> street
        ProfileKey.POSTAL_CODE -> postalCode
        ProfileKey.CITY -> city
        ProfileKey.POSTAL_CODE_CITY -> listOf(postalCode, city).filter { it.isNotBlank() }.joinToString(" ")
        ProfileKey.COUNTRY -> country
        ProfileKey.EMAIL -> email
        ProfileKey.PHONE -> phone
        ProfileKey.BIRTH_DATE -> birthDate
        ProfileKey.TAX_ID -> taxId
        ProfileKey.COMPANY -> company
    }
}

enum class ProfileKey {
    FIRST_NAME, LAST_NAME, FULL_NAME, STREET, POSTAL_CODE, CITY, POSTAL_CODE_CITY, COUNTRY,
    EMAIL, PHONE, BIRTH_DATE, TAX_ID, COMPANY,
}

/** Matches form fields to profile entries by their names and labels (English and German). */
object Autofill {

    private fun has(words: List<String>, vararg any: String) = any.any { it in words }
    private fun joined(words: List<String>) = words.joinToString("")

    /** The profile entry a field asks for, or null when it isn't recognised. */
    fun keyFor(field: FormField): ProfileKey? {
        if (field.kind != FieldKind.TEXT || field.readOnly) return null
        val w = FormFormats.words(field.name + " " + field.label)
        val all = joined(w)
        return when {
            has(w, "email", "e-mail", "mail") || "email" in all -> ProfileKey.EMAIL
            has(w, "phone", "telephone", "tel", "mobile", "telefon", "telefonnummer", "handy", "mobil", "mobilnummer") || "phone" in all -> ProfileKey.PHONE
            has(w, "birthday", "dob", "geburtsdatum") || ("birth" in all && "date" in all) || "geburtsdatum" in all -> ProfileKey.BIRTH_DATE
            has(w, "steuer-id", "steueridentifikationsnummer", "steuernummer", "tin", "ssn", "idnr") || ("tax" in all && "id" in w) || "taxid" in all || "steuerid" in all -> ProfileKey.TAX_ID
            has(w, "firstname", "vorname", "given") || ("first" in w && "name" in w) || "firstname" in all -> ProfileKey.FIRST_NAME
            has(w, "lastname", "surname", "nachname", "familienname", "family") || ("last" in w && "name" in w) || "lastname" in all -> ProfileKey.LAST_NAME
            has(w, "company", "firma", "organisation", "organization", "employer", "arbeitgeber") -> ProfileKey.COMPANY
            (has(w, "plz", "zip", "postal", "postleitzahl", "postcode") && has(w, "ort", "city", "wohnort", "town")) || "plzort" in all -> ProfileKey.POSTAL_CODE_CITY
            has(w, "plz", "zip", "zipcode", "postal", "postleitzahl", "postcode") || "zipcode" in all || "postalcode" in all -> ProfileKey.POSTAL_CODE
            has(w, "city", "town", "ort", "wohnort", "stadt") -> ProfileKey.CITY
            has(w, "street", "address", "straße", "strasse", "anschrift", "adresse", "hausnummer") || "street" in all || "straße" in all -> ProfileKey.STREET
            has(w, "country", "land", "staat") -> ProfileKey.COUNTRY
            has(w, "name") -> ProfileKey.FULL_NAME
            else -> null
        }
    }

    /**
     * Values to fill: for each empty, recognised field, the profile's value. Fields that
     * already have a value are left alone unless [overwrite].
     */
    fun fill(fields: List<FormField>, profile: AutofillProfile, overwrite: Boolean = false): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (f in fields) {
            if (!overwrite && f.value.isNotBlank()) continue
            val key = keyFor(f) ?: continue
            val value = profile.valueOf(key)
            if (value.isNotBlank()) result[f.name] = if (f.maxLength > 0) value.take(f.maxLength) else value
        }
        return result
    }
}
