import java.awt.Window;
import java.nio.file.Path;

/** Cocoa clips the opaque AWT surface; Java never enables per-pixel translucency. */
final class NativeWindowCorners {
    private static final boolean available;
    static {
        boolean loaded = false;
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            try {
                Path classes = Path.of(NativeWindowCorners.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
                System.load(classes.resolve("libCastCorners.dylib").toString());
                loaded = true;
            } catch (Exception | LinkageError error) {
                System.err.println("原生圆角不可用，保留直角窗口: " + error);
            }
        }
        available = loaded;
    }
    static void apply(Window window, String title, double radius) {
        if (available && window.isDisplayable()) setCorners(title, radius);
    }
    private static native void setCorners(String title, double radius);
}
