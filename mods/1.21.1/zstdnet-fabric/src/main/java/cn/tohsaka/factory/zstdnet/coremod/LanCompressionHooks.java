package cn.tohsaka.factory.zstdnet.coremod;

import net.minecraft.server.MinecraftServer;

public final class LanCompressionHooks {
    // Retain vanilla compression during login; the negotiated Zstd pipeline replaces it later.
    public static final int LAN_THRESHOLD = 256;

    private LanCompressionHooks() {
    }

    public static boolean shouldOverrideCompressionThreshold(MinecraftServer server) {
        return server != null && !server.isDedicatedServer() && server.isPublished();
    }
}
