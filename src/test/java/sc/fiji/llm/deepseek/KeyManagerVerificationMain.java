/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.command.DynamicCommand;
import org.scijava.module.ModuleService;
import org.scijava.plugin.Parameter;
import sc.fiji.llm.commands.Manage_Keys;

/** Real SciJava dispatch and futures; no CommandService/ModuleService mocks. */
public final class KeyManagerVerificationMain {
    private static CountDownLatch entered;
    private static CountDownLatch release;
    private static CountDownLatch finished;
    private static final AtomicBoolean keyAvailable = new AtomicBoolean();
    private static final AtomicInteger runs = new AtomicInteger();
    private static volatile boolean cancel;
    private static String expectedProvider = "DeepSeek";

    public static class SimulatedKeyManager extends DynamicCommand {
        @Parameter(persist = false) private String provider;
        @Parameter(persist = false) private boolean startChatbot;

        @Override public void run() {
            check(expectedProvider.equals(provider) && !startChatbot, "key-manager inputs preserved");
            runs.incrementAndGet();
            entered.countDown();
            try {
                check(release.await(5, TimeUnit.SECONDS), "test gate timeout");
                if (cancel) cancel("Simulated user cancellation");
                else keyAvailable.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancel("Interrupted");
            } finally { finished.countDown(); }
        }
    }

    private static void reset(boolean shouldCancel) {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        finished = new CountDownLatch(1);
        keyAvailable.set(false);
        cancel = shouldCancel;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        try (Context context = new Context(CommandService.class, ModuleService.class)) {
            // This is a headless dispatch test, not a GUI test. Keep the real
            // SciJava core processors; omit optional Fiji/GUI processors whose
            // application services are deliberately absent from this context.
            var plugins = context.service(org.scijava.plugin.PluginService.class);
            plugins.removePlugins(plugins.getPluginsOfType(org.scijava.module.process.PreprocessorPlugin.class)
                .stream().filter(p -> !p.getClassName().startsWith("org.scijava.module.process.")).toList());
            plugins.removePlugins(plugins.getPluginsOfType(org.scijava.module.process.PostprocessorPlugin.class)
                .stream().filter(p -> !p.getClassName().startsWith("org.scijava.module.process.")).toList());
            var commands = context.service(CommandService.class);
            var modules = context.service(ModuleService.class);
            check(commands.getClass().getName().equals("org.scijava.command.DefaultCommandService"),
                "real command service");
            check(modules.getClass().getName().equals("org.scijava.module.DefaultModuleService"),
                "real module service");
            check(DynamicCommand.class.isAssignableFrom(Manage_Keys.class), "real key manager is DynamicCommand");
            check(commands.getCommand(Manage_Keys.class) != null, "real key manager command is registered");

            reset(false);
            var brokenFuture = commands.run(SimulatedKeyManager.class, true,
                "provider", "DeepSeek", "startChatbot", false);
            check(brokenFuture == null, "reproduced CommandService's null future");
            check(entered.await(5, TimeUnit.SECONDS), "command still starts despite null future");
            release.countDown();
            check(finished.await(5, TimeUnit.SECONDS), "reproduction command finishes");

            var refreshes = new AtomicInteger();
            var provider = new DeepSeekProvider() {
                @Override public String getName() { return expectedProvider; }
                @Override public Context getContext() { return context; }
                @Override protected CommandInfo keyManagerCommand() {
                    return new CommandInfo(SimulatedKeyManager.class);
                }
                @Override protected String apiKey() {
                    if (!keyAvailable.get()) throw new IllegalStateException("Synthetic locked vault");
                    return "synthetic-key-not-used-for-network";
                }
                @Override public java.util.List<String> refreshAvailableModels() {
                    check(keyAvailable.get(), "refresh must occur after authentication completes");
                    refreshes.incrementAndGet();
                    return java.util.List.of("synthetic-model");
                }
                @Override public String getModelCatalogStatus() { return "Synthetic refresh succeeded"; }
            };
            var executor = Executors.newSingleThreadExecutor();
            try {
                reset(false);
                var unlocking = executor.submit(provider::ensureApiKeyAvailable);
                check(entered.await(5, TimeUnit.SECONDS), "native module service started key manager");
                check(!unlocking.isDone(), "provider waits while authentication is pending");
                check(!keyAvailable.get(), "no premature successful authentication");
                release.countDown();
                check(unlocking.get(5, TimeUnit.SECONDS), "provider continues after actual module completes");
                int previousRuns = runs.get();
                check(provider.ensureApiKeyAvailable() && runs.get() == previousRuns,
                    "already-unlocked key skips the prompt");

                reset(true);
                var canceled = executor.submit(provider::ensureApiKeyAvailable);
                check(entered.await(5, TimeUnit.SECONDS), "cancel case started");
                release.countDown();
                check(!canceled.get(5, TimeUnit.SECONDS), "module cancellation returns false without error");

                var displayed = new AtomicInteger();
                var refresh = new RefreshLLMModels();
                var providers = java.lang.reflect.Proxy.newProxyInstance(
                    sc.fiji.llm.provider.ProviderService.class.getClassLoader(),
                    new Class<?>[]{sc.fiji.llm.provider.ProviderService.class},
                    (p,m,a) -> {
                        if (!m.getName().equals("getProvider")) return null;
                        check(expectedProvider.equals(a[0]), "refresh selects the requested service");
                        return provider;
                    });
                var ui = java.lang.reflect.Proxy.newProxyInstance(org.scijava.ui.UIService.class.getClassLoader(),
                    new Class<?>[]{org.scijava.ui.UIService.class}, (p,m,a) -> {
                        if (m.getName().equals("showDialog")) displayed.incrementAndGet();
                        return null;
                    });
                var providerField = RefreshLLMModels.class.getDeclaredField("providers");
                providerField.setAccessible(true); providerField.set(refresh, providers);
                var uiField = RefreshLLMModels.class.getDeclaredField("ui");
                uiField.setAccessible(true); uiField.set(refresh, ui);
                reset(false);
                var refreshing = executor.submit(refresh::run);
                check(entered.await(5, TimeUnit.SECONDS), "actual refresh command starts authentication");
                check(!refreshing.isDone() && refreshes.get() == 0 && displayed.get() == 0,
                    "actual refresh command waits without premature fetch or dialog");
                release.countDown(); refreshing.get(5, TimeUnit.SECONDS);
                check(refreshes.get() == 1 && displayed.get() == 1, "refresh continues once after unlock");
                reset(true);
                var cancelRefresh = executor.submit(refresh::run);
                check(entered.await(5, TimeUnit.SECONDS), "refresh cancellation starts");
                release.countDown(); cancelRefresh.get(5, TimeUnit.SECONDS);
                check(refreshes.get() == 1 && displayed.get() == 1, "canceling unlock does not refresh or show success");

                var selection = RefreshLLMModels.class.getDeclaredField("provider");
                selection.setAccessible(true);
                for (String name : java.util.List.of("SiliconFlow", "Volcengine", "NVIDIA")) {
                    expectedProvider = name;
                    selection.set(refresh, name);
                    int before = refreshes.get();
                    reset(false);
                    var addedService = executor.submit(refresh::run);
                    check(entered.await(5, TimeUnit.SECONDS), name + " unlock started");
                    check(!addedService.isDone() && refreshes.get() == before, name + " waits for key manager");
                    release.countDown(); addedService.get(5, TimeUnit.SECONDS);
                    check(refreshes.get() == before + 1, name + " refreshes after unlock");
                }
                expectedProvider = "DeepSeek";

                reset(false);
                var interrupted = executor.submit(() -> {
                    Thread.currentThread().interrupt();
                    boolean result = provider.ensureApiKeyAvailable();
                    boolean flag = Thread.currentThread().isInterrupted();
                    Thread.interrupted();
                    return !result && flag;
                });
                check(interrupted.get(5, TimeUnit.SECONDS), "interruption exits and preserves interrupt status");
                release.countDown();
                // A dispatched module may still complete after its waiting caller is interrupted.
                if (entered.await(1, TimeUnit.SECONDS)) finished.await(5, TimeUnit.SECONDS);
            } finally { release.countDown(); executor.shutdownNow(); }
            System.out.println("PASS: real SciJava null-future reproduction, module waiting, four-service Refresh sequencing, cancellation and interruption");
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
