package cn.tohsaka.factory.zstdnet.mixin;

import cn.tohsaka.factory.zstdnet.coremod.ConnectScreenHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin extends Screen {
    protected ConnectScreenMixin(Component title) {
        super(title);
    }

    @ModifyVariable(
        method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
        at = @At("HEAD"),
        ordinal = 0,
        argsOnly = true
    )
    private static ServerAddress zstdnet$interceptConnect(
        ServerAddress original,
        Screen parent,
        Minecraft minecraft,
        ServerAddress serverAddress,
        ServerData serverData,
        boolean quickPlay,
        TransferState transferState
    ) {
        return ConnectScreenHooks.interceptConnect(original, serverData);
    }
}
