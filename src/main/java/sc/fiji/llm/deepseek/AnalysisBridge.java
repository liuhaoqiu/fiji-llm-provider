/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

/** Public, string-only entry points for ImageJ Macro call(). */
public final class AnalysisBridge {
    private AnalysisBridge(){}
    public static String extractChannel(String imageId,String channel){try{
        var result=ImageEvidence.extract(ij.WindowManager.getImage(Integer.parseInt(imageId)),Integer.parseInt(channel));
        UiAutomationTools.edt(()->{result.show();return null;});return Integer.toString(result.getID());
    }catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}}
    public static String measureLabels(String sourceId,String labelId,String channel){try{
        return ImageEvidence.deliver(ij.WindowManager.getImage(Integer.parseInt(sourceId)),ij.WindowManager.getImage(Integer.parseInt(labelId)),Integer.parseInt(channel),new ContextArchive().directory,!java.awt.GraphicsEnvironment.isHeadless()).toString();
    }catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}}
}
