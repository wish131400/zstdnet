package cn.tohsaka.factory.zstdnet.network;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanCompressionSyncTest {
    @Test
    void prepareAndActivateUseTheReceivingConnectionWithoutTheClientMainThread() throws Exception {
        // Plain JUnit runs without Forge's event-class transformer.
        Method initializeEvent = EventListenerHelper.class.getDeclaredMethod("getListenerListInternal", Class.class, boolean.class);
        initializeEvent.setAccessible(true);
        initializeEvent.invoke(null, NetworkEvent.class, true);
        initializeEvent.invoke(null, NetworkEvent.GatherLoginPayloadsEvent.class, true);
        LanCompressionSync.init();
        ClientConfig.SPEC.setConfig(CommentedConfig.inMemory());
        EmbeddedChannel receiving = channel();
        EmbeddedChannel other = channel();
        try {
            Connection connection = connection(receiving);
            NetworkEvent.Context context = context(connection);
            receive("INTEGRATED_CHANNEL", "IntegratedPrepareMessage", 1, context);
            assertTrue(context.getPacketHandled());
            receiving.runPendingTasks();
            assertNotNull(receiving.pipeline().get("zstdnet_decode"));
            ServerboundCustomPayloadPacket ready = receiving.readOutbound();
            assertNotNull(ready);
            assertEquals("zstdnet:integrated_transport", ready.getIdentifier().toString());
            ready.getData().release();

            receive("CHANNEL", "ActivateMessage", -1, context);
            assertNotNull(receiving.pipeline().get("zstdnet_encode"));
            assertNull(receiving.pipeline().get("compress"));
            assertNull(other.pipeline().get("zstdnet_decode"));
            assertNull(other.pipeline().get("zstdnet_encode"));
            assertNull(other.readOutbound());
        } finally {
            receiving.finishAndReleaseAll();
            other.finishAndReleaseAll();
        }
    }

    private static void receive(String channelName, String name, int value, NetworkEvent.Context context) throws Exception {
        Class<?> type = Class.forName(LanCompressionSync.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        Field channelField = LanCompressionSync.class.getDeclaredField(channelName);
        channelField.setAccessible(true);
        SimpleChannel channel = (SimpleChannel) channelField.get(null);
        Field codecField = SimpleChannel.class.getDeclaredField("indexedCodec");
        codecField.setAccessible(true);
        Object codec = codecField.get(channel);
        Method consume = codec.getClass().getDeclaredMethod("consume", FriendlyByteBuf.class, int.class, Supplier.class);
        consume.setAccessible(true);
        FriendlyByteBuf payload = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            channel.encodeMessage(constructor.newInstance(value), payload);
            consume.invoke(codec, payload, Integer.MIN_VALUE, (Supplier<NetworkEvent.Context>) () -> context);
        } finally {
            payload.release();
        }
    }

    private static NetworkEvent.Context context(Connection connection) throws Exception {
        Constructor<NetworkEvent.Context> constructor = NetworkEvent.Context.class.getDeclaredConstructor(
            Connection.class, NetworkDirection.class, NetworkEvent.PacketDispatcher.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection, NetworkDirection.PLAY_TO_CLIENT, null);
    }

    private static EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new Varint21FrameDecoder());
        channel.pipeline().addLast("prepender", new Varint21LengthFieldPrepender());
        channel.pipeline().addLast("compress", new CompressionEncoder(256));
        return channel;
    }

    private static Connection connection(Channel channel) throws Exception {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        for (Field field : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(connection, channel);
                return connection;
            }
        }
        throw new AssertionError("Connection has no channel field");
    }
}
