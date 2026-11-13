# Proguard / R8 rules for EnduLink

# Mantener metadatos y serializadores de kotlinx.serialization y corrutinas
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class com.oscar.endulink.**$$serializer { *; }
-keepclassmembers class com.oscar.endulink.** {
    *** Companion;
}

# Servicios y componentes declarados en AndroidManifest
-keep class com.oscar.endulink.platform.BikeForegroundService { *; }

# Dependencias opcionales en JVM referenciadas por OkHttp / Ktor
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.MethodHandles$Lookup


