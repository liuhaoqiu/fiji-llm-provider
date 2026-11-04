/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Reproduce the actual ordinary-executor inheritance, then protect real work. */
public final class ExecutorWorkerVerificationMain {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class Run {
        final MacroRunScope scope=new MacroRunScope("ordinary-executor");
        final CountDownLatch done=new CountDownLatch(1),body=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        Run(Runnable action){Thread controller=new Thread(()->{
            try{scope.run(()->{action.run();body.countDown();});}catch(Throwable t){failure.set(t);}finally{done.countDown();}
        });controller.setDaemon(true);controller.start();}
        void finished()throws Exception{check(done.await(3,TimeUnit.SECONDS),"run must finish");check(failure.get()==null,"scope failure: "+failure.get());}
    }
    public static void main(String[] args)throws Exception{
        baselineOrIdle(args.length>0&&args[0].equals("baseline"));
        if(args.length>0)return;
        activeAndQueued();scheduled();unknown();
        System.out.println("PASS: inherited ordinary executor idle completion, reusable renamed workers, active/queued jobs, scheduled jobs and unknown-child preservation");
    }
    static void baselineOrIdle(boolean baseline)throws Exception{
        var pool=new AtomicReference<ThreadPoolExecutor>();var worker=new AtomicReference<Thread>();
        Run run=new Run(()->{
            var p=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>());pool.set(p);
            try{p.submit(()->{worker.set(Thread.currentThread());Thread.currentThread().setName("renamed-ordinary-idle-worker");}).get();}
            catch(Exception e){throw new RuntimeException(e);}p.prestartAllCoreThreads();
        });
        try{
            check(run.body.await(3,TimeUnit.SECONDS),"macro body completed");
            check(worker.get().getThreadGroup()==run.scope.group,"real worker inherited the macro group");
            if(baseline){
                check(!run.done.await(1,TimeUnit.SECONDS),"0.5.8 must reproduce the persistent wait");
                System.out.println("REPRODUCED: 0.5.8 keeps waiting after ordinary executor work is done and its workers wait in getTask");
            }else{
                run.finished();check(!pool.get().isShutdown(),"the extension must not terminate the executor");
                check(pool.get().submit(()->42).get(2,TimeUnit.SECONDS)==42,"idle pool remains reusable after macro completion");
            }
        }finally{if(pool.get()!=null)pool.get().shutdownNow();run.finished();}
    }
    static void activeAndQueued()throws Exception{
        var pool=new AtomicReference<ExecutorService>();var release=new CountDownLatch(1);var started=new CountDownLatch(1);var queuedDone=new AtomicBoolean();
        Run run=new Run(()->{
            var p=Executors.newFixedThreadPool(1);pool.set(p);
            p.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
            p.execute(()->{try{Thread.sleep(150);}catch(InterruptedException e){throw new RuntimeException(e);}queuedDone.set(true);});
        });
        try{check(started.await(2,TimeUnit.SECONDS)&&run.body.await(2,TimeUnit.SECONDS),"active/queued fixture started");
            check(!run.done.await(100,TimeUnit.MILLISECONDS),"active blocked task is real work");release.countDown();run.finished();check(queuedDone.get(),"queued work must finish before the macro does");
        }finally{release.countDown();if(pool.get()!=null)pool.get().shutdownNow();}
    }
    static void scheduled()throws Exception{
        var pool=new AtomicReference<ScheduledExecutorService>();var jobDone=new AtomicBoolean();
        Run run=new Run(()->{var p=Executors.newScheduledThreadPool(1);pool.set(p);p.schedule(()->jobDone.set(true),300,TimeUnit.MILLISECONDS);p.shutdown();});
        try{check(run.body.await(2,TimeUnit.SECONDS),"scheduled body returned");check(!run.done.await(100,TimeUnit.MILLISECONDS),"a delayed queue is not idle maintenance");run.finished();check(jobDone.get(),"delayed job finished");}
        finally{if(pool.get()!=null)pool.get().shutdownNow();}
    }
    static void unknown()throws Exception{
        var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        Run run=new Run(()->new Thread(()->{started.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}},"pool-40-thread-1").start());
        try{check(started.await(2,TimeUnit.SECONDS),"unknown child started");check(!run.done.await(100,TimeUnit.MILLISECONDS),"a misleading pool-like name is not enough to ignore work");}
        finally{release.countDown();run.finished();}
    }
}
