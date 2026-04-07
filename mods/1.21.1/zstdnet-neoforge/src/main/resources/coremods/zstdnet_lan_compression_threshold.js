var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
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
                var mappedMethod = ASMAPI.mapMethod('getCompressionThreshold');

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if ((method.name != mappedMethod && method.name != 'getCompressionThreshold') || method.desc != '()I') {
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
        }
    };
}
