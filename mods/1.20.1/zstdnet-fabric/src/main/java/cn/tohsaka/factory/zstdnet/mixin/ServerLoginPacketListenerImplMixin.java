package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void zstdnet$rejectDirectBackendLogin(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (ServerProxyBootstrap.rejectDirectBackendLogin(this.connection)) {
            ci.cancel();
        }
    }
}
