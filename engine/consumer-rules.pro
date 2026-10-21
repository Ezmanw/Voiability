# JNI callbacks are looked up by name from native code.
-keep class com.voiability.engine.** { native <methods>; }
-keep interface com.voiability.engine.TokenSink { *; }
