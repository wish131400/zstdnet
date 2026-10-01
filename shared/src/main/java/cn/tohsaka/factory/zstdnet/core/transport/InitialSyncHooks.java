package cn.tohsaka.factory.zstdnet.core.transport;

import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class InitialSyncHooks {
    private InitialSyncHooks() {
    }

    public static void beforeInitialSync(Connection connection, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null && (server.isDedicatedServer() || server.isPublished())
            && ServerProxyConfigFile.readEnabled() && !connection.isMemoryConnection()
            && ZstdPipeline.canUpgrade(connection)) {
            LanCompressionSync.requestIntegratedUpgrade(player);
        }
    }
}
