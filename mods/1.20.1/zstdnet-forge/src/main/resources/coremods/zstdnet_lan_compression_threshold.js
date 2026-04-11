var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_lan_compression_threshold': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.MinecraftServer'
            },
            'transformer': function(classNode) {
                var targetMethod = ASMAPI.mapMethod('m_6328_');

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name != targetMethod || method.desc != '()I') {
                        continue;
                    }

                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/LanCompressionHooks',
                        'resolveLanCompressionThreshold',
                        '(Lnet/minecraft/server/MinecraftServer;)I',
                        false
                    ));
                    method.instructions.add(new InsnNode(Opcodes.IRETURN));
                    method.maxStack = 1;
                    method.maxLocals = 1;
                    ASMAPI.log('INFO', '[zstdnet] patched MinecraftServer#getCompressionThreshold for LAN mode.');
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch MinecraftServer#getCompressionThreshold.');
                return classNode;
            }
        },
        'zstdnet_dedicated_auto_port': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.dedicated.DedicatedServer'
            },
            'transformer': function(classNode) {
                var mappedInitServer = ASMAPI.mapMethod('m_7038_');
                var mappedGetProperties = ASMAPI.mapMethod('m_139777_');

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if ((method.name != mappedInitServer && method.name != 'initServer' && method.name != 'e') || method.desc != '()Z') {
                        continue;
                    }

                    for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                            continue;
                        }
                        if (insn.name != mappedGetProperties && insn.name != 'getProperties' && insn.name != 'a') {
                            continue;
                        }

                        var next = insn.getNext();
                        while (next != null && next.getOpcode() == -1) {
                            next = next.getNext();
                        }
                        if (next == null || next.getOpcode() != Opcodes.ASTORE) {
                            continue;
                        }

                        var injected = new InsnList();
                        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        injected.add(new InsnNode(Opcodes.SWAP));
                        injected.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/server/DedicatedServerAutoPort',
                            'prepareDedicatedServerProperties',
                            '(Lnet/minecraft/server/dedicated/DedicatedServer;Lnet/minecraft/server/dedicated/DedicatedServerProperties;)Lnet/minecraft/server/dedicated/DedicatedServerProperties;',
                            false
                        ));
                        method.instructions.insert(insn, injected);
                        ASMAPI.log('INFO', '[zstdnet] patched DedicatedServer#initServer for auto port takeover.');
                        return classNode;
                    }
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch DedicatedServer#initServer for auto port takeover.');
                return classNode;
            }
        }
    };
}
