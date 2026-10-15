# Keep default rules; the app uses only platform and AndroidX APIs.
# PdfBox loads some classes reflectively and references optional JPEG2000 support.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.gemalto.jp2.**
