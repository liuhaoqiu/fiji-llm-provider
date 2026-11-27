/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.imagej.legacy.LegacyService;
import org.scijava.Priority;
import org.scijava.module.Module;
import org.scijava.module.ModuleInfo;
import org.scijava.module.ModuleItem;
import org.scijava.module.process.AbstractPreprocessorPlugin;
import org.scijava.module.process.PreprocessorPlugin;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Notices a Boolean command input that a macro wrote as something other than true or false.
 *
 * A macro variable set to true prints as 1, and SciJava reads the option text "1" as false
 * without saying so: usegpu=1 runs on the CPU. This only observes. It reads the same option
 * text ImageJ's own macro preprocessor reads, changes no input and no result, and keeps a
 * short note that a Fiji Chat run tool shows to the model. A macro run outside a chat
 * behaves exactly as it would without this extension.
 */
@Plugin(type = PreprocessorPlugin.class, priority = Priority.VERY_HIGH + 1)
public class MacroOptionCheck extends AbstractPreprocessorPlugin {
    private static final List<String> NOTES = new ArrayList<>();

    @Parameter(required = false) private LegacyService legacy;

    @Override public void process(final Module module) {
        try {
            if (legacy == null) return;
            var helper = legacy.getIJ1Helper();
            if (helper == null || !helper.isMacro()) return;
            for (String note : check(module.getInfo(), helper::getMacroParameter)) add(note);
        } catch (RuntimeException | LinkageError unavailable) {
            // Observation only: a command must run exactly as it would without this check.
        }
    }

    /** The notes for one command, given how its macro options are looked up by input name. */
    static List<String> check(ModuleInfo info, Function<String, String> option) {
        var notes = new ArrayList<String>();
        for (ModuleItem<?> input : info.inputs()) {
            if (input.getType() != Boolean.class && input.getType() != boolean.class) continue;
            String value = option.apply(input.getName());
            if (value == null || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) continue;
            String key = ij.Macro.trimKey(input.getName());
            String title = info.getTitle() == null ? info.getDelegateClassName() : info.getTitle();
            notes.add("In run(\"" + title + "\", ...) the option " + key + "=" + value + " was read as false. " +
                "A Boolean option must be the text true or false; a macro variable set to true prints as 1. " +
                "Write " + key + "=true to switch it on.");
        }
        return notes;
    }

    static void add(String note) {
        synchronized (NOTES) {
            if (!NOTES.contains(note) && NOTES.size() < 20) NOTES.add(note);
        }
    }

    /** Returns the notes collected so far and forgets them. */
    static List<String> drain() {
        synchronized (NOTES) {
            var copy = new ArrayList<>(NOTES);
            NOTES.clear();
            return copy;
        }
    }
}
