# PDF Reader for Android

A lightweight PDF viewer written in Kotlin. It uses Android's built-in
`PdfRenderer`, so there are no third-party PDF libraries and the APK stays small.

## Features

- Open PDFs with the system file picker, or from any app via **Open with** (Files, Downloads, Gmail, browsers)
- Smooth vertical scrolling, with pages rendered lazily and cached
- Pinch-to-zoom (up to 5×), double-tap to zoom, and panning while zoomed; pages re-render sharper after zooming
- Page indicator and **Go to page**
- **Dark pages** mode that inverts page colours for night reading
- **Recent** list that reopens a file at the page you left off on
- Survives rotation without reloading the document
- Clear messages for password-protected or damaged files

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
*PDF Reader (Android)* GitHub Actions workflow. Download `pdf-reader-debug-apk`
from the workflow run's **Artifacts** section and install it on your phone
(allow "install unknown apps" for your browser or file manager).

## Project layout

| File | Purpose |
| --- | --- |
| `MainActivity.kt` | Toolbar, file picker, empty state/recent list, page indicator |
| `ReaderViewModel.kt` | Opens and holds the document; survives rotation |
| `PdfDocument.kt` | Thread-safe wrapper around `PdfRenderer` |
| `PageAdapter.kt` | Renders pages into the list with an in-memory cache |
| `ZoomableRecyclerView.kt` | Pinch/double-tap zoom and panning on top of RecyclerView |
| `RecentFiles.kt` | Remembers recent documents and last page |

## Limitations

`PdfRenderer` only displays pages, so this app has no text selection, text search,
or password-protected PDF support. Those features need a full PDF engine such as
PdfiumAndroid or MuPDF.
