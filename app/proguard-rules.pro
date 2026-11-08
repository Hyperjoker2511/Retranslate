# ReTranslate release rules (reviewed in P8, PLAN.md §11).
#
# Libraries with their own consumer rules: Room, WorkManager, OkHttp,
# kotlinx.serialization, Compose — their AAR rules are applied
# automatically and need nothing here.

# PDFBox-Android references an optional JPEG 2000 decoder
# (com.gemalto.jp2) that is not a dependency and not present on Android;
# PDFBox checks for it reflectively and works without it. R8 must not
# treat the reference as an error. (Found by the P8 release build:
# "Missing class com.gemalto.jp2.JP2Decoder ... JPXFilter.readJPX".)
-dontwarn com.gemalto.jp2.JP2Decoder

# kotlinx.serialization (official rules): the provider response models
# are parsed reflectively through generated serializers — if R8 stripped
# them, every provider response would fail at runtime on device while
# JVM tests (unminified) stayed green. Keep them explicitly.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
