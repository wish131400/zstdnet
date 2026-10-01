package cn.tohsaka.factory.zstdnet.coremod;

import net.minecraft.server.MinecraftServer;

public final class LanCompressionHooks {
    public static final int VANILLA_THRESHOLD = 256;
    // Retain vanilla compression during login; the negotiated Zstd pipeline replaces it later.
    public static final int LAN_THRESHOLD = VANILLA_THRESHOLD;

    private LanCompressionHooks() {
    }

    public static int resolveLanCompressionThreshold(MinecraftServer server) {
        return server != null && !server.isDedicatedServer() && server.isPublished()
            ? LAN_THRESHOLD : VANILLA_THRESHOLD;
    }
}
