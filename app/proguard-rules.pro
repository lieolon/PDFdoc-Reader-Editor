# PdfBox-Android: optional JPEG2000 and BouncyCastle dependencies are not shipped.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# Tesseract and Leptonica are called from native code by class and member names.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Edits are stored as JSON inside PDF annotations.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.pdfdoc.domain.edit.**$$serializer { *; }
-keepclassmembers class app.pdfdoc.domain.edit.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
