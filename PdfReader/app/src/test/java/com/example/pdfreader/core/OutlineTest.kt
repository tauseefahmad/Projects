package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineTreeTest {

    private val tree = listOf(
        OutlineNode("Intro", 0),
        OutlineNode(
            "Part 1", 2,
            listOf(
                OutlineNode("Chapter 1", 3, listOf(OutlineNode("1.1", 4))),
                OutlineNode("Chapter 2", 6),
            ),
        ),
        OutlineNode("Appendix", 9),
    )

    @Test
    fun collapsedTreeShowsOnlyTopLevel() {
        val rows = OutlineTree.visibleRows(tree, emptySet())
        assertEquals(listOf("Intro", "Part 1", "Appendix"), rows.map { it.node.title })
        assertEquals(listOf("0", "1", "2"), rows.map { it.id })
        assertEquals(listOf(false, true, false), rows.map { it.hasChildren })
    }

    @Test
    fun expandingShowsChildrenIndented() {
        val rows = OutlineTree.visibleRows(tree, setOf("1"))
        assertEquals(listOf("Intro", "Part 1", "Chapter 1", "Chapter 2", "Appendix"), rows.map { it.node.title })
        assertEquals(listOf(0, 0, 1, 1, 0), rows.map { it.depth })
        assertEquals(true, rows[1].expanded)
    }

    @Test
    fun nestedExpansionNeedsParentsExpanded() {
        assertEquals(3, OutlineTree.visibleRows(tree, setOf("1/0")).size)
        val rows = OutlineTree.visibleRows(tree, setOf("1", "1/0"))
        assertEquals("1.1", rows[3].node.title)
        assertEquals(2, rows[3].depth)
        assertEquals("1/0/0", rows[3].id)
    }

    @Test
    fun sectionForPageIsDeepestEntryAtOrBefore() {
        assertEquals("0", OutlineTree.sectionFor(tree, 1))
        assertEquals("1/0/0", OutlineTree.sectionFor(tree, 5))
        assertEquals("1/1", OutlineTree.sectionFor(tree, 7))
        assertEquals("2", OutlineTree.sectionFor(tree, 50))
        assertNull(OutlineTree.sectionFor(listOf(OutlineNode("Late", 4)), 2))
    }

    @Test
    fun ancestorsOfNestedId() {
        assertEquals(listOf("1", "1/0"), OutlineTree.ancestors("1/0/0"))
        assertEquals(emptyList<String>(), OutlineTree.ancestors("2"))
    }
}

class BookmarksTest {

    @Test
    fun toggleAddsSortedAndRemoves() {
        var list = Bookmarks.toggle(emptyList(), 5, "Page 6", 1)
        list = Bookmarks.toggle(list, 2, "Page 3", 2)
        assertEquals(listOf(2, 5), list.map { it.page })
        list = Bookmarks.toggle(list, 5, "ignored", 3)
        assertEquals(listOf(2), list.map { it.page })
    }

    @Test
    fun renameKeepsOldTitleWhenBlank() {
        val list = listOf(Bookmark(2, "Page 3", 1))
        assertEquals("Budget", Bookmarks.rename(list, 2, "Budget")[0].title)
        assertEquals("Page 3", Bookmarks.rename(list, 2, "  ")[0].title)
    }
}
