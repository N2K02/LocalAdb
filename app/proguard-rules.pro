# Keep updater DTOs and serializers handled by the library's consumer ProGuard rules.
-keepattributes Signature,*Annotation*

# Shizuku instantiates the diagnostic helper with its public no-argument constructor.
-keep class dev.n2k02.localadb.LocalHelperService { public <init>(); }

# WorkManager uses Room's reflective generated database factory during AndroidX
# Startup ContentProvider initialization, before our Activity is created.
# Without these rules a minified release may remove the no-arg constructor:
# java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# Preserve the same constructor contract for any other generated Room databases.
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
