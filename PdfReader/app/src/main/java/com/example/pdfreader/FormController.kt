package com.example.pdfreader

import android.app.DatePickerDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.pdfreader.core.Autofill
import com.example.pdfreader.core.AutofillProfile
import com.example.pdfreader.core.FieldFormat
import com.example.pdfreader.core.FieldKind
import com.example.pdfreader.core.FieldProblem
import com.example.pdfreader.core.FormField
import com.example.pdfreader.core.FormNavigation
import com.example.pdfreader.core.FormValidation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.json.Json
import java.util.Calendar

/** The user's saved details for autofill, kept in the app's settings. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var profile: AutofillProfile
        get() = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(AutofillProfile.serializer(), it) }.getOrNull() } ?: AutofillProfile()
        set(value) = prefs.edit().putString(KEY, json.encodeToString(AutofillProfile.serializer(), value)).apply()

    var highlightFields: Boolean
        get() = prefs.getBoolean("form_highlight", true)
        set(value) = prefs.edit().putBoolean("form_highlight", value).apply()

    private companion object {
        const val KEY = "autofill_profile"
    }
}

/**
 * Filling interactive (AcroForm) forms: tap a field to type into it in the bar above the
 * keyboard (with a keyboard to match: numbers, email, phone, date), Next/Previous to move
 * through the form, tap checkboxes and radio buttons to set them, pick from dropdowns.
 * Values are drawn over the page until saved; saving goes through [AnnotationSession].
 */
class FormController(
    private val activity: AppCompatActivity,
    private val goToPage: (Int) -> Unit,
) : PageView.FormLayer {

    private val context: Context get() = activity
    private val store by lazy { ProfileStore(activity) }

    private lateinit var pages: ZoomableRecyclerView
    private lateinit var bar: View
    private lateinit var label: TextView
    private lateinit var input: EditText
    private lateinit var error: TextView
    private lateinit var pickDate: ImageButton

    private var session: AnnotationSession? = null
    private val sessionListener: () -> Unit = { invalidatePages() }

    /** The field being typed into, if any. */
    private var editing: FormField? = null
    override val activeField: FormField? get() = editing

    override val highlightFields: Boolean get() = store.highlightFields

    /** Updating the input from code shouldn't write the value back. */
    private var settingText = false

    /** Called when the bar opens or closes (the screen hides its page navigation meanwhile). */
    var onEditingChanged: ((Boolean) -> Unit)? = null

    val fields: List<FormField> get() = session?.formFields.orEmpty()
    val hasForm: Boolean get() = fields.any { it.kind != FieldKind.BUTTON }

    fun attach(pages: ZoomableRecyclerView, root: View) {
        this.pages = pages
        bar = root.findViewById(R.id.formBar)
        label = root.findViewById(R.id.formFieldLabel)
        input = root.findViewById(R.id.formInput)
        error = root.findViewById(R.id.formError)
        pickDate = root.findViewById(R.id.formPickDate)
        root.findViewById<View>(R.id.formPrev).setOnClickListener { move(forward = false) }
        root.findViewById<View>(R.id.formNext).setOnClickListener { move(forward = true) }
        root.findViewById<View>(R.id.formDone).setOnClickListener { finishEditing() }
        pickDate.setOnClickListener { activeField?.let(::showDatePicker) }
        input.doAfterTextChanged { text ->
            val field = activeField ?: return@doAfterTextChanged
            if (settingText) return@doAfterTextChanged
            session?.setFieldValue(field, text?.toString().orEmpty())
            error.isVisible = false
            heldOn = null
        }
        input.setOnEditorActionListener { _, action, _ ->
            when (action) {
                EditorInfo.IME_ACTION_NEXT -> {
                    move(forward = true)
                    true
                }
                EditorInfo.IME_ACTION_DONE -> {
                    finishEditing()
                    true
                }
                else -> false
            }
        }
    }

    fun bind(ready: ReaderState.Ready?) {
        session?.listeners?.remove(sessionListener)
        session = ready?.annotations
        session?.listeners?.add(sessionListener)
        closeBar()
    }

    private fun invalidatePages() {
        if (::pages.isInitialized) pages.forEachPageView { it.invalidate() }
    }

    // --- FormLayer -----------------------------------------------------------------------------

    override fun fieldsOn(page: Int): List<FormField> = fields.filter { it.page == page }

    override fun pendingValue(field: FormField): String? {
        val session = session ?: return null
        return if (session.isChanged(field)) session.valueOf(field) else null
    }

    // --- Taps --------------------------------------------------------------------------------

    /** A tap on a form field fills it (or opens it for typing). Returns true if it hit one. */
    fun onTap(x: Float, y: Float): Boolean {
        val session = session ?: return false
        if (fields.isEmpty()) return false
        val (view, local) = pages.pageViewAt(x, y) ?: return false
        val p = view.toPagePoint(local.x, local.y)
        val slop = 2f
        val field = fields.lastOrNull { it.page == view.page && it.box.contains(p.x, p.y, slop) && it.kind != FieldKind.BUTTON } ?: run {
            if (activeField != null) finishEditing()
            return false
        }
        if (field.readOnly) {
            Toast.makeText(context, R.string.field_read_only, Toast.LENGTH_SHORT).show()
            return true
        }
        when (field.kind) {
            FieldKind.CHECKBOX -> {
                finishEditing()
                session.setFieldValue(field, if (session.valueOf(field).isEmpty()) field.onValue else "")
            }
            FieldKind.RADIO -> {
                finishEditing()
                session.setFieldValue(field, field.onValue)
            }
            FieldKind.CHOICE -> {
                finishEditing()
                showChoices(field)
            }
            FieldKind.TEXT -> edit(field)
            FieldKind.SIGNATURE -> return false
            FieldKind.BUTTON -> return false
        }
        return true
    }

    private fun showChoices(field: FormField) {
        val options = field.options
        if (options.isEmpty()) return
        val current = options.indexOf(session?.valueOf(field))
        MaterialAlertDialogBuilder(context)
            .setTitle(field.label.ifBlank { field.name })
            .setSingleChoiceItems(options.toTypedArray(), current) { dialog, which ->
                session?.setFieldValue(field, options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Typing ------------------------------------------------------------------------------

    /** Opens [field] in the bar for typing. */
    fun edit(field: FormField) {
        val session = session ?: return
        editing = field
        goToPageIfNeeded(field)
        bar.isVisible = true
        onEditingChanged?.invoke(true)
        label.text = buildString {
            append(field.label.ifBlank { field.name })
            if (field.required) append(" *")
        }
        input.filters = if (field.maxLength > 0) arrayOf(InputFilter.LengthFilter(field.maxLength)) else emptyArray()
        input.inputType = inputTypeFor(field)
        if (field.format == FieldFormat.NUMBER) input.keyListener = DigitsKeyListener.getInstance("0123456789.,-+ ")
        input.isSingleLine = !field.multiline
        input.imeOptions = (if (FormNavigation.next(fields, field) != null) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE) or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        pickDate.isVisible = field.format == FieldFormat.DATE
        settingText = true
        input.setText(session.valueOf(field))
        input.setSelection(input.text.length)
        settingText = false
        error.isVisible = false
        input.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        invalidatePages()
    }

    private fun inputTypeFor(field: FormField): Int = when (field.format) {
        FieldFormat.NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        FieldFormat.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        FieldFormat.PHONE -> InputType.TYPE_CLASS_PHONE
        FieldFormat.DATE -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        FieldFormat.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (field.multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
    }

    private fun goToPageIfNeeded(field: FormField) {
        if (pages.pageViewFor(field.page) == null) goToPage(field.page)
    }

    /** The field Next last refused to leave because of a problem. */
    private var heldOn: FormField? = null

    /**
     * Moves to the next or previous field. If the value has a problem, the first Next shows it
     * and stays; pressing Next again moves on anyway (forms can be saved half-filled).
     */
    private fun move(forward: Boolean) {
        val field = activeField ?: return
        if (!showProblem(field) && heldOn != field) {
            heldOn = field
            return
        }
        heldOn = null
        val next = FormNavigation.next(fields, field, forward)
        if (next == null) {
            if (forward) finishEditing()
            return
        }
        edit(next)
    }

    /** Shows what's wrong with a field's value, if anything; returns whether it is fine. */
    private fun showProblem(field: FormField): Boolean {
        val problem = FormValidation.problem(field, session?.valueOf(field).orEmpty()) ?: return true
        error.text = describe(field, problem)
        error.isVisible = true
        return false
    }

    fun finishEditing() {
        val field = activeField ?: return
        if (!showProblem(field)) {
            // Keep the message visible briefly as a toast once the bar closes.
            Toast.makeText(context, label.text.toString() + ": " + error.text, Toast.LENGTH_SHORT).show()
        }
        closeBar()
    }

    private fun closeBar() {
        val wasOpen = activeField != null
        editing = null
        if (!::bar.isInitialized) return
        bar.isVisible = false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        pages.requestFocus()
        if (wasOpen) onEditingChanged?.invoke(false)
        invalidatePages()
    }

    private fun showDatePicker(field: FormField) {
        val calendar = Calendar.getInstance()
        FormValidation.parseDate(session?.valueOf(field).orEmpty(), field.datePattern)?.let { calendar.timeInMillis = it }
        DatePickerDialog(context, { _, year, month, day ->
            val picked = Calendar.getInstance().apply { set(year, month, day, 12, 0, 0) }
            val text = FormValidation.formatDate(picked.timeInMillis, field.datePattern)
            input.setText(text)
            input.setSelection(text.length)
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun describe(field: FormField, problem: FieldProblem): String = when (problem) {
        FieldProblem.REQUIRED -> context.getString(R.string.field_required)
        FieldProblem.TOO_LONG -> context.getString(R.string.field_too_long)
        FieldProblem.NOT_A_NUMBER -> context.getString(R.string.field_not_number)
        FieldProblem.NOT_AN_EMAIL -> context.getString(R.string.field_not_email)
        FieldProblem.NOT_A_PHONE -> context.getString(R.string.field_not_phone)
        FieldProblem.NOT_A_DATE -> context.getString(R.string.field_not_date, field.datePattern.ifBlank { "dd.MM.yyyy" }.lowercase())
    }

    /** Back closes the bar first. Returns true if it did. */
    fun handleBack(): Boolean {
        if (activeField == null) return false
        finishEditing()
        return true
    }

    // --- Checking before saving ----------------------------------------------------------------

    /** Every field (one entry per name) whose value isn't acceptable. */
    fun problems(): List<Pair<FormField, FieldProblem>> {
        val session = session ?: return emptyList()
        return FormNavigation.order(fields).distinctBy { it.name }.mapNotNull { f ->
            val value = if (f.kind == FieldKind.RADIO) fields.filter { it.name == f.name }.map(session::valueOf).firstOrNull { it.isNotEmpty() }.orEmpty() else session.valueOf(f)
            FormValidation.problem(f, value)?.let { f to it }
        }
    }

    /** Runs [proceed] if the form is fine; otherwise lists the problems first. */
    fun confirmSave(proceed: () -> Unit) {
        finishEditing()
        val problems = problems()
        if (problems.isEmpty()) {
            proceed()
            return
        }
        val list = problems.take(8).joinToString("\n") { (f, p) -> "• " + f.label.ifBlank { f.name } + ": " + describe(f, p) } +
            if (problems.size > 8) "\n…" else ""
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.form_problems_title)
            .setMessage(context.getString(R.string.form_problems_message, list))
            .setPositiveButton(R.string.save_anyway) { _, _ -> proceed() }
            .setNegativeButton(R.string.review) { _, _ ->
                val first = problems.first().first
                if (first.kind == FieldKind.TEXT) edit(first) else goToPage(first.page)
            }
            .show()
    }

    // --- Autofill and clearing -------------------------------------------------------------

    fun autofill() {
        val session = session ?: return
        val profile = store.profile
        if (profile.isEmpty) {
            Toast.makeText(context, R.string.form_profile_empty, Toast.LENGTH_SHORT).show()
            editProfile()
            return
        }
        val current = fields.map { it.copy(value = session.valueOf(it)) }
        val values = Autofill.fill(current, profile)
        for ((name, value) in values) {
            val field = fields.firstOrNull { it.name == name } ?: continue
            session.setFieldValue(field, value)
        }
        Toast.makeText(
            context,
            if (values.isEmpty()) context.getString(R.string.form_nothing_to_fill) else context.getString(R.string.form_filled, values.size),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun clear() {
        val session = session ?: return
        MaterialAlertDialogBuilder(context)
            .setMessage(R.string.form_clear_message)
            .setPositiveButton(R.string.form_clear) { _, _ ->
                closeBar()
                for (name in session.changedFormValues.keys.toList()) {
                    val field = fields.first { it.name == name }
                    val original = if (field.kind == FieldKind.RADIO) fields.firstOrNull { it.name == name && it.value.isNotEmpty() }?.onValue.orEmpty() else field.value
                    session.setFieldValue(field, original)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun toggleHighlight(): Boolean {
        store.highlightFields = !store.highlightFields
        invalidatePages()
        return store.highlightFields
    }

    /** A simple form for the user's own details. */
    fun editProfile() {
        val p = store.profile
        val density = context.resources.displayMetrics.density
        val entries = listOf(
            R.string.profile_first_name to p.firstName, R.string.profile_last_name to p.lastName,
            R.string.profile_company to p.company, R.string.profile_street to p.street,
            R.string.profile_postal_code to p.postalCode, R.string.profile_city to p.city,
            R.string.profile_country to p.country, R.string.profile_email to p.email,
            R.string.profile_phone to p.phone, R.string.profile_birth_date to p.birthDate,
            R.string.profile_tax_id to p.taxId,
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val inputs = entries.map { (hint, value) ->
            EditText(context).apply {
                setHint(hint)
                contentDescription = context.getString(hint)
                setText(value)
                isSingleLine = true
                inputType = when (hint) {
                    R.string.profile_email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    R.string.profile_phone -> InputType.TYPE_CLASS_PHONE
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                column.addView(this)
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.form_profile)
            .setView(ScrollView(context).apply { addView(column) })
            .setPositiveButton(R.string.save) { _, _ ->
                val v = inputs.map { it.text.toString().trim() }
                store.profile = AutofillProfile(
                    firstName = v[0], lastName = v[1], company = v[2], street = v[3], postalCode = v[4], city = v[5],
                    country = v[6], email = v[7], phone = v[8], birthDate = v[9], taxId = v[10],
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
