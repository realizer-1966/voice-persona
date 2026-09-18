# Keep the JNI entry points reachable from native code.
-keepclasseswithmembernames class com.voicepersona.llm.** { native <methods>; }
-keepclasseswithmembernames class com.voicepersona.asr.** { native <methods>; }
-keep class com.voicepersona.llm.TokenCallback { *; }
