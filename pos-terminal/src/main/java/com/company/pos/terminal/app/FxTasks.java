package com.company.pos.terminal.app;

import javafx.concurrent.Task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Helper for running a synchronous view-model call off the JavaFX Application
 * Thread. The {@code work} runs on a background daemon thread inside a
 * {@link Task}; the {@code onDone}/{@code onError} callbacks are dispatched by
 * the {@link Task}'s success/failure handlers, which JavaFX always invokes back
 * on the FX Application Thread. This keeps network I/O off the UI thread while
 * letting callbacks touch scene-graph nodes safely.
 *
 * <p>All tasks share a single cached-thread-pool executor so threads are reused
 * across calls and each thread carries a unique name (e.g. {@code api-call-1})
 * that is visible in thread dumps. All threads are daemon threads so the JVM
 * can exit cleanly without waiting for in-flight API calls.
 */
public final class FxTasks {
    private FxTasks() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "api-call-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public static void run(Runnable work, Runnable onDone, Consumer<Throwable> onError) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                work.run();
                return null;
            }
        };
        // setOnSucceeded / setOnFailed handlers are invoked on the FX thread.
        task.setOnSucceeded(e -> onDone.run());
        task.setOnFailed(e -> onError.accept(task.getException()));
        EXEC.execute(task);
    }
}
