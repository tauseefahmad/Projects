package com.example.pdfreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Signatures and initials saved for reuse, as transparent PNGs in app storage. */
class SignatureStore(context: Context) {

    enum class Kind { SIGNATURE, INITIALS }

    data class Saved(val file: File, val kind: Kind)

    private val dir = File(context.filesDir, "signatures")

    /** Newest first. */
    fun list(): List<Saved> = dir.listFiles { f -> f.name.endsWith(".png") }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { Saved(it, kindOf(it)) }

    /** Saves [bitmap] cropped to its ink; returns null when there is nothing on it. */
    fun save(bitmap: Bitmap, kind: Kind): Saved? {
        val cropped = crop(bitmap) ?: return null
        dir.mkdirs()
        val prefix = if (kind == Kind.INITIALS) "initials" else "signature"
        val file = File(dir, "$prefix-${UUID.randomUUID()}.png")
        file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (cropped !== bitmap) cropped.recycle()
        return Saved(file, kind)
    }

    fun delete(saved: Saved) = saved.file.delete()

    companion object {
        fun kindOf(file: File) = if (file.name.startsWith("initials")) Kind.INITIALS else Kind.SIGNATURE

        /** [bitmap] without its transparent margins (plus a little padding). */
        fun crop(bitmap: Bitmap): Bitmap? {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) for (x in 0 until w) {
                if (Color.alpha(pixels[y * w + x]) > 16) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
            if (right < 0) return null
            val pad = 4
            left = (left - pad).coerceAtLeast(0)
            top = (top - pad).coerceAtLeast(0)
            right = (right + pad).coerceAtMost(w - 1)
            bottom = (bottom + pad).coerceAtMost(h - 1)
            return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        }

        /**
         * Makes the paper of a photographed or scanned signature transparent: light pixels
         * disappear, darker ones keep their color with an alpha that follows their darkness.
         */
        fun removeBackground(source: Bitmap): Bitmap {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val luminance = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                val alpha = when {
                    luminance > 0.8f -> 0f
                    luminance < 0.5f -> 1f
                    else -> (0.8f - luminance) / 0.3f
                } * Color.alpha(c) / 255f
                pixels[i] = Color.argb((alpha * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        /** A typed name in a handwriting-style font, as a bitmap. */
        fun renderTyped(text: String, typeface: Typeface, color: Int = INK): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 120f
                this.color = color
            }
            val width = paint.measureText(text).toInt().coerceAtLeast(1) + 40
            val metrics = paint.fontMetrics
            val height = (metrics.descent - metrics.ascent).toInt() + 40
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText(text, 20f, 20f - metrics.ascent, paint)
            return bitmap
        }

        /** Handwriting-style fonts available on the device (the system's cursive font first). */
        val typedStyles: List<Typeface> by lazy {
            listOf(
                Typeface.create("cursive", Typeface.NORMAL),
                Typeface.create("casual", Typeface.NORMAL),
                Typeface.create(Typeface.SERIF, Typeface.ITALIC),
            )
        }

        const val INK = 0xFF10204A.toInt()
    }
}

/** A box to sign in with a finger or stylus. */
class SignaturePadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = SignatureStore.INK
        strokeWidth = 3.5f * resources.displayMetrics.density
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55888888
        strokeWidth = resources.displayMetrics.density
    }

    /** Called when the first stroke is drawn or the pad is cleared. */
    var onChanged: (() -> Unit)? = null

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
        onChanged?.invoke()
    }

    /** Draws a stroke programmatically (for tests). Points are in view pixels. */
    fun addStroke(points: List<Pair<Float, Float>>) {
        if (points.isEmpty()) return
        strokes += Path().apply {
            moveTo(points[0].first, points[0].second)
            for ((x, y) in points.drop(1)) lineTo(x, y)
            if (points.size == 1) lineTo(points[0].first + 0.1f, points[0].second)
        }
        invalidate()
        onChanged?.invoke()
    }

    /** The strokes on a transparent background, the size of the pad. */
    fun toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (s in strokes) canvas.drawPath(s, ink)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = height * 0.75f
        val margin = 16 * resources.displayMetrics.density
        canvas.drawLine(margin, y, width - margin, y, guide)
        for (s in strokes) canvas.drawPath(s, ink)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val path = Path().apply { moveTo(x, y) }
                current = path
                strokes += path
                lastX = x
                lastY = y
                onChanged?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val path = current ?: return false
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    path.quadTo(lastX, lastY, (lastX + hx) / 2, (lastY + hy) / 2)
                    lastX = hx
                    lastY = hy
                }
                path.quadTo(lastX, lastY, (lastX + x) / 2, (lastY + y) / 2)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                current?.lineTo(x, y)
                current = null
            }
            MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }
}

/**
 * Lets the user pick a saved signature or initials, or make a new one by drawing, typing in a
 * handwriting font or importing a picture. Create it while the activity is being constructed
 * (it registers for the image picker result).
 */
class SignaturePicker(private val activity: AppCompatActivity) {

    private val context: Context get() = activity
    val store by lazy { SignatureStore(activity) }

    private var pendingKind = SignatureStore.Kind.SIGNATURE
    private var pendingResult: ((SignatureStore.Saved) -> Unit)? = null

    private val pickImage = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val onPicked = pendingResult ?: return@registerForActivityResult
        pendingResult = null
        if (uri != null) importImage(uri, pendingKind, onPicked)
    }

    /** The most recently open picker dialog (for tests). */
    var dialog: AlertDialog? = null
        private set

    /** Shows the saved signatures and initials; [onPicked] gets the chosen (or new) one. */
    fun pick(onPicked: (SignatureStore.Saved) -> Unit) {
        val saved = store.list()
        if (saved.isEmpty()) {
            create(SignatureStore.Kind.SIGNATURE, onPicked)
            return
        }
        val density = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        lateinit var dialog: AlertDialog
        for (s in saved) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.panel_row_bg)
                contentDescription = context.getString(if (s.kind == SignatureStore.Kind.INITIALS) R.string.initials else R.string.signature)
                setOnClickListener {
                    dialog.dismiss()
                    onPicked(s)
                }
            }
            row.addView(TextView(context).apply {
                text = context.getString(if (s.kind == SignatureStore.Kind.INITIALS) R.string.initials else R.string.signature)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                minWidth = (64 * density).toInt()
            })
            row.addView(ImageView(context).apply {
                setImageBitmap(BitmapFactory.decodeFile(s.file.path))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                maxHeight = (56 * density).toInt()
            }, LinearLayout.LayoutParams(0, (56 * density).toInt(), 1f))
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_delete)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = context.getString(R.string.annot_delete)
                setOnClickListener {
                    store.delete(s)
                    dialog.dismiss()
                    pick(onPicked)
                }
            }, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.signatures)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.new_signature) { _, _ -> create(SignatureStore.Kind.SIGNATURE, onPicked) }
            .setNeutralButton(R.string.new_initials) { _, _ -> create(SignatureStore.Kind.INITIALS, onPicked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        this.dialog = dialog
    }

    /** Asks how to make a new signature (or initials): draw, type or picture. */
    fun create(kind: SignatureStore.Kind, onPicked: (SignatureStore.Saved) -> Unit) {
        val title = if (kind == SignatureStore.Kind.INITIALS) R.string.new_initials else R.string.new_signature
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(arrayOf(context.getString(R.string.sign_draw), context.getString(R.string.sign_type), context.getString(R.string.sign_image))) { _, which ->
                when (which) {
                    0 -> draw(kind, onPicked)
                    1 -> type(kind, onPicked)
                    else -> {
                        pendingKind = kind
                        pendingResult = onPicked
                        pickImage.launch("image/*")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The open drawing pad (for tests). */
    var pad: SignaturePadView? = null
        private set

    fun draw(kind: SignatureStore.Kind, onPicked: (SignatureStore.Saved) -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = SignaturePadView(context).apply {
            id = R.id.signature_pad
            setBackgroundColor(Color.WHITE)
            contentDescription = context.getString(R.string.sign_here)
        }
        this.pad = pad
        val frame = LinearLayout(context).apply {
            val p = (16 * density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ((if (kind == SignatureStore.Kind.INITIALS) 140 else 180) * density).toInt()))
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sign_here)
            .setView(frame)
            .setPositiveButton(R.string.use_signature, null)
            .setNeutralButton(R.string.clear, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        this.dialog = dialog
        val use = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        use.isEnabled = false
        pad.onChanged = { use.isEnabled = !pad.isEmpty }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        use.setOnClickListener {
            val saved = store.save(pad.toBitmap(), kind) ?: return@setOnClickListener
            dialog.dismiss()
            onPicked(saved)
        }
    }

    fun type(kind: SignatureStore.Kind, onPicked: (SignatureStore.Saved) -> Unit) {
        val density = context.resources.displayMetrics.density
        val profileName = ProfileStore(context).profile.fullName
        val input = EditText(context).apply {
            id = R.id.signature_text
            setHint(if (kind == SignatureStore.Kind.INITIALS) R.string.initials_hint else R.string.signature_name_hint)
            setSingleLine()
            setText(if (kind == SignatureStore.Kind.INITIALS) initialsOf(profileName) else profileName)
        }
        val styles = RadioGroup(context)
        val buttons = SignatureStore.typedStyles.mapIndexed { i, typeface ->
            RadioButton(context).apply {
                id = View.generateViewId()
                this.typeface = typeface
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(SignatureStore.INK)
                isChecked = i == 0
            }.also(styles::addView)
        }
        fun preview() {
            val text = input.text.toString().ifBlank { context.getString(if (kind == SignatureStore.Kind.INITIALS) R.string.initials_hint else R.string.signature_name_hint) }
            buttons.forEach { it.text = text }
        }
        preview()
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = preview()
        })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
            addView(styles)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sign_type)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.use_signature, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        this.dialog = dialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = context.getString(R.string.field_required)
                return@setOnClickListener
            }
            val index = buttons.indexOfFirst { it.isChecked }.coerceAtLeast(0)
            val bitmap = SignatureStore.renderTyped(text, SignatureStore.typedStyles[index])
            val saved = store.save(bitmap, kind)
            bitmap.recycle()
            dialog.dismiss()
            saved?.let(onPicked)
        }
    }

    /** Imports a picture of a signature, making its paper transparent. */
    fun importImage(uri: Uri, kind: SignatureStore.Kind, onPicked: (SignatureStore.Saved) -> Unit) {
        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: return@runCatching null
                    val clear = SignatureStore.removeBackground(bitmap)
                    bitmap.recycle()
                    store.save(clear, kind).also { clear.recycle() }
                }.getOrNull()
            }
            if (saved == null) Toast.makeText(context, R.string.image_failed, Toast.LENGTH_LONG).show()
            else onPicked(saved)
        }
    }

    private fun initialsOf(name: String) =
        name.split(' ', '-').filter { it.isNotBlank() }.joinToString("") { it.first().uppercase() }
}
