# Shizuku loads DumpService by class name in a separate process.
-keep class com.saplin.edrc.DumpService {
    public <init>();
    public <init>(android.content.Context);
    public void destroy();
    public *;
}
-keep class com.saplin.edrc.IDumpService { *; }
-keep class com.saplin.edrc.IDumpService$Stub { *; }
-keep class com.saplin.edrc.IDumpService$Stub$Proxy { *; }
-keep class com.saplin.edrc.** { *; }
