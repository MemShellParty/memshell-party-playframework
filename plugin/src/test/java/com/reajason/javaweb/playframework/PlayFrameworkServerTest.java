package com.reajason.javaweb.playframework;

import com.reajason.javaweb.memshell.ShellTool;
import com.reajason.javaweb.memshell.obfuscation.MethodContracts;
import com.reajason.javaweb.memshell.obfuscation.MethodSignature;
import com.reajason.javaweb.memshell.server.InjectorMapping;
import com.reajason.javaweb.memshell.server.ToolMapping;
import com.reajason.javaweb.memshell.spi.ServerMeta;
import com.reajason.javaweb.memshell.spi.ServerProvider;
import com.reajason.javaweb.playframework.injector.PlayHandlerInjector;
import com.reajason.javaweb.playframework.shell.PlayCommandShell;
import com.reajason.javaweb.playframework.shell.PlayGodzillaShell;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayFrameworkServerTest {

    private final PlayFrameworkServer server = new PlayFrameworkServer();

    @Test
    void mapsBothPlayRuntimesToTheSameInjector() {
        InjectorMapping mapping = server.getShellInjectorMapping();

        assertEquals(new HashSet<String>(Arrays.asList(
                PlayShellTypes.AKKA_HANDLER, PlayShellTypes.PEKKO_HANDLER)), mapping.getSupportedShellTypes());
        assertEquals(PlayHandlerInjector.class.getName(), mapping.getInjector(PlayShellTypes.AKKA_HANDLER));
        assertEquals(PlayHandlerInjector.class.getName(), mapping.getInjector(PlayShellTypes.PEKKO_HANDLER));
    }

    @Test
    void overridesGodzillaAndCommandWithPlayShells() {
        Map<String, ToolMapping> overrides = server.getToolMappingOverrides();

        assertEquals(new HashSet<String>(Arrays.asList(ShellTool.Godzilla, ShellTool.Command)), overrides.keySet());
        assertShellMapping(overrides.get(ShellTool.Godzilla), PlayGodzillaShell.class);
        assertShellMapping(overrides.get(ShellTool.Command), PlayCommandShell.class);
    }

    @Test
    void raisesTheTargetToJava8() {
        assertEquals(Opcodes.V1_8, server.getMinimumTargetJreVersion());
    }

    @Test
    void placesShellsInPlayPackage() {
        assertEquals("play.core.server", server.getShellPackageName(PlayFrameworkServerProvider.NAME));
    }

    @Test
    void keepsHandleAndMatchesContract() {
        Set<String> names = server.getShellMethodContracts(PlayShellTypes.AKKA_HANDLER).stream()
                .map(MethodSignature::getName)
                .collect(Collectors.toSet());

        assertTrue(names.contains("handle"));
        assertTrue(names.contains("matches"));
        assertEquals(MethodContracts.CONSERVATIVE, server.getShellMethodContracts("NoSuchHandler"));
    }

    @Test
    void providerCarriesSpiMetadataAndServiceEntry() throws Exception {
        ServerMeta meta = PlayFrameworkServerProvider.class.getAnnotation(ServerMeta.class);
        assertNotNull(meta);
        assertEquals(PlayFrameworkServerProvider.NAME, meta.name());
        assertEquals(1000, meta.order());
        assertInstanceOf(PlayFrameworkServer.class, new PlayFrameworkServerProvider().createServer());

        try (InputStream input = PlayFrameworkServerProvider.class.getResourceAsStream(
                "/META-INF/services/" + ServerProvider.class.getName())) {
            assertNotNull(input, "metainf-services did not generate the provider entry");
            String entry = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines()
                    .filter(line -> !line.trim().isEmpty())
                    .collect(Collectors.joining("\n"));
            assertEquals(PlayFrameworkServerProvider.class.getName(), entry);
        }
    }

    private static void assertShellMapping(ToolMapping mapping, Class<?> shell) {
        assertNotNull(mapping);
        assertEquals(new HashSet<String>(Arrays.asList(
                PlayShellTypes.AKKA_HANDLER, PlayShellTypes.PEKKO_HANDLER)), mapping.getSupportedShellTypes());
        assertEquals(shell.getName(), mapping.getShellClassMap().get(PlayShellTypes.AKKA_HANDLER));
        assertEquals(shell.getName(), mapping.getShellClassMap().get(PlayShellTypes.PEKKO_HANDLER));
    }
}
