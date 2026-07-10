package com.company.pos.terminal.app;

import javafx.concurrent.Task;

import java.util.function.Consumer;

/**
 * Helper for running a synchronous view-model call off the JavaFX Application
 * Thread. The {@code work} runs on a background daemon thread inside a
 * {@link Task}; the {@code onDone}/{@code onError} callbacks are dispatched by
 * the {@link Task}'s success/failure handlers, which JavaFX always invokes back
 * on the FX Application Thread. This keeps network I/O off the UI thread while
 * letting callbacks touch scene-graph nodes safely.
 */
public final class FxTasks {
    private FxTasks() {}

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
        Thread t = new Thread(task, "api-call");
        t.setDaemon(true);
        t.start();
    }
}
