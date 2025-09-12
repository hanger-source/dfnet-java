package source.hanger.jna;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeepFilterNetLibraryInitializer {

    private static boolean initializedPath = false;
    private static DeepFilterNetNativeLib nativeLibInstance = null; // 用于存储 DeepFilterNetNativeLib 的实例
    private static File nativeLibTempDir; // 新增：用于存储解压本地库的临时目录

    public static synchronized void initializeNativeLibraryPath() {
        if (initializedPath) {
            return;
        }

        // 检查 jna.library.path 是否已手动设置
        String jnaLibraryPath = System.getProperty("jna.library.path");
        if (jnaLibraryPath != null && !jnaLibraryPath.isEmpty()) {
            log.info("DF_LOG: JNA library path already set manually: {}", jnaLibraryPath);
            initializedPath = true;
            return;
        }

        // 根据 OS 和架构推断本地库名称
        String libName = "df";
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String platformSpecificLibName = null;

        if (osName.contains("mac")) {
            osName = "macos";
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                osArch = "aarch64";
            } else if (osArch.contains("x86_64")) {
                osArch = "x86_64";
            }
            platformSpecificLibName = "lib" + libName + ".dylib";
        } else if (osName.contains("linux")) {
            osName = "linux";
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                osArch = "x86_64";
            } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                osArch = "aarch64";
            }
            platformSpecificLibName = "lib" + libName + ".so";
        } else if (osName.contains("windows")) {
            osName = "windows";
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                osArch = "x86_64";
            } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                osArch = "aarch64";
            }
            platformSpecificLibName = libName + ".dll";
        } else {
            log.warn("DF_WARN: 未知操作系统或架构: {} - {}. 无法自动设置 JNA 库路径。", osName, osArch);
            return;
        }

        String resourceLibPath = String.format("/lib/%s/%s/%s", osName, osArch, platformSpecificLibName);
        log.info("DF_LOG: 尝试从 JAR 资源路径加载本地库: {}", resourceLibPath);

        try {
            // 创建临时目录来解压本地库
            nativeLibTempDir = Files.createTempDirectory("df_native_lib").toFile();
            nativeLibTempDir.deleteOnExit(); // 确保 JVM 退出时删除目录

            File tempLibFile = new File(nativeLibTempDir, platformSpecificLibName);
            tempLibFile.deleteOnExit(); // 确保 JVM 退出时删除文件

            try (InputStream inputStream = DeepFilterNetLibraryInitializer.class.getResourceAsStream(resourceLibPath)) {
                if (inputStream == null) {
                    log.error("DF_ERROR: JAR 资源中未找到本地库: {}", resourceLibPath);
                    throw new FileNotFoundException("JAR 资源中未找到本地库: " + resourceLibPath);
                }
                Files.copy(inputStream, tempLibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("DF_INFO: 本地库已成功提取到临时文件: {}", tempLibFile.getAbsolutePath());
            }

            // 将临时目录添加到 jna.library.path
            String currentJnaPath = System.getProperty("jna.library.path", "");
            String newJnaPath;
            if (currentJnaPath.isEmpty()) {
                newJnaPath = nativeLibTempDir.getAbsolutePath();
            } else {
                newJnaPath = currentJnaPath + File.pathSeparator + nativeLibTempDir.getAbsolutePath();
            }
            System.setProperty("jna.library.path", newJnaPath);
            log.info("DF_LOG: JNA library path set to: {}", newJnaPath);

            initializedPath = true;
        } catch (IOException e) {
            log.error("DF_ERROR: 无法提取或设置 JNA 本地库路径: {}", e.getMessage(), e);
            // 在初始化失败时，尝试清理可能已创建的临时目录
            if (nativeLibTempDir != null && nativeLibTempDir.exists()) {
                deleteDirectory(nativeLibTempDir); // 递归删除目录
            }
        } catch (Exception e) {
            log.error("DF_ERROR: 初始化 JNA 本地库路径时发生意外错误: {}", e.getMessage(), e);
            // 在初始化失败时，尝试清理可能已创建的临时目录
            if (nativeLibTempDir != null && nativeLibTempDir.exists()) {
                deleteDirectory(nativeLibTempDir); // 递归删除目录
            }
        }
    }

    public static synchronized DeepFilterNetNativeLib getNativeLibraryInstance() {
        initializeNativeLibraryPath(); // 确保路径已经被初始化
        if (nativeLibInstance == null) {
            try {
                nativeLibInstance = Native.load("df", DeepFilterNetNativeLib.class);
            } catch (UnsatisfiedLinkError e) {
                log.error("DF_ERROR: 无法加载本地库 'df'。请确保 'libdf.dylib' (macOS) / 'libdf.so' (Linux) / 'df.dll' (Windows) "
                        +
                        "文件存在于 jna.library.path ({}) 或系统库路径中。错误信息: {}",
                    System.getProperty("jna.library.path", "未设置"), e.getMessage());
                throw e;
            }
        }
        return nativeLibInstance;
    }

    /**
     * 递归删除目录及其所有内容。
     *
     * @param directory 要删除的目录
     * @return 如果成功删除则返回 true，否则返回 false
     */
    private static boolean deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        return directory.delete();
    }

    /**
     * 在应用程序退出时调用，用于清理解压的本地库临时文件。
     */
    public static synchronized void releaseNativeLibrary() {
        if (nativeLibTempDir != null && nativeLibTempDir.exists()) {
            if (deleteDirectory(nativeLibTempDir)) {
                log.info("DF_INFO: 临时本地库目录已删除: {}", nativeLibTempDir.getAbsolutePath());
            } else {
                log.warn("DF_WARN: 无法删除临时本地库目录: {}", nativeLibTempDir.getAbsolutePath());
            }
        }
    }
}
