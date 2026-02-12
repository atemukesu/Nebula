package com.atemukesu.nebula.client.util;

import com.atemukesu.nebula.client.bridge.IrisBridge;

public class IrisUtil {

    /**
     * Checks if Iris is installed.
     * Delegates to IrisBridge.
     * 
     * @return true if Iris is installed
     */
    public static boolean isIrisInstalled() {
        return IrisBridge.getInstance().isIrisInstalled();
    }

    /**
     * Checks if Iris rendering is active (shader pack in use).
     * Delegates to IrisBridge.
     * 
     * @return true if Iris is active
     */
    public static boolean isIrisRenderingActive() {
        return IrisBridge.getInstance().isIrisRenderingActive();
    }

    /**
     * Binds the Iris translucent framebuffer.
     * Delegates to IrisBridge which handles reflection strategies.
     */
    public static void bindIrisTranslucentFramebuffer() {
        IrisBridge.getInstance().bindTranslucentFramebuffer();
    }
}
