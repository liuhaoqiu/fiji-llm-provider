/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;
import java.util.*;
import java.lang.ref.WeakReference;
import com.google.gson.*;
import ij.*;

/** Object/ID observations, never title matching or ownership inference. */
final class MacroImageIds {
    final Map<Integer,WeakReference<ImagePlus>> before=new HashMap<>();
    MacroImageIds(){for(int id:ids()){var image=WindowManager.getImage(id);if(image!=null)before.put(id,new WeakReference<>(image));}}
    static int[] ids(){var ids=WindowManager.getIDList();return ids==null?new int[0]:ids;}
    JsonObject observe(){
        var out=new JsonObject();var images=new JsonArray();
        for(int id:ids()){var image=WindowManager.getImage(id);if(image!=null&&(!before.containsKey(id)||before.get(id).get()!=image))images.add(row(image));}
        out.add("new_images_observed",images);var active=WindowManager.getCurrentImage();if(active!=null)out.add("active_image_observed",row(active));
        out.addProperty("image_observation_note","Live ImageJ IDs observed since run start; closed outputs are absent and concurrent external activity may be included. Titles do not establish identity or ownership.");return out;
    }
    private static JsonObject row(ImagePlus im){var o=new JsonObject();o.addProperty("image_id",im.getID());o.addProperty("title",im.getTitle());return o;}
}
