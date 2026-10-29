package io.github.opencodeandroid.diagnostics.protocol;

/** Debug-only, signature-protected control of fixed synthetic scenarios. No paths or commands. */
public final class DiagnosticsProtocol {
    public static final int VERSION = 1;
    public static final int HELLO = 0;
    public static final int STATUS = 1;
    public static final int CANCEL = 2;
    public static final String TARGET = "io.github.opencodeandroid";
    public static final String CONTROLLER = "io.github.opencodeandroid.diagnostics";
    public static final String SERVICE = TARGET + ".diagnostics.BackgroundDiagnosticsService";
    public static final String PERMISSION = TARGET + ".permission.BACKGROUND_DIAGNOSTICS";
    public static final String START = TARGET + ".diagnostics.START_BACKGROUND_SOAK";
    private DiagnosticsProtocol() {}
}
