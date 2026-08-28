/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 */

package cn.tohsaka.factory.zstdnet.coremod;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Filters the vanilla/MCWiFiPnP status lines emitted while ZstdNet starts a LAN world. */
public final class LanChatMessageHooks {
    private static final long SUPPRESSION_WINDOW_MILLIS = 30_000L;
    private static volatile long suppressUntilMillis;

    private LanChatMessageHooks() {
    }

    public static void beginLanPublishMessageSuppression() {
        suppressUntilMillis = System.currentTimeMillis() + SUPPRESSION_WINDOW_MILLIS;
    }

    public static boolean shouldSuppress(Component message) {
        if (message == null || System.currentTimeMillis() > suppressUntilMillis) {
            return false;
        }
        return containsSuppressedTranslation(message);
    }

    private static boolean containsSuppressedTranslation(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable
            && isSuppressedKey(translatable.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsSuppressedTranslation(sibling)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuppressedKey(String key) {
        return "commands.publish.started".equals(key)
            || "commands.publish.failed".equals(key)
            || key.startsWith("mcwifipnp.upnp.");
    }
}
