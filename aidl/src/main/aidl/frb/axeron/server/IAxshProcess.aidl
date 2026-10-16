package frb.axeron.server;

// One remote shell from IAxeronService.newAxshProcess. One object per session, so a caller can
// run several at once.
interface IAxshProcess {

    // TIOCSWINSZ's struct winsize packed little-endian: rows | cols << 16 | xpixel << 32 | ypixel << 48.
    // Ignored without a PTY.
    void setWindowSize(long size);

    // Blocks until the shell exits.
    int waitFor();

    // Kills the shell's whole session.
    void destroy();
}
