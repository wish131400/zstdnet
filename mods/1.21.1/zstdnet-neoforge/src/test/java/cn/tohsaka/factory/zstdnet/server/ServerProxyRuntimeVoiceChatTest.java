package cn.tohsaka.factory.zstdnet.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProxyRuntimeVoiceChatTest {

    @Test
    void samePortVoiceChatReusesGameUdpRoute() {
        ServerProxyRuntime.VoiceChatPassthroughDecision decision = ServerProxyRuntime.resolveVoiceChatPassthrough(
            new ServerProxyRuntime.HostPort("0.0.0.0", 25565),
            new ServerProxyRuntime.HostPort("127.0.0.1", 25566),
            true,
            "",
            "",
            -1,
            "config/voicechat/voicechat-server.properties"
        );

        assertTrue(decision.reuseGameRoute());
        assertNull(decision.route());
        assertEquals("voice chat UDP reuses the built-in game UDP route", decision.reason());
    }

    @Test
    void separateVoiceChatPortWithoutListenIsDisabled() {
        ServerProxyRuntime.VoiceChatPassthroughDecision decision = ServerProxyRuntime.resolveVoiceChatPassthrough(
            new ServerProxyRuntime.HostPort("0.0.0.0", 25565),
            new ServerProxyRuntime.HostPort("127.0.0.1", 25566),
            true,
            "",
            "",
            24454,
            "config/voicechat/voicechat-server.properties"
        );

        assertFalse(decision.reuseGameRoute());
        assertNull(decision.route());
        assertEquals("Simple Voice Chat uses a separate port, so voice_chat_listen must be set explicitly", decision.reason());
    }

    @Test
    void explicitVoiceChatListenWithoutSvcConfigIsDisabled() {
        ServerProxyRuntime.VoiceChatPassthroughDecision decision = ServerProxyRuntime.resolveVoiceChatPassthrough(
            new ServerProxyRuntime.HostPort("0.0.0.0", 25565),
            new ServerProxyRuntime.HostPort("127.0.0.1", 25566),
            true,
            "0.0.0.0:30000",
            "",
            null,
            "missing"
        );

        assertNull(decision.route());
        assertEquals("voice_chat_target is blank and Simple Voice Chat config was not found at missing", decision.reason());
    }

    @Test
    void explicitVoiceChatListenAndTargetOverrideAutoResolution() {
        ServerProxyRuntime.VoiceChatPassthroughDecision decision = ServerProxyRuntime.resolveVoiceChatPassthrough(
            new ServerProxyRuntime.HostPort("0.0.0.0", 25565),
            new ServerProxyRuntime.HostPort("127.0.0.1", 25566),
            true,
            "0.0.0.0:30000",
            "127.0.0.1:24456",
            24454,
            "config/voicechat/voicechat-server.properties"
        );

        assertNotNull(decision.route());
        assertEquals(new ServerProxyRuntime.HostPort("0.0.0.0", 30000), decision.route().listen());
        assertEquals(new ServerProxyRuntime.HostPort("127.0.0.1", 24456), decision.route().target());
    }

    @Test
    void localVoiceChatPortCollisionIsRemapped() {
        ServerProxyRuntime.VoiceChatPassthroughDecision decision = ServerProxyRuntime.resolveVoiceChatPassthrough(
            new ServerProxyRuntime.HostPort("0.0.0.0", 25565),
            new ServerProxyRuntime.HostPort("127.0.0.1", 25566),
            true,
            "0.0.0.0:24454",
            "127.0.0.1:24454",
            24454,
            "config/voicechat/voicechat-server.properties"
        );

        assertNotNull(decision.route());
        assertEquals(new ServerProxyRuntime.HostPort("127.0.0.1", 24454), decision.route().target());
        assertNotEquals(24454, decision.route().listen().port());
    }

    @Test
    void lanVoiceDefaultsOnlyApplyWhenConfigIsBlank() throws Exception {
        assertTrue(invokeLanVoiceDefaultCheck("isDefaultVoiceChatListen", ""));
        assertTrue(invokeLanVoiceDefaultCheck("isDefaultVoiceChatTarget", ""));
        assertFalse(invokeLanVoiceDefaultCheck("isDefaultVoiceChatListen", "0.0.0.0:24455"));
        assertFalse(invokeLanVoiceDefaultCheck("isDefaultVoiceChatTarget", "127.0.0.1:24454"));
    }

    private static boolean invokeLanVoiceDefaultCheck(String methodName, String value) throws Exception {
        Class<?> proxyConfigClass = Class.forName(ServerProxyRuntime.class.getName() + "$ProxyConfig");
        Method method = proxyConfigClass.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, value);
    }
}
