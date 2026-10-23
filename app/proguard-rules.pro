# PdfBox-Android: optional JPEG2000 and BouncyCastle dependencies are not shipped.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
