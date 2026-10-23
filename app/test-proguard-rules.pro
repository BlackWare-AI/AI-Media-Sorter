# AndroidX test dependencies include Error Prone compile-time annotations.
# Their annotation element type is a JDK compiler API, never executed on Android.
-dontwarn javax.lang.model.element.Modifier
