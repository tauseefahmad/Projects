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
