# kotlinx.serialization: сериализаторы генерируются и находятся по имени класса
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ru.whensclass.data.** {
    *** Companion;
}
-keepclasseswithmembers class ru.whensclass.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
