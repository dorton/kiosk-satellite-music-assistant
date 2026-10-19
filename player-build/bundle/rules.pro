# The plugin host constructs this entry by name and provides the SDK classes.
-keep class net.markandrew.kiosk.music.MusicAssistantPlugin { public *; }
-keep class net.markandrew.kiosk.music.receiver.PluginReceiver { public *; }
-keep class me.jxl.kiosk.plugins.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder
# Coroutine dispatcher discovery needs this provider when META-INF is not packaged.
-keep,allowobfuscation class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep,allowobfuscation class kotlinx.coroutines.internal.FastServiceLoader { *; }
# DexClassLoader is parent-first: dependency classes must never overlap the host.
-repackageclasses net.markandrew.kiosk.music.internal
# Do not ship upstream development logging or evaluate its message builders.
-assumenosideeffects class android.util.Log { public static int *(...); }
-assumenosideeffects class com.sendspindroid.shared.log.Log { public int *(...); }
