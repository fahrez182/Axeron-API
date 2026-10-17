# libaxsh.so starts this by name through app_process.
-keep class frb.axeron.shell.Bootstrap {
    public static void main(java.lang.String[]);
}
