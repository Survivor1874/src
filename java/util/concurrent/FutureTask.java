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

import java.util.concurrent.locks.LockSupport;

/**
 * FutureTask是一种可以取消的异步的计算任务。
 * 它的计算是通过Callable实现的，可以把它理解为是可以返回结果的Runnable。
 * <p>
 * 使用FutureTask的优势有：
 * <p>
 * 1. 可以获取线程执行后的返回结果；
 * 2. 提供了超时控制功能。
 * <p>
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 * @author Doug Lea
 * @since 1.5
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     * <p>
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     * <p>
     * 当创建一个FutureTask对象是，初始的状态是NEW，在运行时状态会转换，有4中状态的转换过程：
     * <p>
     * NEW -> COMPLETING -> NORMAL：正常执行并返回；
     * NEW -> COMPLETING -> EXCEPTIONAL：执行过程中出现了异常；
     * NEW -> CANCELLED；执行前被取消；
     * NEW -> INTERRUPTING -> INTERRUPTED：取消时被中断。
     */
    private volatile int state;
    private static final int NEW = 0;
    private static final int COMPLETING = 1;
    private static final int NORMAL = 2;
    private static final int EXCEPTIONAL = 3;
    private static final int CANCELLED = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED = 6;

    /**
     * 内部封装的Callable对象。如果通过构造函数传的是Runnable对象，
     * FutureTask会通过调用Executors#callable，把Runnable对象封装成一个callable。
     * The underlying callable; nulled out after running
     */
    private Callable<V> callable;

    /**
     * 用于保存计算结果或者异常信息。
     * The result to return or exception to throw from get()
     */
    private Object outcome; // non-volatile, protected by state reads/writes


    /**
     * 用来运行callable的线程。
     * The thread running the callable; CASed during run()
     */
    private volatile Thread runner;
    /**
     * Treiber stack of waiting threads
     * 使用 Treiber 算法实现的无阻塞的Stack，
     * 用于存放等待的线程
     */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL) {
            return (V) x;
        }
        if (s >= CANCELLED) {
            throw new CancellationException();
        }
        throw new ExecutionException((Throwable) x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result   the result to return on successful completion. If
     *                 you don't need a particular result, consider using
     *                 constructions of the form:
     *                 {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    @Override
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    @Override
    public boolean isDone() {
        return state != NEW;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW
                && UNSAFE.compareAndSwapInt(this, stateOffset, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED))) {
            return false;
        }
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null) {
                        t.interrupt();
                    }
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        int s = state;

        // NEW 或者 COMPLETING。
        if (s <= COMPLETING) {
            s = awaitDone(false, 0L);
        }
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    @Override
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null) {
            throw new NullPointerException();
        }
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING) {
            throw new TimeoutException();
        }
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() {
    }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;

            // final state
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    @Override
    public void run() {

        /*
         * 首先判断状态，如果不是初始状态，说明任务已经被执行或取消；
         * runner是FutureTask的一个属性，用于保存执行任务的线程，
         * 如果不为空则表示已经有线程正在执行，这里用CAS来设置，失败则返回。
         * state为NEW且对runner变量CAS成功。
         * 对state的判断写在前面，是一种优化。
         */
        if (state != NEW
                || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread())) {
            return;
        }

        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;

                /*
                 * 是否成功运行。
                 * 之所以用了这样一个标志位，而不是把set方法写在try中call调用的后一句，
                 * 是为了不想捕获set方法出现的异常。
                 *
                 * 举例来说，子类覆盖了FutureTask的done方法，
                 * set -> finishCompletion -> done会抛出异常，
                 * 然而实际上提交的任务是有正常的结果的。
                 */
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran) {
                    set(result);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()

            /*
             *
             * 要清楚，即便在runner被清为null后，仍然有可能有线程会进入到run方法的外层try块。
             * 举例：线程A和B都在执行第一行的if语句读到state == NEW,线程A成功cas了runner，并执行到此处。
             *       在此过程中线程B都没拿到CPU时间片。此时线程B一旦拿到时间片就能进到外层try块。
             *
             * 为了避免线程B重复执行任务，必须在set/setException方法被调用，才能把runner清为null。
             * 这时候其他线程即便进入到了外层try块，也一定能够读到state != NEW，从而避免任务重复执行。
             */
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts

            /*
             * 因为任务执行过程中由于cancel方法的调用，状态为INTERRUPTING,
             * 令当前线程等待INTERRUPTING状态变为INTERRUPTED。
             * 这是为了不想让中断操作逃逸出run方法以至于线程在执行后续操作时被中断。
             */
            int s = state;
            if (s >= INTERRUPTING) {
                handlePossibleCancellationInterrupt(s);
            }
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW
                || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread())) {
            return false;
        }
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING) {
                handlePossibleCancellationInterrupt(s);
            }
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.

        /*
         * 这里的主要目的就是等调用cancel方法的线程完成中断。
         *
         * 以防止中断操作逃逸出run或者runAndReset方法,影响后续操作。
         *
         * 实际上，当前调用cancel方法的线程不一定能够中断到本线程。
         * 有可能cancel方法里读到runner是null，甚至有可能是其它并发调用run/runAndReset方法的线程。
         * 但是也没办法判断另一个线程在cancel方法中读到的runner到底是什么，所以索性自旋让出CPU时间片也没事。
         */
        if (s == INTERRUPTING) {
            while (state == INTERRUPTING) {
                // wait out pending interrupt
                Thread.yield();
            }
        }


        /*
         * 下面的代码在JDK8中已经被注释掉了。
         * 因为在原来的设计中，是想把cancel方法设置的中断位给清除的。
         * 但是实际上也应该允许调用FutureTask的线程使用中断作为线程间通信的机制，
         * 这里没办法区分中断位到底是不是来自cancel方法的调用。
         */

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null; ) {

            // 必须将栈顶CAS为null，否则重读栈顶并重试。
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (; ; ) {

                    // 遍历并唤醒栈中节点对应的线程。
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null) {
                        break;
                    }

                    // unlink to help gc
                    // 将next域置为null，这样对GC友好。
                    q.next = null;
                    q = next;
                }
                break;
            }
        }

        /*
         * done方法是暴露给子类的一个钩子方法。
         *
         * 这个方法在ExecutorCompletionService.QueueingFuture中的override实现是把结果加到阻塞队列里。
         * CompletionService 谁用谁知道，奥秘全在这。
         */
        done();

        /*
         * to reduce footprint
         * callable置为null主要为了减少内存开销,
         * 更多可以了解JVM memory footprint相关资料。
         */
        callable = null;
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (; ; ) {
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            // 完成赋值
            int s = state;

            // 如果q已经被初始化了，为了GC需要清q.thread。
            if (s > COMPLETING) {
                if (q != null) {
                    q.thread = null;
                }
                return s;

                // cannot time out yet
                // COMPLETING 是一个很短暂的状态，调用Thread.yield期望让出时间片，之后重试循环。
            } else if (s == COMPLETING) {
                Thread.yield();

                // 初始化节点，重试一次循环。
            } else if (q == null) {
                q = new WaitNode();

                // queued记录是否已经入栈,此处准备将节点压栈。
            } else if (!queued) {

                /*
                 *  这是Treiber Stack算法入栈的逻辑。
                 *  Treiber Stack是一个基于CAS的无锁并发栈实现,
                 *  更多可以参考https://en.wikipedia.org/wiki/Treiber_Stack
                 */
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset, q.next = waiters, q);
            } else if (timed) {

                // 如果有时限，判断是否超时，未超时则park剩下的时间。
                nanos = deadline - System.nanoTime();
                // 超时，移除栈中节点。
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            } else {
                LockSupport.park(this);
            }
        }
    }

    /**
     *
     *  * 清理用于保存等待线程栈里的节点。
     *  * 所谓节点无效就是内部的thread为null，
     *  * 一般有以下几种情况:
     *  * 1. 节点调用get超时。
     *  * 2. 节点调用get中断。
     *  * 3. 节点调用get拿到task的状态值(> COMPLETING)。
     *  *
     *  * 此方法干了两件事情：
     *  * 1. 置标记参数node的thread为null。
     *  * 2. 清理栈中的无效节点。
     *  *
     *  * 如果在遍历过程中发现有竞争则重新遍历栈。
     *
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:

            // restart on removeWaiter race
            for (; ; ) {
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;

                    // 如果当前节点仍有效,则置pred为当前节点，继续遍历。
                    if (q.thread != null) {
                        pred = q;

                        /*
                         * 当前节点已无效且有前驱，则将前驱的后继置为当前节点的后继实现删除节点。
                         * 如果前驱节点已无效，则重新遍历waiters栈。
                         */
                    } else if (pred != null) {
                        pred.next = s;
                        // check for race
                        if (pred.thread == null)
                        {
                            continue retry;
                        }
                        /*
                         * 当前节点已无效，且当前节点没有前驱，则将栈顶置为当前节点的后继。
                         * 失败的话重新遍历waiters栈。
                         */
                    } else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s)) {
                        continue retry;
                    }
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
