package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void zstdnet$applyForwardedAddress(ClientIntentionPacket packet, CallbackInfo ci) {
        ServerRealIpHooks.applyForwardedAddress(this.connection, packet);
    }
}
