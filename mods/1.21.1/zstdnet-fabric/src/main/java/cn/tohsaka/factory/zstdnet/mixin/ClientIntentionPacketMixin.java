package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientIntentionPacket.class)
public abstract class ClientIntentionPacketMixin {
    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readUtf(I)Ljava/lang/String;")
    )
    private static String zstdnet$rememberRawHandshakeHost(FriendlyByteBuf buffer, int maxLength) {
        return ServerRealIpHooks.rememberRawHandshakeHostString(buffer.readUtf(maxLength));
    }
}
