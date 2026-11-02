/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.command.DynamicCommand;
import org.scijava.prefs.PrefService;
import org.scijava.ui.UIService;
import sc.fiji.llm.commands.Fiji_Chat;
import sc.fiji.llm.provider.ProviderService;

/** Drives the installed, unmodified Fiji_Chat initialize/run methods in isolation. */
public final class StartupVerificationMain {
    public static void main(String[] args) throws Exception {
        Map<String,Object> values = new HashMap<>();
        PrefService prefs = proxy(PrefService.class, (p,m,a) -> {
            if (m.getName().equals("get")) return values.getOrDefault((String)a[1], a.length > 2 ? a[2] : null);
            if (m.getName().equals("getBoolean")) return values.getOrDefault((String)a[1],a[2]);
            if (m.getName().equals("put")) { values.put((String)a[1],a[2]); return null; }
            if (m.getName().equals("remove")) { values.remove((String)a[1]); return null; }
            return null;
        });
        var provider = new DeepSeekProvider() {
            @Override protected Path modelCacheFile() { return null; }
            @Override protected PrefService preferences() { return prefs; }
            @Override protected boolean ensureApiKeyAvailable() { return false; }
        };
        AtomicInteger launches = new AtomicInteger();
        AtomicInteger dialogs = new AtomicInteger();
        CommandService commands = proxy(CommandService.class, (p,m,a) -> {
            if (m.getName().equals("getCommand")) return new CommandInfo(Fiji_Chat.class);
            if (m.getName().equals("run")) { launches.incrementAndGet(); return null; }
            return null;
        });
        UIService ui = proxy(UIService.class, (p,m,a) -> {
            if (m.getName().equals("showDialog")) dialogs.incrementAndGet();
            return null;
        });
        // Reproduce 0.2.0's loop with an empty list and the persisted auto-run flag.
        var oldBehavior = new DeepSeekProvider() {
            @Override public List<String> getAvailableModels() { return List.of(); }
        };
        values.put(Fiji_Chat.LAST_CHAT_PROVIDER, "DeepSeek");
        values.put(Fiji_Chat.AUTO_RUN, true);
        var oldChat = command(oldBehavior, prefs, commands, ui);
        oldChat.initialize();
        check(oldChat.isInputResolved("model"), "old auto-run skips model chooser");
        oldChat.run();
        check(launches.get() == 1 && dialogs.get() == 1, "old behavior reopens itself after empty-list popup");
        check(Boolean.TRUE.equals(values.get(Fiji_Chat.AUTO_RUN)), "old behavior leaves loop flag enabled");

        launches.set(0); dialogs.set(0);
        var fixedChat = command(provider, prefs, commands, ui);
        fixedChat.initialize();
        check(!fixedChat.isInputResolved("model"), "fixed startup shows cancellable chooser");
        check(DeepSeekProvider.LOAD_MODELS.equals(fixedChat.getInput("model")), "setup action appears in chooser");
        check(!Boolean.TRUE.equals(values.get(Fiji_Chat.AUTO_RUN)), "auto-run disabled on empty catalog");
        fixedChat.run(); // Simulate canceling authentication in the setup action.
        check(fixedChat.isCanceled(), "authentication cancellation ends current command");
        check(launches.get() == 0 && dialogs.get() == 0, "no repeated dialog or self-relaunch");
        try {
            provider.createChatModel(DeepSeekProvider.LOAD_MODELS);
            throw new AssertionError("setup label reached model builder");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: real Fiji_Chat loop reproduced; setup, cancel and auto-run recovery verified");
    }

    private static Fiji_Chat command(DeepSeekProvider provider, PrefService prefs,
        CommandService commands, UIService ui) throws Exception {
        ProviderService providers = proxy(ProviderService.class, (p,m,a) -> {
            if (m.getName().equals("getInstances")) return List.of(provider);
            if (m.getName().equals("getProvider")) return provider;
            return null;
        });
        var command = new Fiji_Chat();
        set(command, DynamicCommand.class, "commandService", commands);
        set(command, Fiji_Chat.class, "commandService", commands);
        set(command, Fiji_Chat.class, "prefService", prefs);
        set(command, Fiji_Chat.class, "providerService", providers);
        set(command, Fiji_Chat.class, "uiService", ui);
        return command;
    }

    private static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
