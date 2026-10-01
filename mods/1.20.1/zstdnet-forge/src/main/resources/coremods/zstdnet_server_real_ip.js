var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_connection_proxy_protocol': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.network.Connection'
            },
            'transformer': function(classNode) {
                var addressField = null;
                for (var i = 0; i < classNode.fields.size(); i++) {
                    if (classNode.fields.get(i).desc == 'Ljava/net/SocketAddress;') {
                        addressField = classNode.fields.get(i).name;
                        break;
                    }
                }
                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.name == 'channelActive'
                            && method.desc == '(Lio/netty/channel/ChannelHandlerContext;)V') {
                        var injected = new InsnList();
                        injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        injected.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                            'installProxyProtocol',
                            '(Lnet/minecraft/network/Connection;Lio/netty/channel/ChannelHandlerContext;)V',
                            false
                        ));
                        method.instructions.insert(injected);
                    }
                    if (addressField != null && method.desc == '()Ljava/net/SocketAddress;') {
                        method.instructions.clear();
                        method.tryCatchBlocks.clear();
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        method.instructions.add(new FieldInsnNode(
                            Opcodes.GETFIELD, 'net/minecraft/network/Connection',
                            addressField, 'Ljava/net/SocketAddress;'
                        ));
                        method.instructions.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                            'getRemoteAddress',
                            '(Lnet/minecraft/network/Connection;Ljava/net/SocketAddress;)Ljava/net/SocketAddress;',
                            false
                        ));
                        method.instructions.add(new InsnNode(Opcodes.ARETURN));
                        method.maxStack = 2;
                        method.maxLocals = 1;
                    }
                }
                return classNode;
            }
        }
    };
}
