package cn.tohsaka.factory.zstdnet.core.transport;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InitialSyncInjectionTest {
    @Test
    void coremodInjectsOnceBeforeTheFirstPlaySendAndPreservesTheOperandStack() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ClassNode target = new ClassNode();
        try (InputStream input = loader.getResourceAsStream("net/minecraft/server/players/PlayerList.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(target, 0);
        }
        String script;
        try (InputStream input = loader.getResourceAsStream("coremods/zstdnet_initial_sync.js")) {
            assertNotNull(input);
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // JUnit uses official mapped classes and has no running ModLauncher naming service.
        script = script.replaceFirst("var ASMAPI = Java.type\\('[^']+'\\);",
            "var ASMAPI = { mapMethod: function(name) { return name; }, log: function(level, text) {} };");
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        assertNotNull(engine);
        engine.put("targetClass", target);
        engine.eval(script);
        engine.eval("initializeCoreMod().zstdnet_initial_sync.transformer(targetClass)");

        MethodNode method = target.methods.stream().filter(node -> node.name.equals("placeNewPlayer")).findFirst().orElseThrow();
        int hooks = 0;
        boolean sent = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("cn/tohsaka/factory/zstdnet/core/transport/InitialSyncHooks")) {
                    assertEquals(false, sent, "Negotiation must begin before any initial PLAY send");
                    hooks++;
                }
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.startsWith("net/minecraft/server/network/Server")
                    && call.desc.equals("(Lnet/minecraft/network/protocol/Packet;)V")) {
                    sent = true;
                }
            }
        }
        assertEquals(1, hooks);
        new Analyzer<>(new BasicVerifier()).analyze(target.name, method);
    }
}
