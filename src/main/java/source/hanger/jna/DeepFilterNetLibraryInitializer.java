package source.hanger.jna;

import java.io.File;

import com.sun.jna.Native;

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
            if (osName.contains("linux") && "amd64".equals(osArch)) {
                osArch = "x86_64";
            }
            String nativeLibPath = projectRoot + "lib" + File.separator + osName + File.separator + osArch;

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
