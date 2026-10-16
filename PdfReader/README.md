# PDF Reader for Android

A PDF reader for Android written in Kotlin. Pages are rendered with Android's built-in
`PdfRenderer`; document structure (text, links, outline, metadata, passwords) is read with
PdfBox-Android.

## Features

Built feature by feature from the PDF App Feature Roadmap. Every feature has automated
tests that run on an Android emulator in CI before a build is published.

**Phase 1: Viewer essentials**

| Feature | What you get |
| --- | --- |
| Page navigation | Previous/next buttons, "12 / 240" label with Go to page, draggable fast-scroll thumb, volume-key paging (optional), keyboard shortcuts |
| Scroll modes | Continuous vertical, single page (swipe sideways), two-page spread with optional cover page |
| Zoom and fit | Fit width, fit page, actual size, 50–400% presets, pinch and double-tap; pages re-render sharp when zoomed |
| Thumbnails | Side panel with every page; current page outlined; tap to jump |
| Outline and bookmarks | The PDF's table of contents as a tree; your own bookmarks per file (rename, delete) |
| Text search | "3 of 27" count, next/previous, match case, whole word, highlights, results list with context |
| Text selection | Long-press to select, drag handles, Copy / Share / Select all / Translate |
| Links | Internal links jump; external links ask first (only web, email and phone links) |
| Rotate view | Turn pages 90° for reading (file unchanged) |
| Print and share | Android print dialog with page ranges; share sheet; save a copy anywhere |
| Document properties | Title, author, pages, paper size, file size, dates, PDF version, encryption |
| Themes | Follow system, light, dark, sepia; invert page colors for night reading |
| Recent files | Start screen of recent files; reopen at the last page and zoom |
| Open from anywhere | Open with, file picker, share to app, download from URL, password prompt |
| Full screen | Hides all bars, tap edges to turn pages, keeps the screen awake |

**Phase 3: Page management**

| Feature | What you get |
| --- | --- |
| Page organizer | Grid of pages: select, drag to reorder, move to position, rotate, delete (with undo), duplicate, extract, insert blank pages, other PDFs or images |
| Merge and split | Combine PDFs (a bookmark per file); split by ranges, every N pages or one file per page (zip) |
| Crop and resize | Trim margins; scale pages to A4, Letter and more |
| Page numbers and watermark | Number pages in any corner with your own format; text watermark with size, angle and opacity |

**Phase 2: Annotation** (tap ✎ *Annotate*)

| Feature | What you get |
| --- | --- |
| Text markup | Highlight, underline, strikethrough, squiggly: drag over text, or select text and pick Highlight |
| Notes and comments | Sticky notes; a comment on any annotation; tap a note while reading to read it |
| Free text | Text boxes with font size and color |
| Ink | Pen and highlighter with smoothing and pen pressure; once a stylus is used, fingers scroll instead of drawing (palm rejection) |
| Shapes, stamps, images | Rectangle, ellipse, line, arrow; Approved / Draft / Confidential / Paid or custom stamps with date and name; pictures such as a logo |
| Edit | Select to move, resize, recolor, comment or delete; eraser; undo/redo (Ctrl+Z / Ctrl+Y) |
| Annotation panel | All annotations by page with filters by type and author; export a text summary |
| Saving | Saved as standard PDF annotations other apps can read and edit; save in place or as a copy; flattened copy; unsaved work is kept as a draft and offered again |

### Forms and signatures

| Feature | What you get |
| --- | --- |
| Fill forms | Tap text, checkbox, radio, dropdown and date fields; Next/Previous move between fields with the right keyboard; number, email, phone and date checks; required fields are flagged before saving |
| Autofill | Save your details once (name, address, email, phone, date of birth…) and fill matching fields in one tap |
| Flat forms | Tick ✓, cross ✗ and today's date tools for forms without fields; text boxes for anything else |
| Signatures | Draw, type in a handwriting font, or import a photo (the paper is made transparent); signatures and initials are saved for reuse; place, move, resize and copy onto several pages; tap a signature field to sign it |
| Form builder | Draw text, date, checkbox, dropdown and signature fields on any PDF; they are saved as real fields other apps can fill |
| Flatten | Save a flattened copy so fields and annotations can no longer be changed |

Not included yet: sending a document to others for signature (needs a server) and certificate-based digital signatures (PAdES).

### Conversion, OCR and compression

| Feature | What you get |
| --- | --- |
| OCR | *Tools → Recognize text*: scanned pages become searchable and selectable (invisible text layer over the words); English, German and other Latin-script languages; sideways and upside-down pages are detected; runs on the phone |
| Scan | *Scan document*: camera scanner with edge detection, perspective and color correction, then OCR and compression in one step |
| Compress | High quality / balanced / smallest presets; shows the size before and after |
| Export | Pages as PNG or JPG (72–300 dpi, zip for several pages); text, Markdown, HTML or Word (text only); the table on a page as CSV |
| Pictures to PDF | One page per picture (JPG, PNG, WebP, HEIC), turned the right way up |
| Batch | Compress, OCR or clean many files at once into a folder you choose |

### Insights (on the phone, nothing is uploaded)

| Feature | What you get |
| --- | --- |
| Summary | The document's key sentences: one paragraph, one page or page by page; export as Markdown |
| Ask this document | Type a question; the passages that answer it best, with page numbers; tap to jump there with the words highlighted |
| Extract data | Recognizes invoices, receipts, contracts, payslips and letters; pulls out totals, dates, due dates, invoice numbers, IBAN/BIC, VAT IDs, emails and phone numbers; save as JSON or CSV |
| Review contract | Termination, automatic renewal, liability, payment, penalties, confidentiality, jurisdiction and term clauses, with warnings for unusual ones (help with reading, not legal advice) |
| Create bookmarks | A table of contents from the headings of documents that have none |

These use rules and statistics, not a language model. Free-form AI answers, translation of whole documents and study cards need a cloud AI service and are not included.

### Security and privacy

| Feature | What you get |
| --- | --- |
| Passwords | AES-256 open password; restrict printing, copying and editing with a permissions password; remove a password you know |
| Redaction | Draw areas or find IBANs, emails, phone numbers or any words; on saving, redacted pages are rebuilt as pictures so the removed text is gone from the file, not just covered |
| Hidden data | See and remove document information, XMP metadata, attached files and scripts |
| Privacy | Everything runs on the phone; *Offline only* blocks the one online feature (Open from URL) |

Needs a server, so not included: Office documents ↔ PDF (except the text-only Word export), PDF/A conversion.

### Keyboard shortcuts

| Keys | Action |
| --- | --- |
| Page Down, Space, →, J | Next page |
| Page Up, Shift+Space, ←, K | Previous page |
| Home / End | First / last page |
| Ctrl+G | Go to page |
| Ctrl+F | Search |
| Ctrl + / Ctrl − / Ctrl+0 | Zoom in / out / fit width |
| Ctrl+R | Rotate view |
| F11 / Esc | Enter / leave full screen |
| Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) | Undo / redo annotation changes |
| Delete | Delete the selected annotation |

## Requirements

- Android 8.0 (API 26) or newer
- To build: JDK 17 and the Android SDK (Android Studio installs both)

## Build and run

**Android Studio:** File → Open → select the `PdfReader` folder, wait for Gradle sync, then press Run.

**Command line:**

```bash
cd PdfReader
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Without installing anything:** every push that changes `PdfReader/` triggers the
*PDF Reader (Android)* GitHub Actions workflow: unit tests, then the instrumented tests on an
Android emulator. When everything passes, the APK is published as a GitHub pre-release named
"PDF Reader build N" — open the repository's **Releases** page on your phone, download the APK
and install it (allow "install unknown apps" for your browser or file manager). Builds are
signed with the same key, so each one installs over the previous one.

## Project layout

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | Toolbar, file picker, empty state/recent list, page indicator |
| `ReaderViewModel.kt` | Opens and holds the document; survives rotation |
| `PdfDocument.kt` | Thread-safe wrapper around `PdfRenderer` |
| `PageAdapter.kt` | Renders pages into the list with an in-memory cache |
| `ZoomableRecyclerView.kt` | Pinch/double-tap zoom and panning on top of RecyclerView |
| `RecentFiles.kt` | Remembers recent documents, last page and zoom |
| `PdfStructure.kt` | PdfBox-based outline, text, links and metadata |
| `SearchController.kt` | Background search across pages |
| `TextSelectionController.kt` | Long-press selection and the text toolbar |
| `ZoomController.kt` | Fit modes, presets and sharp detail rendering |
| `core/` | Plain-Kotlin logic (layout, zoom, search, geometry) with fast unit tests |

## Libraries

Pages are drawn by Android's `PdfRenderer`; text, links, outline and metadata come from
PdfBox-Android (Apache 2.0), which keeps the app free of AGPL licensing.
