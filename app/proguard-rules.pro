# R8 rules for the release build (isMinifyEnabled + isShrinkResources).
# The stack ships its own consumer rules (AndroidX, Compose, Hilt/Dagger, coroutines).

# Keep line numbers so a stripped stack trace stays readable with the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# No log call survives in release builds: scanned content must never reach logcat (threat model T-STO-3).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Same for stack traces printed to stderr/logcat: WorkManager's WorkTypeConverters.byteArrayToSetOfTriggers
# calls Throwable.printStackTrace(), and R8 merges that library method into an app class (LF-29).
# A stack trace can carry scanned content in an exception message, so no such call may survive.
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
