package org.xbib.netty.http.server.reactive;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.EventExecutor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.CANCELLED;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.COMPLETE;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.INACTIVE;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.NO_CONTEXT;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.NO_SUBSCRIPTION;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.NO_SUBSCRIPTION_OR_CONTEXT;
import static org.xbib.netty.http.server.reactive.HandlerSubscriber.State.RUNNING;


/**
 * Subscriber that publishes received messages to the handler pipeline.
 */
public class HandlerSubscriber<T> extends ChannelDuplexHandler implements Subscriber<T> {

    private static final long DEFAULT_LOW_WATERMARK = 4;

    private static final long DEFAULT_HIGH_WATERMARK = 16;

    /**
     * Create a new handler subscriber.
     *
     * The supplied executor must be the same event loop as the event loop that this handler is eventually registered
     * with, if not, an exception will be thrown when the handler is registered.
     *
     * @param executor The executor to execute asynchronous events from the publisher on.
     * @param demandLowWatermark  The low watermark for demand. When demand drops below this, more will be requested.
     * @param demandHighWatermark The high watermark for demand. This is the maximum that will be requested.
     */
    public HandlerSubscriber(EventExecutor executor, long demandLowWatermark, long demandHighWatermark) {
        this.executor = executor;
        this.demandLowWatermark = demandLowWatermark;
        this.demandHighWatermark = demandHighWatermark;
    }

    /**
     * Create a new handler subscriber with the default low and high watermarks.
     *
     * The supplied executor must be the same event loop as the event loop that this handler is eventually registered
     * with, if not, an exception will be thrown when the handler is registered.
     *
     * @param executor The executor to execute asynchronous events from the publisher on.
     * @see #HandlerSubscriber(EventExecutor, long, long)
     */
    public HandlerSubscriber(EventExecutor executor) {
        this(executor, DEFAULT_LOW_WATERMARK, DEFAULT_HIGH_WATERMARK);
    }

    /**
     * Override for custom error handling. By default, it closes the channel.
     *
     * @param error The error to handle.
     */
    protected void error(Throwable error) {
        doClose();
    }

    /**
     * Override for custom completion handling. By default, it closes the channel.
     */
    protected void complete() {
        doClose();
    }

    private final EventExecutor executor;
    private final long demandLowWatermark;
    private final long demandHighWatermark;

    enum State {
        NO_SUBSCRIPTION_OR_CONTEXT,
        NO_SUBSCRIPTION,
        NO_CONTEXT,
        INACTIVE,
        RUNNING,
        CANCELLED,
        COMPLETE
    }

    private final AtomicBoolean hasSubscription = new AtomicBoolean();

    private volatile Subscription subscription;
    private volatile ChannelHandlerContext ctx;

    private State state = NO_SUBSCRIPTION_OR_CONTEXT;
    private long outstandingDemand = 0;
    private ChannelFuture lastWriteFuture;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        verifyRegisteredWithRightExecutor(ctx);

        switch (state) {
            case NO_SUBSCRIPTION_OR_CONTEXT:
                this.ctx = ctx;
                state = NO_SUBSCRIPTION;
                break;
            case NO_CONTEXT:
                this.ctx = ctx;
                maybeStart();
                break;
            case COMPLETE:
                state = COMPLETE;
                ctx.close();
                break;
            default:
                throw new IllegalStateException("This handler must only be added to a pipeline once " + state);
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        verifyRegisteredWithRightExecutor(ctx);
        ctx.fireChannelRegistered();
    }

    private void verifyRegisteredWithRightExecutor(ChannelHandlerContext ctx) {
        if (ctx.channel().isRegistered() && !executor.inEventLoop()) {
            throw new IllegalArgumentException("Channel handler MUST be registered with the same EventExecutor that it is created with.");
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        maybeRequestMore();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (state == INACTIVE) {
            state = RUNNING;
            maybeRequestMore();
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancel();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx)  {
        cancel();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancel();
        ctx.fireExceptionCaught(cause);
    }

    private void cancel() {
        switch (state) {
            case NO_SUBSCRIPTION:
                state = CANCELLED;
                break;
            case RUNNING:
            case INACTIVE:
                subscription.cancel();
                state = CANCELLED;
                break;
            default:
                break;
        }
    }

    @Override
    public void onSubscribe(final Subscription subscription) {
        if (subscription == null) {
            throw new NullPointerException("Null subscription");
        } else if (!hasSubscription.compareAndSet(false, true)) {
            subscription.cancel();
        } else {
            this.subscription = subscription;
            executor.execute(this::provideSubscription);
        }
    }

    private void provideSubscription() {
        switch (state) {
            case NO_SUBSCRIPTION_OR_CONTEXT:
                state = NO_CONTEXT;
                break;
            case NO_SUBSCRIPTION:
                maybeStart();
                break;
            case CANCELLED:
                subscription.cancel();
                break;
            default:
                break;
        }
    }

    private void maybeStart() {
        if (ctx.channel().isActive()) {
            state = RUNNING;
            maybeRequestMore();
        } else {
            state = INACTIVE;
        }
    }

    @Override
    public void onNext(T t) {
        lastWriteFuture = ctx.writeAndFlush(t);
        lastWriteFuture.addListener((ChannelFutureListener) future -> {
            outstandingDemand--;
            maybeRequestMore();
        });
    }

    @Override
    public void onError(final Throwable error) {
        if (error == null) {
            throw new NullPointerException("Null error published");
        }
        error(error);
    }

    @Override
    public void onComplete() {
        if (lastWriteFuture == null) {
            complete();
        } else {
            lastWriteFuture.addListener((ChannelFutureListener) channelFuture -> complete());
        }
    }

    private void doClose() {
        executor.execute(() -> {
            switch (state) {
                case NO_SUBSCRIPTION:
                case INACTIVE:
                case RUNNING:
                    ctx.close();
                    state = COMPLETE;
                    break;
                default:
                    break;

            }
        });
    }

    private void maybeRequestMore() {
        if (outstandingDemand <= demandLowWatermark && ctx.channel().isWritable()) {
            long toRequest = demandHighWatermark - outstandingDemand;
            outstandingDemand = demandHighWatermark;
            subscription.request(toRequest);
        }
    }
}
