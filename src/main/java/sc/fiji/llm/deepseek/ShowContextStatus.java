/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import javax.swing.*;
import java.awt.event.*;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Help>Assistants>LLM Context Status...")
public final class ShowContextStatus implements Command {
    @Override public void run() {
        SwingUtilities.invokeLater(() -> {
            var frame = new JFrame("Fiji LLM Context Status");
            var text = new JTextArea(ContextMonitor.status(), 16, 80);
            text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
            frame.add(new JScrollPane(text));
            var timer = new Timer(1000, event -> text.setText(ContextMonitor.status()));
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { timer.stop(); }
            });
            frame.pack(); frame.setLocationByPlatform(true); frame.setVisible(true); timer.start();
        });
    }
}
