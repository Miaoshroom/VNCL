// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import com.sun.glass.events.ViewEvent;
import com.sun.glass.ui.View;
import com.sun.javafx.stage.WindowHelper;
import com.sun.javafx.tk.quantum.WindowStage;
import com.sun.jna.Function;
import com.sun.jna.Pointer;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.macos.ObjectiveCRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Places the macOS window buttons over the launcher toolbar on existing JavaFX runtimes.
@NotNullByDefault
final class MacOSWindowDecoration {
    /// The AppKit style mask for content extending behind the title bar.
    private static final long FULL_SIZE_CONTENT_VIEW = 1L << 15;

    /// The AppKit masks for a title bar with close and minimize controls.
    private static final long TITLED_WINDOW = (1L << 0) | (1L << 1) | (1L << 2);

    /// The AppKit mask that allows a native window to resize and zoom.
    private static final long RESIZABLE_WINDOW = 1L << 3;

    /// The AppKit collection modes replaced to disallow full screen and full-screen tiling.
    private static final long FULL_SCREEN_ALLOWED = (1L << 7) | (1L << 8) | (1L << 11);

    /// The AppKit collection modes that disallow full screen and full-screen tiling.
    private static final long FULL_SCREEN_DISABLED = (1L << 9) | (1L << 12);

    /// Registers Objective-C selectors and resolves AppKit classes.
    private final ObjectiveCRuntime objc;

    /// Invokes Objective-C methods with their native argument types.
    private final Function send;

    /// Resynchronizes JavaFX's cached scene offset after AppKit changes it.
    private final MethodHandle notifyView;

    /// Brings Glass's cached zoom state into sync without resizing the AppKit window.
    private final MethodHandle notifyResize;

    /// Resolves the native calls and the Glass view notification before enabling this decoration.
    private MacOSWindowDecoration(ObjectiveCRuntime objc) throws ReflectiveOperationException {
        this.objc = objc;
        send = Function.getFunction("objc", "objc_msgSend");
        notifyView = MethodHandles.privateLookupIn(View.class, MethodHandles.lookup())
                .findVirtual(View.class, "notifyView", MethodType.methodType(void.class, int.class));
        notifyResize = MethodHandles.privateLookupIn(com.sun.glass.ui.Window.class, MethodHandles.lookup())
                .findVirtual(com.sun.glass.ui.Window.class, "notifyResize",
                        MethodType.methodType(void.class, int.class, int.class, int.class));
    }

    /// Returns the macOS integration if available and allowed by the native-decoration preference.
    static @Nullable MacOSWindowDecoration create() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.MACOS) {
            return null;
        }
        @Nullable String preference = System.getProperty("hmcl.nativeDecoration", System.getenv("HMCL_NATIVE_DECORATION"));
        @Nullable ObjectiveCRuntime objc = ObjectiveCRuntime.INSTANCE;
        if (objc == null || (preference != null && "false".equalsIgnoreCase(preference.trim()))) {
            return null;
        }
        try {
            return new MacOSWindowDecoration(objc);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warning("Cannot initialize the macOS title bar", e);
            return null;
        }
    }

    /// Configures the current AppKit peer after the JavaFX stage is shown.
    void configure(Stage stage) {
        try {
            if (!(WindowHelper.getPeer(stage) instanceof WindowStage peer)) {
                return;
            }
            @Nullable com.sun.glass.ui.Window glass = peer.getPlatformWindow();
            if (glass == null) {
                return;
            }

            long handle = glass.getNativeWindow();
            @Nullable View view = glass.getView();
            if (handle == 0 || view == null) {
                return;
            }

            Pointer window = new Pointer(handle);
            boolean transparent = stage.getStyle() == StageStyle.TRANSPARENT;
            if (transparent) {
                long style = send.invokeLong(new Object[]{window, objc.sel_registerName("styleMask")});
                long resizable = stage.isResizable() ? RESIZABLE_WINDOW : 0;
                set(window, "setStyleMask:", style | TITLED_WINDOW | FULL_SIZE_CONTENT_VIEW | resizable);
                set(window, "setOpaque:", (byte) 0);
            }
            long behavior = send.invokeLong(new Object[]{window, objc.sel_registerName("collectionBehavior")});
            set(window, "setCollectionBehavior:", (behavior & ~FULL_SCREEN_ALLOWED) | FULL_SCREEN_DISABLED);

            // An empty compact toolbar lets AppKit center the buttons in the 40-point VNCL toolbar.
            set(window, "setToolbarStyle:", 4L);
            Pointer toolbar = Objects.requireNonNull(objc.objc_msgSend(objc.objc_getClass("NSToolbar"),
                    objc.sel_registerName("new")));
            set(window, "setToolbar:", toolbar);
            objc.objc_msgSend(toolbar, objc.sel_registerName("release"));
            set(window, "setTitlebarAppearsTransparent:", (byte) 1);
            set(window, "setTitleVisibility:", 1L);
            set(window, "setTitlebarSeparatorStyle:", 1L);
            long style = send.invokeLong(new Object[]{window, objc.sel_registerName("styleMask")});
            set(window, "setStyleMask:", style | FULL_SIZE_CONTENT_VIEW);

            // Glass does not always receive a move event when the native title-bar offset changes.
            notifyView.invokeExact(view, ViewEvent.MOVE);

            if (transparent) {
                set(window, "setHasShadow:", (byte) 1);
                // Glass can retain a false zoom notification from the original borderless peer.
                if (stage.isMaximized() && (byte) send.invoke(byte.class,
                        new Object[]{window, objc.sel_registerName("isZoomed")}) == 0) {
                    notifyResize.invokeExact(glass, com.sun.glass.events.WindowEvent.RESTORE,
                            Math.round(glass.getWidth() / glass.getPlatformScaleX()),
                            Math.round(glass.getHeight() / glass.getPlatformScaleY()));
                }
                stage.getProperties().put(MacOSWindowDecoration.class, Boolean.TRUE);
            }
        } catch (Throwable e) {
            LOG.warning("Cannot configure the macOS title bar", e);
        }
    }

    /// Sets an AppKit property with a single Objective-C argument.
    private void set(Pointer window, String selector, Object value) {
        send.invokeVoid(new Object[]{window, objc.sel_registerName(selector), value});
    }
}
