# Keep the protocol payload classes intact: kotlinx.serialization generates
# serializers that R8 must not strip or rename.
-keepclassmembers class com.nearbyshare.protocol.** {
    *** Companion;
}
-keepclasseswithmembers class com.nearbyshare.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
