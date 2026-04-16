var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_connect_screen_intercept': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.client.gui.screens.ConnectScreen'
            },
            'transformer': function(classNode) {
                var exactDesc = '(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V';

                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if ((method.access & Opcodes.ACC_STATIC) == 0) {
                        continue;
                    }
                    if (method.desc != exactDesc) {
                        continue;
                    }

                    var injected = new InsnList();
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ConnectScreenHooks',
                        'interceptConnect',
                        '(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;',
                        false
                    ));
                    injected.add(new VarInsnNode(Opcodes.ASTORE, 2));

                    method.instructions.insert(injected);
                    ASMAPI.log('INFO', '[zstdnet] patched ConnectScreen#startConnecting for programmatic connection interception.');
                    return classNode;
                }

                ASMAPI.log('WARN', '[zstdnet] failed to patch ConnectScreen#startConnecting - ServerRedirect compatibility may not work.');
                return classNode;
            }
        }
    };
}
