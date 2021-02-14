package de.kcodeyt.heads.util;

import cn.nukkit.Server;
import cn.nukkit.scheduler.ServerScheduler;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ScheduledFuture<V> {

    private static final int WAITING = 0;
    private static final int COMPLETED = 1;

    public static <T> ScheduledFuture<T> supplyAsync(Supplier<T> supplier) {
        return new ScheduledFuture<>(supplier, false);
    }

    public static <T> ScheduledFuture<T> completed(T value) {
        return new ScheduledFuture<T>(null, true).apply(value, null);
    }

    private final Queue<BiConsumer<V, Exception>> syncQueue;
    private final Queue<BiConsumer<V, Exception>> asyncQueue;

    private final ServerScheduler scheduler;
    private final Runnable task;

    private volatile V value;
    private volatile Exception exception;
    private volatile int state;

    private ScheduledFuture(Supplier<V> supplier, boolean canBeNull) {
        this.syncQueue = new ConcurrentLinkedDeque<>();
        this.asyncQueue = new ConcurrentLinkedDeque<>();

        this.scheduler = Server.getInstance().getScheduler();
        if(supplier != null) {
            this.scheduler.scheduleTask(null, this.task = () -> {
                V value = null;
                Exception exception = null;
                try {
                    value = supplier.get();
                } catch(Exception e) {
                    exception = e;
                }

                this.apply(value, exception);
            }, true);
        } else {
            if(!canBeNull)
                throw new NullPointerException("Supplier cant be null!");
            this.task = null;
        }

        this.state = WAITING;
    }

    private ScheduledFuture<V> apply(V value, Exception e) {
        if(this.state == WAITING || this.task == null) {
            this.state = COMPLETED;
            this.value = value;
            this.exception = e;
            if(Server.getInstance().isPrimaryThread()) {
                this.runSyncQueue(value, e);
                this.scheduler.scheduleTask(null, () -> this.runAsyncQueue(value, e), true);
            } else {
                this.runAsyncQueue(value, e);
                this.scheduler.scheduleTask(null, () -> this.runSyncQueue(value, e));
            }
        } else {
            this.state = WAITING;
            this.scheduler.scheduleTask(null, this.task, true);
        }
        return this;
    }

    public ScheduledFuture<V> whenComplete(BiConsumer<V, Exception> consumer) {
        if(this.state != COMPLETED)
            this.syncQueue.add(consumer);
        else
            this.scheduler.scheduleTask(null, () -> consumer.accept(this.value, this.exception));
        return this;
    }

    public ScheduledFuture<V> whenCompleteAsync(BiConsumer<V, Exception> consumer) {
        if(this.state != COMPLETED)
            this.asyncQueue.add(consumer);
        else
            this.scheduler.scheduleTask(null, () -> consumer.accept(this.value, this.exception), true);
        return this;
    }

    public <R> ScheduledFuture<R> thenApply(Function<V, R> function) {
        final ScheduledFuture<R> future = new ScheduledFuture<>(null, true);
        this.whenCompleteAsync((v, e0) -> {
            R r = null;
            Exception e1 = e0;
            if(v != null) {
                try {
                    r = function.apply(v);
                } catch(Exception e2) {
                    e1 = e2;
                }
            }
            future.apply(r, e1);
        });
        return future;
    }

    public V join() throws Exception {
        //noinspection StatementWithEmptyBody
        while(this.state != COMPLETED) ;
        if(this.exception != null)
            throw this.exception;
        return this.value;
    }

    private void runSyncQueue(V value, Exception exception) {
        while(!this.syncQueue.isEmpty())
            this.syncQueue.poll().accept(value, exception);
    }

    private void runAsyncQueue(V value, Exception exception) {
        while(!this.asyncQueue.isEmpty())
            this.asyncQueue.poll().accept(value, exception);
    }

}
