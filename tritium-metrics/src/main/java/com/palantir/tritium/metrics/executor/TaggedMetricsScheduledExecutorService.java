/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.tritium.metrics.executor;

import static com.palantir.logsafe.Preconditions.checkNotNull;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.palantir.tritium.metrics.registry.MetricName;
import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TaggedMetricsScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    private final Meter submitted;
    private final Counter running;
    private final Meter completed;
    private final Timer duration;

    private final Meter scheduledOnce;
    private final Meter scheduledRepetitively;
    private final Counter scheduledOverrun;
    private final Histogram scheduledPercentOfPeriod;

    public static TaggedMetricsScheduledExecutorService create(
            ScheduledExecutorService delegate,
            TaggedMetricRegistry registry,
            String name) {
        checkNotNull(registry, "delegate");
        checkNotNull(registry, "registry");
        checkNotNull(name, "name");
        return new TaggedMetricsScheduledExecutorService(delegate, registry, name);
    }

    private TaggedMetricsScheduledExecutorService(
            ScheduledExecutorService delegate,
            TaggedMetricRegistry registry,
            String name) {
        this.delegate = delegate;

        this.submitted = registry.meter(createMetricName("submitted", name));
        this.running = registry.counter(createMetricName("running", name));
        this.completed = registry.meter(createMetricName("completed", name));
        this.duration = registry.timer(createMetricName("duration", name));

        this.scheduledOnce = registry.meter(createMetricName("scheduled.once", name));
        this.scheduledRepetitively = registry.meter(createMetricName("scheduled.repetitively", name));
        this.scheduledOverrun = registry.counter(createMetricName("scheduled.overrun", name));
        this.scheduledPercentOfPeriod = registry.histogram(createMetricName("scheduled.percent-of-period", name));
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        scheduledOnce.mark();
        return delegate.schedule(new TaggedMetricsRunnable(task), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        scheduledOnce.mark();
        return delegate.schedule(new TaggedMetricsCallable<>(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        scheduledRepetitively.mark();
        return delegate.scheduleAtFixedRate(
                new TaggedMetricsScheduledRunnable(task, period, unit), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        scheduledRepetitively.mark();
        return delegate.scheduleWithFixedDelay(new TaggedMetricsRunnable(task), initialDelay, delay, unit);
    }

    @Override
    public void execute(Runnable task) {
        submitted.mark();
        delegate.execute(new TaggedMetricsRunnable(task));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        submitted.mark();
        return delegate.submit(new TaggedMetricsCallable<>(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        submitted.mark();
        return delegate.submit(new TaggedMetricsRunnable(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        submitted.mark();
        return delegate.submit(new TaggedMetricsRunnable(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        submitted.mark(tasks.size());
        Collection<TaggedMetricsCallable<T>> instrumented = instrument(tasks);
        return delegate.invokeAll(instrumented);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        submitted.mark(tasks.size());
        Collection<TaggedMetricsCallable<T>> instrumented = instrument(tasks);
        return delegate.invokeAll(instrumented, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        submitted.mark(tasks.size());
        Collection<TaggedMetricsCallable<T>> instrumented = instrument(tasks);
        return delegate.invokeAny(instrumented);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        submitted.mark(tasks.size());
        Collection<TaggedMetricsCallable<T>> instrumented = instrument(tasks);
        return delegate.invokeAny(instrumented, timeout, unit);
    }

    private <T> Collection<TaggedMetricsCallable<T>> instrument(Collection<? extends Callable<T>> tasks) {
        List<TaggedMetricsCallable<T>> instrumented = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            instrumented.add(new TaggedMetricsCallable<>(task));
        }
        return instrumented;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    private class TaggedMetricsRunnable implements Runnable {

        private final Runnable task;

        TaggedMetricsRunnable(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            running.inc();
            try (Timer.Context ignored = duration.time()) {
                task.run();
            } finally {
                running.dec();
                completed.mark();
            }
        }
    }

    private class TaggedMetricsScheduledRunnable implements Runnable {

        private final Runnable task;
        private final long periodInNanos;

        TaggedMetricsScheduledRunnable(Runnable task, long period, TimeUnit unit) {
            this.task = task;
            this.periodInNanos = unit.toNanos(period);
        }

        @Override
        public void run() {
            running.inc();
            Timer.Context context = duration.time();
            try {
                task.run();
            } finally {
                long elapsed = context.stop();
                running.dec();
                completed.mark();
                if (elapsed > periodInNanos) {
                    scheduledOverrun.inc();
                }
                scheduledPercentOfPeriod.update((100L * elapsed) / periodInNanos);
            }
        }
    }

    private class TaggedMetricsCallable<T> implements Callable<T> {

        private final Callable<T> task;

        TaggedMetricsCallable(Callable<T> task) {
            this.task = task;
        }

        @Override
        public T call() throws Exception {
            running.inc();
            try (Timer.Context ignored = duration.time()) {
                return task.call();
            } finally {
                running.dec();
                completed.mark();
            }
        }
    }

    private static MetricName createMetricName(String metricName, String name) {
        return MetricName.builder()
                .safeName(MetricRegistry.name("executor", metricName))
                .putSafeTags("name", name)
                .build();
    }
}
