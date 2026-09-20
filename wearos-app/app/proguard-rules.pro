# Keep ANCS model classes
-keep class com.wearos.ancsbridge.model.** { *; }

# Drop verbose and debug logging from release builds. Each call still costs string
# building and an IO write on the watch, and these fire for every ANCS attribute.
# Info, warning and error logging is kept so problems stay diagnosable.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static boolean isLoggable(java.lang.String, int);
}
