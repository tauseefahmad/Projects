package com.example.pdfreader.core

/**
 * A link area on a page, in PDF points (top-left origin, relative to the crop box).
 * Exactly one of [targetPage] (internal link) or [uri] (external link) is set.
 */
data class PageLink(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val targetPage: Int? = null,
    val uri: String? = null,
)

object Links {

    /** The link under (x, y), allowing [slop] points around small links for fingers. */
    fun hit(links: List<PageLink>, x: Float, y: Float, slop: Float = 4f): PageLink? =
        links.filter { x >= it.left - slop && x <= it.right + slop && y >= it.top - slop && y <= it.bottom + slop }
            // Prefer the smallest area when links overlap.
            .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }

    private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")

    /**
     * Whether an external link may be handed to other apps. Only web, email and phone links are
     * allowed; schemes like javascript:, file: or intent: in a document could be abused.
     */
    fun isSafeExternal(uri: String): Boolean {
        val scheme = uri.trim().substringBefore(':', "").lowercase()
        return scheme in SAFE_SCHEMES && uri.trim().length > scheme.length + 1
    }

    /** Adds https:// to bare web addresses such as "www.example.com". */
    fun normalize(uri: String): String {
        val trimmed = uri.trim()
        return if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
    }
}
