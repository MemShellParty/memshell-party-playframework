package com.reajason.javaweb.playframework;

import com.reajason.javaweb.memshell.ShellTool;
import com.reajason.javaweb.memshell.obfuscation.MethodContracts;
import com.reajason.javaweb.memshell.obfuscation.MethodSignature;
import com.reajason.javaweb.memshell.server.AbstractServer;
import com.reajason.javaweb.memshell.server.InjectorMapping;
import com.reajason.javaweb.memshell.server.ToolMapping;
import com.reajason.javaweb.playframework.injector.PlayHandlerInjector;
import com.reajason.javaweb.playframework.shell.PlayCommandShell;
import com.reajason.javaweb.playframework.shell.PlayGodzillaShell;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Play exposes no servlet-style extension point, so the only stable interception seam is the
 * {@code MapAsync} stage that every Play request crosses. Both Play generations use the same
 * seam; only the Akka/Pekko package prefix differs, and the injector resolves that at runtime.
 */
public class PlayFrameworkServer extends AbstractServer {

    @Override
    public Set<MethodSignature> getShellMethodContracts(String shellType) {
        if (!PlayShellTypes.AKKA_HANDLER.equals(shellType) && !PlayShellTypes.PEKKO_HANDLER.equals(shellType)) {
            return super.getShellMethodContracts(shellType);
        }
        // Both entry points are called reflectively from the injector by name.
        Set<MethodSignature> contracts = new LinkedHashSet<MethodSignature>(
                super.getShellMethodContracts(shellType));
        contracts.addAll(MethodContracts.signatures("handle", "matches"));
        return Collections.unmodifiableSet(contracts);
    }

    @Override
    public InjectorMapping getShellInjectorMapping() {
        return InjectorMapping.builder()
                .addInjector(PlayShellTypes.AKKA_HANDLER, PlayHandlerInjector.class.getName())
                .addInjector(PlayShellTypes.PEKKO_HANDLER, PlayHandlerInjector.class.getName())
                .build();
    }

    @Override
    public int getMinimumTargetJreVersion() {
        return Opcodes.V1_8;
    }

    /**
     * The built-in tools keep their names and configuration. The server only adds the two Play
     * handler types; the shell template loader is inherited from {@link #getInjectorTemplateClassLoader()}.
     */
    @Override
    public Map<String, ToolMapping> getToolMappingOverrides() {
        Map<String, ToolMapping> overrides = new LinkedHashMap<String, ToolMapping>();
        overrides.put(ShellTool.Godzilla, playMapping(PlayGodzillaShell.class));
        overrides.put(ShellTool.Command, playMapping(PlayCommandShell.class));
        return Collections.unmodifiableMap(overrides);
    }

    private static ToolMapping playMapping(Class<?> shell) {
        return ToolMapping.builder()
                .addShellClass(PlayShellTypes.AKKA_HANDLER, shell.getName())
                .addShellClass(PlayShellTypes.PEKKO_HANDLER, shell.getName())
                .build();
    }

    @Override
    public String getShellPackageName(String serverName) {
        return "play.core.server";
    }
}
