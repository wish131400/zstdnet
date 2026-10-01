package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.core.transport.InitialSyncHooks;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 0))
    private void zstdnet$beforeInitialSync(Connection connection, ServerPlayer player, CallbackInfo ci) {
        InitialSyncHooks.beforeInitialSync(connection, player);
    }
}
