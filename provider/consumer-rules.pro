-keepnames class moe.shizuku.api.BinderContainer
-keepclassmembers class moe.shizuku.api.BinderContainer {
   public static final android.os.Parcelable$Creator CREATOR;
}

# Bundle.getParcelable(key, Class) (Android 13+) checks creator.getClass().getEnclosingClass().
# Left alone, R8 merges the anonymous CREATOR into a top-level class and drops its
# EnclosingMethod/InnerClasses, so that is null and the read throws an NPE.
-keepattributes InnerClasses,EnclosingMethod
-keep class moe.shizuku.api.BinderContainer$1
