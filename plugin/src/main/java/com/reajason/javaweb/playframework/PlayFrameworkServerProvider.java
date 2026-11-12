package com.reajason.javaweb.playframework;

import com.reajason.javaweb.memshell.server.AbstractServer;
import com.reajason.javaweb.memshell.spi.ServerMeta;
import com.reajason.javaweb.memshell.spi.ServerProvider;
import org.kohsuke.MetaInfServices;

@MetaInfServices(ServerProvider.class)
@ServerMeta(name = PlayFrameworkServerProvider.NAME, order = 1000)
public class PlayFrameworkServerProvider implements ServerProvider {

    public static final String NAME = "PlayFramework";

    @Override
    public AbstractServer createServer() {
        return new PlayFrameworkServer();
    }
}
