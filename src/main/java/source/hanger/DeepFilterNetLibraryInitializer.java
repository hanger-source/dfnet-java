package source.hanger;

import com.sun.jna.Native;
import java.io.File;

public class DeepFilterNetLibraryInitializer {

    private static boolean initializedPath = false;
    private static DeepFilterNetNativeLib nativeLibInstance = null; // 用于存储 DeepFilterNetNativeLib 的实例

    public static synchronized void initializeNativeLibraryPath() {
        if (!initializedPath) {
            String projectRoot = new File(".").getAbsolutePath();
            if (projectRoot.endsWith(File.separator + ".")) {
                projectRoot = projectRoot.substring(0, projectRoot.length() - 2);
            } else if (projectRoot.endsWith(".")) {
                projectRoot = projectRoot.substring(0, projectRoot.length() - 1);
            }
            if (!projectRoot.endsWith(File.separator)) {
                projectRoot += File.separator;
            }

            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("mac")) {
                osName = "macos";
            }
            String osArch = System.getProperty("os.arch").toLowerCase();
            String nativeLibPath = projectRoot + "lib" + File.separator + osName + "-" + osArch;

            System.setProperty("jna.library.path", nativeLibPath);
            System.out.println("DF_LOG: JNA library path set from DeepFilterNetLibraryInitializer: " + nativeLibPath);
            initializedPath = true;
        }
    }

    public static synchronized DeepFilterNetNativeLib getNativeLibraryInstance() {
        initializeNativeLibraryPath(); // 确保路径已经被初始化
        if (nativeLibInstance == null) {
            nativeLibInstance = Native.load("df", DeepFilterNetNativeLib.class);
        }
        return nativeLibInstance;
    }
}
