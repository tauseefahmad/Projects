package com.example.pdfreader

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pdfreader.ReaderTestSupport.context
import com.example.pdfreader.ReaderTestSupport.openUri
import com.example.pdfreader.ReaderTestSupport.waitFor
import com.example.pdfreader.ReaderTestSupport.waitForPageLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ToolsTest {

    private val output = File(context.cacheDir, "tool-output.bin")

    @Before
    fun setUp() {
        ReaderTestSupport.resetAppState()
        output.delete()
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))))
    }

    @After
    fun tearDown() = Intents.release()

    private fun tool(title: Int) {
        openActionBarOverflowOrOptionsMenu(context)
        onView(withText(R.string.tools_menu)).perform(click())
        onView(withText(title)).perform(click())
    }

    private fun text(name: String, vararg pages: String) = TestPdfs.createWithText(context, name, pages.toList())

    @Test
    fun mergeCombinesFilesInOrderWithABookmarkPerFile() {
        val b = text("b.pdf", "B1")
        val c = text("c.pdf", "C1", "C2")
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().apply { clipData = ClipData.newRawUri("", b).apply { addItem(ClipData.Item(c)) } },
            )
        )
        openUri(text("a.pdf", "A1", "A2")).use {
            waitForPageLabel("1 / 2")
            tool(R.string.merge_title)
            onView(withText(R.string.merge_add)).inRoot(isDialog()).perform(click())
            waitFor { onView(withText(context.getString(R.string.merge_item, 3, "c.pdf"))).inRoot(isDialog()).check { v, _ -> checkNotNull(v) } }
            onView(withText(R.string.merge_action)).inRoot(isDialog()).perform(click())
            // The merged file opens in the reader.
            waitForPageLabel("1 / 5", timeoutMs = 20_000)
            val merged = Uri.fromFile(output)
            assertEquals(listOf("A1", "A2", "B1", "C1", "C2"), TestPdfs.pageTexts(context, merged))
            assertEquals(listOf("a", "b", "c"), TestPdfs.outlineTitles(context, merged))
        }
    }

    @Test
    fun splitByRangesIntoAZip() {
        openUri(text("five.pdf", "P1", "P2", "P3", "P4", "P5")).use {
            waitForPageLabel("1 / 5")
            tool(R.string.split_title)
            // Default ranges for 5 pages: "1-3, 4-5"; default output: one ZIP.
            onView(withText(R.string.split_action)).inRoot(isDialog()).perform(click())
            waitFor(20_000) { check(output.exists() && output.length() > 0) }
            Thread.sleep(500)
            ZipFile(output).use { zip ->
                val entries = zip.entries().toList().sortedBy { it.name }
                assertEquals(listOf("five-1.pdf", "five-2.pdf"), entries.map { it.name })
                val counts = entries.map { entry ->
                    val part = File(context.cacheDir, "part-${entry.name}")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    TestPdfs.pageTexts(context, Uri.fromFile(part))
                }
                assertEquals(listOf(listOf("P1", "P2", "P3"), listOf("P4", "P5")), counts)
            }
        }
    }

    @Test
    fun cropMarginsSavesInPlace() {
        val uri = text("crop.pdf", "Hello")
        openUri(uri).use {
            waitForPageLabel("1 / 1")
            tool(R.string.crop_title)
            onView(withText(R.string.apply)).inRoot(isDialog()).perform(click())
            waitFor(20_000) {
                val (w, h) = TestPdfs.pageSizes(context, uri).first()
                assertEquals(595.28f - 2 * 28.35f, w, 0.5f)
                assertEquals(841.89f - 2 * 28.35f, h, 0.5f)
            }
            assertEquals(listOf("Hello"), TestPdfs.pageTexts(context, uri))
        }
    }

    @Test
    fun resizeLetterPagesToA4() {
        val uri = TestPdfs.create(context, "letter.pdf", 2, width = 612, height = 792)
        openUri(uri).use {
            waitForPageLabel("1 / 2")
            tool(R.string.resize_title)
            onView(withText(R.string.size_a4)).inRoot(isDialog()).perform(click())
            waitFor(20_000) {
                TestPdfs.pageSizes(context, uri).forEach { (w, h) ->
                    assertEquals(595.28f, w, 0.5f)
                    assertEquals(841.89f, h, 0.5f)
                }
            }
        }
    }

    @Test
    fun pageNumbersAreAddedToEveryPage() {
        val uri = text("numbers.pdf", "Alpha", "Beta", "Gamma")
        openUri(uri).use {
            waitForPageLabel("1 / 3")
            tool(R.string.stamp_title)
            onView(withText(R.string.apply)).inRoot(isDialog()).perform(click())
            waitFor(20_000) {
                val texts = TestPdfs.pageTexts(context, uri)
                assertTrue(texts.toString(), texts[0].contains("Page 1 of 3") && texts[2].contains("Page 3 of 3"))
                assertTrue(texts[1].contains("Beta"))
            }
        }
    }

    @Test
    fun watermarkIsAddedToEveryPage() {
        val uri = text("mark.pdf", "One", "Two")
        openUri(uri).use {
            waitForPageLabel("1 / 2")
            tool(R.string.watermark_title)
            onView(withText(R.string.apply)).inRoot(isDialog()).perform(click())
            waitFor(20_000) {
                val texts = TestPdfs.pageTexts(context, uri)
                assertTrue(texts.toString(), texts.all { it.contains("CONFIDENTIAL") })
            }
        }
    }
}
