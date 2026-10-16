# libaxsh.so starts this by name through app_process.
-keep class frb.axeron.axsh.AxshBootstrap {
    public static void main(java.lang.String[]);
}
