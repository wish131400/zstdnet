var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var JumpInsnNode = Java.type('org.objectweb.asm.tree.JumpInsnNode');
var LabelNode = Java.type('org.objectweb.asm.tree.LabelNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_lan_chat_filter': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.client.gui.components.ChatComponent'
            },
            'transformer': function(classNode) {
                var exactDesc = '(Lnet/minecraft/network/chat/Component;)V';
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var method = classNode.methods.get(i);
                    if (method.desc != exactDesc) {
                        continue;
                    }

                    var continueLabel = new LabelNode();
                    var injected = new InsnList();
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/LanChatMessageHooks',
                        'shouldSuppress',
                        '(Lnet/minecraft/network/chat/Component;)Z',
                        false
                    ));
                    injected.add(new JumpInsnNode(Opcodes.IFEQ, continueLabel));
                    injected.add(new InsnNode(Opcodes.RETURN));
                    injected.add(continueLabel);
                    method.instructions.insert(injected);
                    ASMAPI.log('INFO', '[zstdnet] patched ChatComponent#addMessage for LAN status filtering.');
                    return classNode;
                }
                ASMAPI.log('WARN', '[zstdnet] failed to patch ChatComponent#addMessage for LAN status filtering.');
                return classNode;
            }
        }
    };
}
