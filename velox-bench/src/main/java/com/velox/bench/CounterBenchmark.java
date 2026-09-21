package com.velox.bench;

import com.velox.core.concurrent.StripedCounter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What is cache-line padding worth?
 *
 * <h2>The experiment</h2>
 *
 * Every thread increments the same logical counter as fast as it can. Four ways of
 * building that counter:
 *
 * <ul>
 *   <li>{@code ATOMIC_LONG} -- one shared cell. Every increment fights for the same
 *       cache line, so the line bounces between cores.</li>
 *   <li>{@code STRIPED_UNPADDED} -- many cells, but packed together. Threads use
 *       <i>different</i> cells, yet those cells share cache lines, so writes still
 *       invalidate each other: <b>false sharing</b>.</li>
 *   <li>{@code STRIPED_PADDED} -- many cells, each on its own pair of cache lines. This
 *       is {@link StripedCounter}'s default.</li>
 *   <li>{@code LONG_ADDER} -- the JDK's own striped counter, as a yardstick.</li>
 * </ul>
 *
 * <p>Both striped variants use 64 cells, far more than there are threads, so two threads
 * rarely land on the <i>same</i> cell. Any gap between them is therefore false sharing
 * and nothing else.
 *
 * <p>Run at 1, 2, 4, 8 and 16 threads with JMH's {@code -t}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class CounterBenchmark {

    @Param({"ATOMIC_LONG", "STRIPED_UNPADDED", "STRIPED_PADDED", "LONG_ADDER"})
    public String impl;

    interface Counter {
        void increment();

        long sum();
    }

    private Counter counter;

    @Setup(Level.Trial)
    public void setup() {
        counter = switch (impl) {
            case "ATOMIC_LONG" -> {
                var cell = new AtomicLong();
                yield new Counter() {
                    @Override
                    public void increment() {
                        cell.incrementAndGet();
                    }

                    @Override
                    public long sum() {
                        return cell.get();
                    }
                };
            }
            case "STRIPED_UNPADDED" -> striped(new StripedCounter(64, false));
            case "STRIPED_PADDED" -> striped(new StripedCounter(64, true));
            case "LONG_ADDER" -> {
                var adder = new LongAdder();
                yield new Counter() {
                    @Override
                    public void increment() {
                        adder.increment();
                    }

                    @Override
                    public long sum() {
                        return adder.sum();
                    }
                };
            }
            default -> throw new IllegalArgumentException(impl);
        };
    }

    private static Counter striped(StripedCounter delegate) {
        return new Counter() {
            @Override
            public void increment() {
                delegate.increment();
            }

            @Override
            public long sum() {
                return delegate.sum();
            }
        };
    }

    @Benchmark
    public void increment() {
        counter.increment();
    }
}
