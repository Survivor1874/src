/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * ScheduledThreadPoolExecutor内部使用了ScheduledFutureTask来表示任务，
 * 即使对于execute方法也将其委托至schedule方法，以零延时的形式实现。
 * 同时ScheduledThreadPoolExecutor也允许我们通过decorateTask方法来包装任务以实现定制化的封装。
 * <p>
 * 而ScheduledThreadPoolExecutor内部使用的阻塞队列DelayedWorkQueue通过小根堆来实现优先队列的功能。
 * 由于DelayedWorkQueue是无界的，所以本质上对于ScheduledThreadPoolExecutor而言，maximumPoolSize并没有意义。
 * 整体而言，ScheduledThreadPoolExecutor处理两类任务--延时任务与周期任务。
 * 通过ScheduledFutureTask.period的是否为零属于哪一类，
 * 通过ScheduledFutureTask.period的正负性来判断属于周期任务中的fixed-rate模式还是fixed-delay模式。
 * 并且提供了通过参数来控制延时任务与周期任务在线程池被关闭时
 * 是否需要被取消并移除出队列(如果还在队列)以及是否允许执行(如果已经被worker线程取出)。
 * DelayedWorkQueue使用了Leader/Follower来避免不必要的等待，只让leader来等待需要等待的时间，其余线程无限等待直至被唤醒即可。
 * DelayedWorkQueue所有的堆调整方法都维护了类型为ScheduledFutureTask的元素的heapIndex，以降低cancel的时间复杂度。
 *
 * ScheduledExecutorService本身继承了ExecutorService接口，并为调度任务额外提供了两种模式
 *
 * 延时执行
 *
 * schedule(Runnable, long, TimeUnit)
 * 根据参数中设定的延时，执行一次任务
 * schedule(Callable, long, TimeUnit)
 * 根据参数中设定的延时，执行一次任务
 *
 *
 * 周期执行
 *
 * scheduleAtFixedRate
 * 假设第n次任务开始时间是t，运行时间是p，设置的间隔周期为T则第n+1次任务的开始时间是max(t + p,t + T)。
 * 换句话说，如果任务执行足够快，则任务之间的间隔就是配置的周期T，否则如果任务执行比较慢，耗时超过T，
 * 则在任务结束后会立即开始下一次的任务。所以不会有同时并发执行提交的周期任务的情况。
 *
 * scheduleWithFixedDelay
 * 假设第n次任务结束时间是t，设置的延时为T，则第n+1次任务的开始时间是t+T。
 * 换句话说连续两个任务的首尾(本次结束与下次开始)为T。
 *
 * A {@link ThreadPoolExecutor} that can additionally schedule
 * commands to run after a given delay, or to execute
 * periodically. This class is preferable to {@link java.util.Timer}
 * when multiple worker threads are needed, or when the additional
 * flexibility or capabilities of {@link ThreadPoolExecutor} (which
 * this class extends) are required.
 *
 * <p>Delayed tasks execute no sooner than they are enabled, but
 * without any real-time guarantees about when, after they are
 * enabled, they will commence. Tasks scheduled for exactly the same
 * execution time are enabled in first-in-first-out (FIFO) order of
 * submission.
 *
 * <p>When a submitted task is cancelled before it is run, execution
 * is suppressed. By default, such a cancelled task is not
 * automatically removed from the work queue until its delay
 * elapses. While this enables further inspection and monitoring, it
 * may also cause unbounded retention of cancelled tasks. To avoid
 * this, set {@link #setRemoveOnCancelPolicy} to {@code true}, which
 * causes tasks to be immediately removed from the work queue at
 * time of cancellation.
 *
 * <p>Successive executions of a task scheduled via
 * {@code scheduleAtFixedRate} or
 * {@code scheduleWithFixedDelay} do not overlap. While different
 * executions may be performed by different threads, the effects of
 * prior executions <a
 * href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those of subsequent ones.
 *
 * <p>While this class inherits from {@link ThreadPoolExecutor}, a few
 * of the inherited tuning methods are not useful for it. In
 * particular, because it acts as a fixed-sized pool using
 * {@code corePoolSize} threads and an unbounded queue, adjustments
 * to {@code maximumPoolSize} have no useful effect. Additionally, it
 * is almost never a good idea to set {@code corePoolSize} to zero or
 * use {@code allowCoreThreadTimeOut} because this may leave the pool
 * without threads to handle tasks once they become eligible to run.
 *
 * <p><b>Extension notes:</b> This class overrides the
 * {@link ThreadPoolExecutor#execute(Runnable) execute} and
 * {@link AbstractExecutorService#submit(Runnable) submit}
 * methods to generate internal {@link ScheduledFuture} objects to
 * control per-task delays and scheduling.  To preserve
 * functionality, any further overrides of these methods in
 * subclasses must invoke superclass versions, which effectively
 * disables additional task customization.  However, this class
 * provides alternative protected extension method
 * {@code decorateTask} (one version each for {@code Runnable} and
 * {@code Callable}) that can be used to customize the concrete task
 * types used to execute commands entered via {@code execute},
 * {@code submit}, {@code schedule}, {@code scheduleAtFixedRate},
 * and {@code scheduleWithFixedDelay}.  By default, a
 * {@code ScheduledThreadPoolExecutor} uses a task type extending
 * {@link FutureTask}. However, this may be modified or replaced using
 * subclasses of the form:
 *
 * <pre> {@code
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {

    /*
     * This class specializes ThreadPoolExecutor implementation by
     *
     * 1. Using a custom task type, ScheduledFutureTask for
     *    tasks, even those that don't require scheduling (i.e.,
     *    those submitted using ExecutorService execute, not
     *    ScheduledExecutorService methods) which are treated as
     *    delayed tasks with a delay of zero.
     *
     * 2. Using a custom queue (DelayedWorkQueue), a variant of
     *    unbounded DelayQueue. The lack of capacity constraint and
     *    the fact that corePoolSize and maximumPoolSize are
     *    effectively identical simplifies some execution mechanics
     *    (see delayedExecute) compared to ThreadPoolExecutor.
     *
     * 3. Supporting optional run-after-shutdown parameters, which
     *    leads to overrides of shutdown methods to remove and cancel
     *    tasks that should NOT be run after shutdown, as well as
     *    different recheck logic when task (re)submission overlaps
     *    with a shutdown.
     *
     * 4. Task decoration methods to allow interception and
     *    instrumentation, which are needed because subclasses cannot
     *    otherwise override submit methods to get this effect. These
     *    don't have any impact on pool control logic though.
     */

    /**
     *
     * 此参数用于控制在线程池关闭后是否还执行已经存在的周期任务。
     * 可以通过setExecuteExistingDelayedTasksAfterShutdownPolicy来设置
     * 以及getContinueExistingPeriodicTasksAfterShutdownPolicy来获取。
     * False if should cancel/suppress periodic tasks on shutdown.
     */
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * 此参数用于控制在线程池关闭后是否还执行已经存在的延时任务。
     * 可以通过setExecuteExistingDelayedTasksAfterShutdownPolicy来设置
     * 以及getExecuteExistingDelayedTasksAfterShutdownPolicy来获取。
     * False if should cancel non-periodic tasks on shutdown.
     */
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    /**
     * 此参数用于控制ScheduledFutureTask在取消时是否应该要从工作队列中移除(如果还在队列中的话)。
     * 可以通过setRemoveOnCancelPolicy来设置以及getRemoveOnCancelPolicy来获取。
     * True if ScheduledFutureTask.cancel should remove from queue
     */
    private volatile boolean removeOnCancel = false;

    /**
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * Returns current nanosecond time.
     */
    final long now() {
        return System.nanoTime();
    }

    private class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {

        /**
         * 任务的序列号,在排序中会用到。
         * Sequence number to break ties FIFO
         */
        private final long sequenceNumber;

        /**
         * 任务可以被执行的时间，以纳秒表示。
         * The time the task is enabled to execute in nanoTime units
         */
        private long time;

        /**
         * 0表示非周期任务。正数表示fixed-rate模式，负数表示fixed-delay模式。
         * Period in nanoseconds for repeating tasks.
         * A positive value indicates fixed-rate execution.
         * A negative value indicates fixed-delay execution.
         * A value of 0 indicates a non-repeating task.
         */
        private final long period;

        /**
         * ScheduledThreadPoolExecutor#decorateTask允许我们包装一下
         * Executor构造的RunnableScheduledFuture(实现为ScheduledFutureTask)
         * 并重新返回一个RunnableScheduledFuture给Executor。
         * 所以ScheduledFutureTask.outerTask实际上就是decorateTask方法包装出来的结果。
         * decorateTask默认返回的就是参数中的RunnableScheduledFuture，也就是不进行包装，
         * 这种情况下outerTask就是ScheduledFutureTask自身了。
         * outerTask的主要目的就是让周期任务在第二次及之后的运行时跑的都是decorateTask返回的包装结果。
         * The actual task to be re-enqueued by reExecutePeriodic
         */
        RunnableScheduledFuture<V> outerTask = this;

        /**
         * 用于维护该任务在 DelayedWorkQueue 内部堆中的索引 (在堆数组中的index)。
         * Index into delay queue, to support faster cancellation.
         */
        int heapIndex;

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Runnable r, V result, long ns) {
            super(r, result);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a periodic action with given nano time and period.
         */
        ScheduledFutureTask(Runnable r, V result, long ns, long period) {
            super(r, result);
            this.time = ns;
            this.period = period;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        ScheduledFutureTask(Callable<V> callable, long ns) {
            super(callable);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(time - now(), NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
            {
                return 0;
            }
            if (other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>) other;
                long diff = time - x.time;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return 1;
                } else if (sequenceNumber < x.sequenceNumber) {
                    return -1;
                } else {
                    return 1;
                }
            }
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        /**
         * Returns {@code true} if this is a periodic (not a one-shot) action.
         *
         * @return {@code true} if periodic
         */
        @Override
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        private void setNextRunTime() {
            long p = period;

            /*
             * fixed-rate模式，时间设置为上一次时间+p。
             * 提一句，这里的时间其实只是可以被执行的最小时间，不代表到点就要执行。
             * 如果这次任务还没执行完是肯定不会执行下一次的。
             */
            if (p > 0) {
                time += p;
            } else {

                /**
                 * fixed-delay模式，计算下一次任务可以被执行的时间。
                 * 简单来说差不多就是当前时间+delay值。因为代码走到这里任务就已经结束了,now()可以认为就是任务结束时间。
                 */
                time = triggerTime(-p);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {

            // 先调用父类FutureTask#cancel来取消任务。
            boolean cancelled = super.cancel(mayInterruptIfRunning);

            /*
             * removeOnCancel 开关用于控制任务取消后是否应该从队列中移除。
             *
             * 如果已经成功取消，并且removeOnCancel开关打开，并且heapIndex >= 0(说明仍然在队列中),
             * 则从队列中删除该任务。
             */
            if (cancelled && removeOnCancel && heapIndex >= 0) {
                remove(this);
            }
            return cancelled;
        }

        /**
         * 如果不能再当前状态下运行了，就取消这个任务。
         * 如果不是周期性的任务，就执行 FutureTask 的 run 方法。
         * 如果是周期性的任务，就需要执行 runAndReset方法。
         * 执行完毕后，重写设置当前任务的下次执行时间，然后添加进队列中。
         * Overrides FutureTask version so as to reset/requeue if periodic.
         */
        @Override
        public void run() {

            // 是否是周期性任务
            boolean periodic = isPeriodic();

            // 如果不可以在当前状态下运行，就取消任务（将这个任务的状态设置为CANCELLED）。
            if (!canRunInCurrentRunState(periodic)) {
                cancel(false);
            } else if (!periodic) {

                // 如果不是周期性的任务，调用 FutureTask # run 方法
                ScheduledFutureTask.super.run();

                // 如果是周期性的。
                // 执行任务，但不设置返回值，成功后返回 true。（callable 不可以重复执行）
            } else if (ScheduledFutureTask.super.runAndReset()) {

                // 设置下次执行时间
                setNextRunTime();

                // 再次将任务添加到队列中
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * Returns true if can run a task given current run state
     * and run-after-shutdown parameters.
     *
     * @param periodic true if this task periodic, false if delayed
     */
    boolean canRunInCurrentRunState(boolean periodic) {

        /*
         * isRunningOrShutdown 的参数为布尔值，true则表示 shutdown 状态也返回true,
         * 否则只有running状态返回ture。
         * 如果为周期性任务则根据 continueExistingPeriodicTasksAfterShutdown 来判断是否 shutdown 了仍然可以执行。
         * 否则根据 executeExistingDelayedTasksAfterShutdown 来判断是否 shutdown 了仍然可以执行。
         */
        return isRunningOrShutdown(periodic ?
                continueExistingPeriodicTasksAfterShutdown :
                executeExistingDelayedTasksAfterShutdown);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {

        // 非 RUNNING 态，根据饱和策略处理任务。
        if (isShutdown()) {
            reject(task);
        } else {

            // 往 work queue 中插入任务。
            super.getQueue().add(task);

            /*
             * 检查任务是否可以被执行。
             * 如果任务不应该被执行，并且从队列中成功移除的话(说明没被 worker 拿取执行)，则调用cancel取消任务。
             */
            if (isShutdown() && !canRunInCurrentRunState(task.isPeriodic()) && remove(task)) {

                // 参数中 false 表示不试图中断执行任务的线程。
                task.cancel(false);
            } else {
                ensurePrestart();
            }
        }
    }

    /**
     * Requeues a periodic task unless current run state precludes it.
     * Same idea as delayedExecute except drops task rather than rejecting.
     *
     * @param task the task
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(true)) {

            // 塞到工作队列中。
            super.getQueue().add(task);

            // 再次检查是否可以执行，如果不能执行且任务还在队列中未被取走则取消任务。
            if (!canRunInCurrentRunState(true) && remove(task)) {
                task.cancel(false);
            } else {
                ensurePrestart();
            }
        }
    }

    /**
     * onShutdown方法是ThreadPoolExecutor的一个钩子方法，会在shutdown方法中被调用，默认实现为空。
     * 而 ScheduledThreadPoolExecutor 覆盖了此方法用于删除并取消工作队列中的不需要再执行的任务。
     * Cancels and clears the queue of all tasks that should not be run
     * due to shutdown policy.  Invoked within super.shutdown.
     */
    @Override
    void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();

        // shutdown是否仍然执行延时任务。
        boolean keepDelayed = getExecuteExistingDelayedTasksAfterShutdownPolicy();

        // shutdown是否仍然执行周期任务。
        boolean keepPeriodic = getContinueExistingPeriodicTasksAfterShutdownPolicy();

        // 如果两者皆不可 则对队列中所有 RunnableScheduledFuture 调用 cancel 取消并清空队列。
        if (!keepDelayed && !keepPeriodic) {
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture<?>) {
                    ((RunnableScheduledFuture<?>) e).cancel(false);
                }
            }
            q.clear();
        } else {
            // Traverse snapshot to avoid iterator exceptions
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture) {
                    RunnableScheduledFuture<?> t = (RunnableScheduledFuture<?>) e;

                    /*
                     * 不需要执行的任务删除并取消。
                     * 已经取消的任务也需要从队列中删除。
                     * 所以这里就判断下是否需要执行或者任务是否已经取消。
                     */
                    if ((t.isPeriodic() ? !keepPeriodic : !keepDelayed) || t.isCancelled()) {

                        // also remove if already cancelled
                        if (q.remove(t)) {
                            t.cancel(false);
                        }
                    }
                }
            }
        }

        // 因为任务被从队列中清理掉，所以这里需要调用 tryTerminate 尝试跃迁 executor 的状态。
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param runnable the submitted Runnable
     * @param task     the task created to execute the runnable
     * @param <V>      the type of the task's result
     * @return a task that can execute the runnable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable the submitted Callable
     * @param task     the task created to execute the callable
     * @param <V>      the type of the task's result
     * @return a task that can execute the callable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
            Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given core pool size.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *                     if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     */
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS, new DelayedWorkQueue());
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given initial parameters.
     *
     * @param corePoolSize  the number of threads to keep in the pool, even
     *                      if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *                      creates a new thread
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException     if {@code threadFactory} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
                new DelayedWorkQueue(), threadFactory);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *                     if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param handler      the handler to use when execution is blocked
     *                     because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException     if {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
                new DelayedWorkQueue(), handler);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize  the number of threads to keep in the pool, even
     *                      if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *                      creates a new thread
     * @param handler       the handler to use when execution is blocked
     *                      because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException     if {@code threadFactory} or
     *                                  {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
                new DelayedWorkQueue(), threadFactory, handler);
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    long triggerTime(long delay) {

        /*
         * 如果delay < Long.Max_VALUE/2,则下次执行时间为当前时间+delay。
         *
         * 否则为了避免队列中出现由于溢出导致的排序紊乱,需要调用overflowFree来修正一下delay(如果有必要的话)。
         */
        return now() + ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * * 主要就是有这么一种情况：
     * * 某个任务的delay为负数，说明当前可以执行(其实早该执行了)。
     * * 工作队列中维护任务顺序是基于compareTo的，在compareTo中比较两个任务的顺序会用time相减，负数则说明优先级高。
     * *
     * * 那么就有可能出现一个delay为正数,减去另一个为负数的delay，结果上溢为负数，则会导致compareTo产生错误的结果。
     * *
     * * 为了特殊处理这种情况，首先判断一下队首的delay是不是负数，如果是正数不用管了,怎么减都不会溢出。
     * * 否则可以拿当前delay减去队首的delay来比较看，如果不出现上溢，则整个队列都ok，排序不会乱。
     * * 不然就把当前delay值给调整为Long.MAX_VALUE + 队首delay。
     * <p>
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            long headDelay = head.getDelay(NANOSECONDS);
            if (headDelay < 0 && (delay - headDelay < 0)) {
                delay = Long.MAX_VALUE + headDelay;
            }
        }
        return delay;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }

        // 包装ScheduledFutureTask,默认返回本身。
        RunnableScheduledFuture<?> t = decorateTask(command, new ScheduledFutureTask<Void>(command, null, triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit) {
        if (callable == null || unit == null) {
            throw new NullPointerException();
        }
        RunnableScheduledFuture<V> t = decorateTask(callable,
                new ScheduledFutureTask<V>(callable,
                        triggerTime(delay, unit)));
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (period <= 0) {
            throw new IllegalArgumentException();
        }

        // fixed-rate模式period为正数。
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit), unit.toNanos(period));

        // 包装ScheduledFutureTask,默认返回本身。
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);

        // 将构造出的 ScheduledFutureTask 的 outerTask 设置为经过包装的结果。
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (delay <= 0) {
            throw new IllegalArgumentException();
        }

        // fixed-delay模式delay为正数。
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<Void>(command, null, triggerTime(initialDelay, unit), unit.toNanos(-delay));

        // 包装ScheduledFutureTask,默认返回本身。
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);

        // 将构造出的ScheduledFutureTask的outerTask设置为经过包装的结果。
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * 覆盖了父类execute的实现，以零延时任务的形式实现。
     * <p>
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable, long, TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException at discretion of
     *                                    {@code RejectedExecutionHandler}, if the task
     *                                    cannot be accepted for execution because the
     *                                    executor has been shut down
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public void execute(Runnable command) {
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value if {@code true}, continue after shutdown, else don't
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
        }
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value if {@code true}, execute after shutdown, else don't
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown()) {
            onShutdown();
        }
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value if {@code true}, remove on cancellation, else don't
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     * from the queue
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    @Override
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     * Each element of this list is a {@link ScheduledFuture},
     * including those tasks submitted using {@code execute},
     * which are for scheduling purposes used as the basis of a
     * zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    @Override
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Each element of
     * this queue is a {@link ScheduledFuture}, including those
     * tasks submitted using {@code execute} which are for scheduling
     * purposes used as the basis of a zero-delay
     * {@code ScheduledFuture}.  Iteration over this queue is
     * <em>not</em> guaranteed to traverse tasks in the order in
     * which they will execute.
     *
     * @return the task queue
     */
    @Override
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * DelayedWorkQueue是ScheduledThreadPoolExecutor使用的工作队列。
     * 它内部维护了一个小根堆，根据任务的执行开始时间来维护任务顺序。
     * 但不同的地方在于，它对于 ScheduledFutureTask 类型的元素额外维护了元素在队列中堆数组的索引，用来实现快速取消。
     * DelayedWorkQueue用了ReentrantLock+Condition来实现管程保证数据的线程安全性。
     * <p>
     * ScheduledThreadPoolExecutor支持任务取消的时候快速从队列中移除，
     * 因为大部分情况下队列中的元素是ScheduledFutureTask类型，
     * 内部维护了heapIndex也即在堆数组中的索引。
     * 堆移除一个元素的时间复杂度是O(log n)，前提是我们需要知道待删除元素在堆数组中的位置。
     * 如果我们不维护heapIndex则需要遍历整个堆数组来定位元素在堆数组的位置，
     * 这样光是扫描一次堆数组复杂度就O(n)了。
     * 而维护了heapIndex，就可以以O(1)的时间来确认位置，从而可以更快的移除元素。
     * Specialized delay queue. To mesh with TPE declarations, this
     * class must be declared as a BlockingQueue<Runnable> even though
     * it can only hold RunnableScheduledFutures.
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {

        /*
         * A DelayedWorkQueue is based on a heap-based data structure
         * like those in DelayQueue and PriorityQueue, except that
         * every ScheduledFutureTask also records its index into the
         * heap array. This eliminates the need to find a task upon
         * cancellation, greatly speeding up removal (down from O(n)
         * to O(log n)), and reducing garbage retention that would
         * otherwise occur by waiting for the element to rise to top
         * before clearing. But because the queue may also hold
         * RunnableScheduledFutures that are not ScheduledFutureTasks,
         * we are not guaranteed to have such indices available, in
         * which case we fall back to linear search. (We expect that
         * most tasks will not be decorated, and that the faster cases
         * will be much more common.)
         *
         * All heap operations must record index changes -- mainly
         * within siftUp and siftDown. Upon removal, a task's
         * heapIndex is set to -1. Note that ScheduledFutureTasks can
         * appear at most once in the queue (this need not be true for
         * other kinds of tasks or work queues), so are uniquely
         * identified by heapIndex.
         */

        private static final int INITIAL_CAPACITY = 16;

        private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];

        private final ReentrantLock lock = new ReentrantLock();

        private int size = 0;

        /**
         * Thread designated to wait for the task at the head of the
         * queue.  This variant of the Leader-Follower pattern
         * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
         * minimize unnecessary timed waiting.  When a thread becomes
         * the leader, it waits only for the next delay to elapse, but
         * other threads await indefinitely.  The leader thread must
         * signal some other thread before returning from take() or
         * poll(...), unless some other thread becomes leader in the
         * interim.  Whenever the head of the queue is replaced with a
         * task with an earlier expiration time, the leader field is
         * invalidated by being reset to null, and some waiting
         * thread, but not necessarily the current leader, is
         * signalled.  So waiting threads must be prepared to acquire
         * and lose leadership while waiting.
         */
        private Thread leader = null;

        /**
         * Condition signalled when a newer task becomes available at the
         * head of the queue or a new thread may need to become leader.
         */
        private final Condition available = lock.newCondition();

        /**
         * Sets f's heapIndex if it is a ScheduledFutureTask.
         */
        private void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask) {
                ((ScheduledFutureTask) f).heapIndex = idx;
            }
        }

        /**
         * Sifts element added at bottom up to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            while (k > 0) {
                int parent = (k - 1) >>> 1;
                RunnableScheduledFuture<?> e = queue[parent];
                if (key.compareTo(e) >= 0) {
                    break;
                }
                queue[k] = e;
                setIndex(e, k);
                k = parent;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Sifts element added at top down to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            int half = size >>> 1;
            while (k < half) {
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                int right = child + 1;
                if (right < size && c.compareTo(queue[right]) > 0) {
                    c = queue[child = right];
                }
                if (key.compareTo(c) <= 0) {
                    break;
                }
                queue[k] = c;
                setIndex(c, k);
                k = child;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Resizes the heap array.  Call only when holding lock.
         */
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
            {
                newCapacity = Integer.MAX_VALUE;
            }
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * Finds index of given object, or -1 if absent.
         */
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.

                    // 再次判断i确实是本线程池的，因为remove方法的参数x完全可以是个其它池子里拿到的ScheduledFutureTask。
                    if (i >= 0 && i < size && queue[i] == x) {
                        return i;
                    }
                } else {
                    for (int i = 0; i < size; i++) {
                        if (x.equals(queue[i])) {
                            return i;
                        }
                    }
                }
            }
            return -1;
        }

        @Override
        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0) {
                    return false;
                }
                setIndex(queue[i], -1);

                /*
                 * 堆的删除某个元素操作就是将最后一个元素移到那个元素。
                 * 这时候有可能需要向上调整堆，也可能需要向下维护。
                 *
                 * 对于小根堆而言，如果移过去后比父元素小，则需要向上维护堆结构，
                 * 否则将左右两个子节点中较小值与当前元素比较，如果当前元素较大，则需要向下维护堆结构。
                 */
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;

                // 如果参数x就是堆数组中最后一个元素则删除操作已经完毕了。
                if (s != i) {

                    // 尝试向下维护堆。
                    siftDown(i, replacement);

                    // 相等说明 replacement 比子节点都要小，尝试向上维护堆。
                    if (queue[i] == replacement) {
                        siftUp(i, replacement);
                    }
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean offer(Runnable x) {
            if (x == null) {
                throw new NullPointerException();
            }
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>) x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if (i >= queue.length) {

                    // 容量扩增50%。
                    grow();
                }
                size = i + 1;

                // 第一个元素,其实这里也可以统一进行sift-up操作,没必要特判。
                if (i == 0) {
                    queue[0] = e;
                    setIndex(e, 0);
                } else {

                    // 插入堆尾。
                    siftUp(i, e);
                }

                // 如果新加入的元素成为了堆顶,则原先的leader就无效了。
                if (queue[0] == e) {
                    leader = null;

                    // 由于原先leader已经无效被设置为null了,这里随便唤醒一个线程(未必是原先的leader)来取走堆顶任务。
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        @Override
        public void put(Runnable e) {
            offer(e);
        }

        @Override
        public boolean add(Runnable e) {
            return offer(e);
        }

        @Override
        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: Replaces
         * first element with last and sifts it down.  Call only when
         * holding lock.
         *
         * @param f the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            int s = --size;
            RunnableScheduledFuture<?> x = queue[s];
            queue[s] = null;
            if (s != 0)
                siftDown(0, x);
            setIndex(f, -1);
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                if (first == null || first.getDelay(NANOSECONDS) > 0)
                    return null;
                else
                    return finishPoll(first);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {

                /*
                 * 循环读取当前堆中最小也就执行开始时间最近的任务。
                 * 如果当前队列为空无任务,则在 available 条件上等待。
                 * 否则如果最近任务的 delay<=0 则返回这个任务以执行,否则的话根据是否可以作为leader分类：
                 *     如果可以作为 leader ,则根据 delay 进行有时限等待。
                 *     否则无限等待直至被 leader 唤醒。
                 */
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];

                    if (first == null) {

                        // 如果当前队列无元素，则在available条件上无限等待直至有任务通过offer入队并唤醒。
                        available.await();
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0) {

                            // 从堆中移除元素并返回结果。
                            // 如果delay小于0说明任务该立刻执行了。
                            return finishPoll(first);
                        }

                        /*
                         * 在接下来等待的过程中,first应该清为null。
                         * 因为下一轮重新拿到的最近需要执行的任务很可能已经不是这里的first了。
                         * 所以对于接下来的逻辑来说first已经没有任何用处了，不该持有引用。
                         */
                        // don't retain ref while waiting
                        first = null;

                        // 如果目前有 leader 的话,当前线程作为 follower 在 available 条件上无限等待直至唤醒。
                        if (leader != null) {
                            available.await();
                        } else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                available.awaitNanos(delay);
                            } finally {

                                /*
                                 * 如果从available条件中被唤醒当前线程仍然是leader,则清空leader。
                                 *
                                 * 分析一下这里不等的情况:
                                 * 1. 原先thisThread == leader, 然后堆顶更新了,leader为null。
                                 * 2. 堆顶更新,offer方法释放锁后,有其它线程通过take/poll拿到锁,读到leader == null，然后将自身更新为leader。
                                 *
                                 * 对于这两种情况统一的处理逻辑就是只要leader为thisThread，则清leader为null用以接下来判断是否需要唤醒后继线程。
                                 */
                                if (leader == thisThread) {
                                    leader = null;
                                }
                            }
                        }
                    }
                }
            } finally {

                /*
                 * 如果当前堆中无元素(根据堆顶判断)则直接释放锁。
                 *
                 *
                 * 否则如果leader有值，说明当前线程一定不是leader，当前线程不用去唤醒后续等待线程。
                 *     否则由当前线程来唤醒后继等待线程。不过这并不代表当前线程原来是leader。
                 */
                if (leader == null && queue[0] != null) {
                    available.signal();
                }
                lock.unlock();
            }
        }

        @Override
        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit)
                throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0)
                            return null;
                        else
                            nanos = available.awaitNanos(nanos);
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            return finishPoll(first);
                        if (nanos <= 0)
                            return null;
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null)
                            nanos = available.awaitNanos(nanos);
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns first element only if it is expired.
         * Used only by drainTo.  Call only when holding lock.
         */
        private RunnableScheduledFuture<?> peekExpired() {
            // assert lock.isHeldByCurrentThread();
            RunnableScheduledFuture<?> first = queue[0];
            return (first == null || first.getDelay(NANOSECONDS) > 0) ?
                    null : first;
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            if (c == null) {
                throw new NullPointerException();
            }
            if (c == this) {
                throw new IllegalArgumentException();
            }
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while ((first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            if (maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while (n < maxElements && (first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            return new Itr(Arrays.copyOf(queue, size));
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor = 0;     // index of next element to return
            int lastRet = -1;   // index of last element, or -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length)
                    throw new NoSuchElementException();
                lastRet = cursor;
                return array[cursor++];
            }

            public void remove() {
                if (lastRet < 0)
                    throw new IllegalStateException();
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
