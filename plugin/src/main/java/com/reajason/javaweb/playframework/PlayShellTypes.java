package com.reajason.javaweb.playframework;

/**
 * Shell types this plugin adds. They are not registered in the host's {@code ShellType}
 * constants because the host cannot hand a Play-specific type to the built-in Godzilla and
 * Command tools: a server only contributes injectors, never new shell classes for a built-in
 * tool. Each type therefore comes with its own tool entry in this plugin.
 */
public final class PlayShellTypes {

    public static final String AKKA_HANDLER = "AkkaHandler";
    public static final String PEKKO_HANDLER = "PekkoHandler";

    private PlayShellTypes() {
    }
}
