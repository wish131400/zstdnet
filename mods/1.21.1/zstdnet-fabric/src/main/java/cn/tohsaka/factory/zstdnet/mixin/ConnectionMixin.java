package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.SocketAddress;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"), cancellable = true)
    private void zstdnet$handleTransportControl(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (LanCompressionSync.handleTransportPacket((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void zstdnet$installProxyProtocol(ChannelHandlerContext context, CallbackInfo ci) {
        ServerRealIpHooks.installProxyProtocol((Connection) (Object) this, context);
    }

    @Inject(method = "getRemoteAddress", at = @At("RETURN"), cancellable = true)
    private void zstdnet$getForwardedRemoteAddress(CallbackInfoReturnable<SocketAddress> cir) {
        cir.setReturnValue(ServerRealIpHooks.getRemoteAddress((Connection) (Object) this, cir.getReturnValue()));
    }
}
