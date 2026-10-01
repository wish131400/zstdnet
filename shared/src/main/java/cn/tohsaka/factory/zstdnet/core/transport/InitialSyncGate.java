package cn.tohsaka.factory.zstdnet.core.transport;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

final class InitialSyncGate extends ChannelDuplexHandler {
    static final int MAX_PACKETS = 4096;
    private static final Logger LOGGER = LoggerFactory.getLogger(InitialSyncGate.class);
    private final Packet<?> prepare;
    private final Queue<PendingWrite> pending = new ArrayDeque<>();
    private final long startedAt = System.nanoTime();
    private Packet<?> activation;
    private ChannelHandlerContext context;
    private ScheduledFuture<?> timeout;
    private boolean finished;

    InitialSyncGate(Packet<?> prepare) {
        this.prepare = prepare;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
        timeout = context.executor().schedule(() -> release("timeout"), 5, TimeUnit.SECONDS);
    }

    void allowActivation(Packet<?> activation) {
        this.activation = activation;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (finished || !(message instanceof Packet<?>) || message == prepare || message == activation) {
            context.write(message, promise);
            return;
        }
        pending.add(new PendingWrite(message, promise));
        if (pending.size() >= MAX_PACKETS) {
            release("packet_limit");
        }
    }

    void release(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        cancelTimeout();
        int count = pending.size();
        context.pipeline().remove(this);
        // Resume from this outbound position so upstream packet filters are not applied twice.
        PendingWrite write;
        while ((write = pending.poll()) != null) {
            context.write(write.message(), write.promise());
        }
        context.flush();
        LOGGER.info("[zstdnet] initial sync released on {}: packets={}, reason={}, wait_ms={}.",
            context.channel().id(), count, reason, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        failPending();
        context.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        failPending();
    }

    private void failPending() {
        if (finished) {
            return;
        }
        finished = true;
        cancelTimeout();
        ClosedChannelException error = new ClosedChannelException();
        PendingWrite write;
        while ((write = pending.poll()) != null) {
            ReferenceCountUtil.release(write.message());
            write.promise().tryFailure(error);
        }
    }

    private void cancelTimeout() {
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }

    private record PendingWrite(Object message, ChannelPromise promise) {
    }
}
