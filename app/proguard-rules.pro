# ============================================================================
# ML Kit – keep rules for R8 full mode
# ============================================================================
# ML Kit loads its component registrars via reflection (Class.getDeclaredConstructor).
# R8 full mode strips the no-arg constructors because it cannot see the reflective
# call sites, which causes:
#   NoSuchMethodException: com.google.mlkit.*Registrar.<init> []
# at runtime and ultimately an NPE inside TextRecognition.getClient().
#
# These rules ensure the registrars and their constructors survive minification.
# Ref: https://developers.google.com/ml-kit/android/text-recognition
# ============================================================================

# Keep all ML Kit component registrar classes and their constructors.
# These are instantiated reflectively by MlKitInitProvider.
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { <init>(); }
-keep class com.google.mlkit.vision.text.internal.TextRegistrar { <init>(); }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { <init>(); }

# Broader safety net – keep all ML Kit registrar classes (covers future
# additions / other ML Kit features) and their public no-arg constructors.
-keep class com.google.mlkit.**Registrar { <init>(); }

# Keep ML Kit model / SDK internal classes accessed via reflection.
-keep class com.google.android.gms.internal.mlkit_** { *; }

-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
