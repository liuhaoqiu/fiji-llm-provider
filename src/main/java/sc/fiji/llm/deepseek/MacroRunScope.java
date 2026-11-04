/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated macro evaluation group, excluding editor/console maintenance.
 * Actual macro-created children are awaited, including sleeping children.
 * Unknown persistent children remain running instead of guessed completion. */
final class MacroRunScope {
    final ThreadGroup group;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    volatile boolean bodyFinished;
    MacroRunScope(String id) {
        group = new ThreadGroup("Fiji-LLM-macro-" + id) {
            @Override public void uncaughtException(Thread t, Throwable error) {
                failure.compareAndSet(null, error);
            }
        };
    }

    void run(Runnable evaluation) {
        Thread body = new Thread(group, () -> {
            try { evaluation.run(); }
            catch (Throwable t) { failure.set(t); }
            finally { bodyFinished = true; }
        }, "Fiji-LLM-macro-evaluation");
        body.setDaemon(true);
        body.start();
        boolean interrupted = false;
        boolean idlePass = false;
        // Interrupting a waiting caller must not release the single-run gate
        // while ImageJ commands or real child threads are still executing.
        while (true) {
            Thread[] children = new Thread[Math.max(16, group.activeCount() * 2 + 8)];
            int count = group.enumerate(children, true);
            if (count == 0 && !body.isAlive()) break;
            int pending = 0;
            for (int i = 0; i < count; i++) {
                if (idleMaintenanceWorker(children[i])) continue;
                pending++;
                try { children[i].join(50); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (pending == 0 && !body.isAlive()) {
                if(idlePass)break;
                idlePass=true;
                try{Thread.sleep(10);}catch(InterruptedException e){interrupted=true;}
            }else idlePass=false;
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure.get() != null) throw new CompletionException(failure.get());
    }

    /** Shared JVM workers can inherit the group of their first submitter.
     * Pool identity and the actual idle wait stack distinguish these
     * maintenance workers from macro work; thread names are not trusted. */
    static boolean idleSharedWorker(Thread thread) {
        if (!(thread instanceof ForkJoinWorkerThread worker)) return false;
        ForkJoinPool common = ForkJoinPool.commonPool();
        if (worker.getPool() != common) return false;
        Thread.State state = thread.getState();
        if (state == Thread.State.TERMINATED) return true;
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
        for (StackTraceElement frame : thread.getStackTrace())
            if (frame.getClassName().equals("java.util.concurrent.ForkJoinPool") &&
                frame.getMethodName().equals("awaitWork")) return true;
        return false;
    }

    static boolean idleMaintenanceWorker(Thread thread) {
        if (idleSharedWorker(thread)) return true;
        if (idleExecutorWorker(thread)) return true;
        // ImageJ's StackWindow starts a long-lived slice-selector event loop
        // on the thread group that creates the image window. Its Object.wait
        // is a UI idle state, not pending macro computation. Do not use the
        // configurable thread name or exempt an actively updating selector.
        if (thread.getState() != Thread.State.WAITING) return false;
        StackTraceElement[] frames = thread.getStackTrace();
        boolean objectWait = false;
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().equals("java.lang.Object") &&
                (frame.getMethodName().equals("wait") || frame.getMethodName().equals("wait0"))) objectWait = true;
            if (objectWait && frame.getClassName().equals("ij.gui.StackWindow") &&
                frame.getMethodName().equals("run")) return true;
        }
        return false;
    }

    /** A completed executor job leaves its worker waiting for the next job.
     * Recognize the actual getTask/runWorker boundary and standard untimed
     * queues, never a thread name or an arbitrary parked task. Delayed queues
     * remain awaited because a scheduled job may not have become runnable yet. */
    static boolean idleExecutorWorker(Thread thread) {
        Thread.State state=thread.getState();
        if(state!=Thread.State.WAITING && state!=Thread.State.TIMED_WAITING)return false;
        StackTraceElement[] frames=thread.getStackTrace();
        boolean queueWait=false;
        for(int i=0;i<frames.length;i++){
            String type=frames[i].getClassName(),method=frames[i].getMethodName();
            if(type.startsWith("java.util.concurrent.ScheduledThreadPoolExecutor")||type.equals("java.util.concurrent.DelayQueue"))return false;
            if((type.equals("java.util.concurrent.SynchronousQueue")||type.equals("java.util.concurrent.LinkedBlockingQueue")||
                type.equals("java.util.concurrent.ArrayBlockingQueue")||type.equals("java.util.concurrent.LinkedTransferQueue"))&&
                (method.equals("take")||method.equals("poll")))queueWait=true;
            if(type.equals("java.util.concurrent.ThreadPoolExecutor")&&method.equals("getTask"))
                return queueWait && i+1<frames.length && frames[i+1].getClassName().equals(type)&&frames[i+1].getMethodName().equals("runWorker")&&
                    (thread.getState()==Thread.State.WAITING||thread.getState()==Thread.State.TIMED_WAITING);
        }
        return false;
    }

    String pendingDescription() {
        Thread[] threads = new Thread[Math.max(16, group.activeCount() * 2 + 8)];
        int count = group.enumerate(threads, true);
        var descriptions = new java.util.ArrayList<String>();
        for (int i = 0; i < count && descriptions.size() < 8; i++) {
            Thread thread = threads[i];
            if (!thread.isAlive() || idleMaintenanceWorker(thread)) continue;
            StackTraceElement[] frames = thread.getStackTrace();
            descriptions.add(thread.getName() + " [" + thread.getClass().getName() +
                ", " + thread.getState() + "] " + (frames.length == 0 ? "" : frames[0]));
        }
        return String.join("; ", descriptions);
    }
}
