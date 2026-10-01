var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_initial_sync': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.server.players.PlayerList' },
            'transformer': function(classNode) {
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.name != 'placeNewPlayer'
                        || method.desc != '(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V') {
                        continue;
                    }
                    for (var node = method.instructions.getFirst(); node != null; node = node.getNext()) {
                        if (node instanceof MethodInsnNode && node.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && (node.owner == 'net/minecraft/server/network/ServerGamePacketListenerImpl'
                                || node.owner == 'net/minecraft/server/network/ServerCommonPacketListenerImpl')
                            && node.desc == '(Lnet/minecraft/network/protocol/Packet;)V') {
                            var injected = new InsnList();
                            injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
                            injected.add(new VarInsnNode(Opcodes.ALOAD, 2));
                            injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                'cn/tohsaka/factory/zstdnet/core/transport/InitialSyncHooks', 'beforeInitialSync',
                                '(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V', false));
                            method.instructions.insertBefore(node, injected);
                            method.maxStack += 2;
                            ASMAPI.log('INFO', '[zstdnet] patched PlayerList initial synchronization.');
                            return classNode;
                        }
                    }
                }
                ASMAPI.log('ERROR', '[zstdnet] failed to patch PlayerList initial synchronization.');
                return classNode;
            }
        }
    };
}
