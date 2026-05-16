var ASMAPI = Java.type('net.neoforged.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');

function initializeCoreMod() {
    return {
        'zstdnet_client_intention_raw_host': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.network.protocol.handshake.ClientIntentionPacket'
            },
            'transformer': function(classNode) {
                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.desc != '(Lnet/minecraft/network/FriendlyByteBuf;)V') {
                        continue;
                    }

                    var patched = false;
                    for (var k = 0; k < method.instructions.size(); k++) {
                        var insn = method.instructions.get(k);
                        if (insn.getOpcode && insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                                && insn.owner == 'net/minecraft/network/FriendlyByteBuf'
                                && (insn.name == 'm_130136_' || insn.name == 'readUtf')
                                && insn.desc == '(I)Ljava/lang/String;') {
                            var injected = new InsnList();
                            injected.add(new InsnNode(Opcodes.DUP));
                            injected.add(new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                                'rememberRawHandshakeHostString',
                                '(Ljava/lang/String;)Ljava/lang/String;',
                                false
                            ));
                            injected.add(new InsnNode(Opcodes.POP));
                            method.instructions.insert(insn, injected);
                            ASMAPI.log('INFO', '[zstdnet] patched ClientIntentionPacket#readUtf for raw forwarded real IP host.');
                            patched = true;
                            break;
                        }
                    }

                    if (!patched) {
                        ASMAPI.log('ERROR', '[zstdnet] failed to patch ClientIntentionPacket - readUtf call not found.');
                    }
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch ClientIntentionPacket constructor.');
                return classNode;
            }
        },
        'zstdnet_server_handshake_real_ip': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.server.network.ServerHandshakePacketListenerImpl'
            },
            'transformer': function(classNode) {
                var connectionField = null;
                for (var i = 0; i < classNode.fields.size(); i++) {
                    var field = classNode.fields.get(i);
                    if (field.desc == 'Lnet/minecraft/network/Connection;') {
                        connectionField = field.name;
                        break;
                    }
                }

                if (connectionField == null) {
                    ASMAPI.log('ERROR', '[zstdnet] failed to patch ServerHandshakePacketListenerImpl - Connection field not found.');
                    return classNode;
                }

                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.desc != '(Lnet/minecraft/network/protocol/handshake/ClientIntentionPacket;Z)V') {
                        continue;
                    }

                    var injected = new InsnList();
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    injected.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        'net/minecraft/server/network/ServerHandshakePacketListenerImpl',
                        connectionField,
                        'Lnet/minecraft/network/Connection;'
                    ));
                    injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    injected.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        'cn/tohsaka/factory/zstdnet/coremod/ServerRealIpHooks',
                        'applyForwardedAddress',
                        '(Lnet/minecraft/network/Connection;Lnet/minecraft/network/protocol/handshake/ClientIntentionPacket;)V',
                        false
                    ));
                    method.instructions.insert(injected);
                    ASMAPI.log('INFO', '[zstdnet] patched ServerHandshakePacketListenerImpl for forwarded real IP.');
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch ServerHandshakePacketListenerImpl#beginLogin.');
                return classNode;
            }
        },
        'zstdnet_connection_real_ip': {
            'target': {
                'type': 'CLASS',
                'name': 'net.minecraft.network.Connection'
            },
            'transformer': function(classNode) {
                var addressField = null;
                for (var i = 0; i < classNode.fields.size(); i++) {
                    var field = classNode.fields.get(i);
                    if (field.desc == 'Ljava/net/SocketAddress;') {
                        addressField = field.name;
                        break;
                    }
                }

                if (addressField == null) {
                    ASMAPI.log('ERROR', '[zstdnet] failed to patch Connection - SocketAddress field not found.');
                    return classNode;
                }

                for (var j = 0; j < classNode.methods.size(); j++) {
                    var method = classNode.methods.get(j);
                    if (method.desc != '()Ljava/net/SocketAddress;') {
                        continue;
                    }

                    method.instructions.clear();
                    method.tryCatchBlocks.clear();
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        'net/minecraft/network/Connection',
                        addressField,
                        'Ljava/net/SocketAddress;'
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
                    ASMAPI.log('INFO', '[zstdnet] patched Connection#getRemoteAddress for forwarded real IP.');
                    return classNode;
                }

                ASMAPI.log('ERROR', '[zstdnet] failed to patch Connection#getRemoteAddress.');
                return classNode;
            }
        }
    };
}
