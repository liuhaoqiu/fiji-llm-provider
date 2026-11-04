/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Cold-JVM regression: a shared ForkJoin worker inherits its first caller's group. */
public final class SharedWorkerVerificationMain {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var done=new CountDownLatch(1);var bodyDone=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();var scope=new MacroRunScope("shared-worker");
        var worker=new AtomicReference<Thread>();
        Thread controller=new Thread(()->{
            try {scope.run(()->{
                ForkJoinPool.commonPool().submit(()->{
                    worker.set(Thread.currentThread());
                    Thread.currentThread().setName("renamed-shared-worker-fixture");
                }).join();bodyDone.countDown();
            });}catch(Throwable t){failure.set(t);}finally{done.countDown();}
        });controller.setDaemon(true);controller.start();
        check(bodyDone.await(3,TimeUnit.SECONDS),"shared computation body finished");
        check(worker.get() instanceof ForkJoinWorkerThread,"actual ForkJoin worker used");
        check(worker.get().getThreadGroup()==scope.group,"cold shared worker inherited macro group");
        if(args.length>0 && args[0].equals("baseline")) {
            check(!done.await(2,TimeUnit.SECONDS),"0.5.6 must reproduce indefinite child wait");
            System.out.println("REPRODUCED: 0.5.6 waits for a completed shared common-pool worker inherited by the macro group");
            Runtime.getRuntime().halt(0);
        }
        check(done.await(3,TimeUnit.SECONDS),"completed shared worker must not prevent completion");
        check(failure.get()==null,"scope failure: "+failure.get());

        var release=new CountDownLatch(1);var running=new CountDownLatch(1);var complete=new CountDownLatch(1);
        Thread busyController=new Thread(()->{
            scope.run(()->{
                // The worker already belongs to this exact group. Keep real
                // work on it active after the macro body returns.
                ForkJoinPool.commonPool().execute(()->{
                    running.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}
                });
                try{running.await();}catch(InterruptedException e){throw new RuntimeException(e);}
            });complete.countDown();
        });busyController.setDaemon(true);busyController.start();check(running.await(2,TimeUnit.SECONDS),"real common task started");
        Thread.sleep(100);check(complete.getCount()==1,"active shared computation must not complete early");
        release.countDown();check(complete.await(3,TimeUnit.SECONDS),"common task completion releases wait");

        // An unrelated busy shared worker must not create a global-quiescence
        // dependency for an already completed macro.
        if(ForkJoinPool.commonPool().getParallelism()>1) {
            var occupied=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);
            ForkJoinPool.commonPool().execute(()->{occupied.countDown();try{releaseFirst.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
            check(occupied.await(2,TimeUnit.SECONDS),"first worker occupied");
            var outsideStarted=new CountDownLatch(1);var releaseOutside=new CountDownLatch(1);
            var outsideWorker=new AtomicReference<Thread>();
            ForkJoinPool.commonPool().execute(()->{outsideWorker.set(Thread.currentThread());outsideStarted.countDown();try{releaseOutside.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
            check(outsideStarted.await(2,TimeUnit.SECONDS),"unrelated worker active");
            check(outsideWorker.get().getThreadGroup()!=scope.group,"unrelated worker is outside macro group");
            releaseFirst.countDown();
            var isolatedDone=new CountDownLatch(1);Thread isolated=new Thread(()->{scope.run(()->{});isolatedDone.countDown();});isolated.setDaemon(true);isolated.start();
            check(isolatedDone.await(2,TimeUnit.SECONDS),"unrelated common activity must not hold a completed macro");
            releaseOutside.countDown();
        }

        var ownPool=new AtomicReference<ForkJoinPool>();var privateDone=new CountDownLatch(1);
        Thread privateController=new Thread(()->{
            new MacroRunScope("private-pool").run(()->{
                var p=new ForkJoinPool(1);ownPool.set(p);p.submit(()->{}).join();
            });privateDone.countDown();
        });privateController.setDaemon(true);privateController.start();
        while(ownPool.get()==null)Thread.sleep(10);Thread.sleep(100);
        check(privateDone.getCount()==1,"a dedicated pool must not be mistaken for the common pool");
        ownPool.get().shutdown();check(privateDone.await(3,TimeUnit.SECONDS),"dedicated pool shutdown releases wait");
        System.out.println("PASS: cold shared-worker inheritance, renamed-worker identity, active-task preservation, unrelated common activity and private-pool isolation");
    }
}
