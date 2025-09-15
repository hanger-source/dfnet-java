package source.hanger.jna;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.AgentRunner;
import source.hanger.processor.agent.DeepFilterNetListenerAgent;

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

        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        PlatformInfo platformInfo = getPlatformInfo(osName, osArch);

        if (platformInfo == null) {
            log.warn("DF_WARN: 未知操作系统或架构: {} - {}. 无法自动设置 JNA 库路径。", osName, osArch);
            return;
        }

        // --- 优先尝试在文件系统上查找原生库 ---
        try {
            File projectRoot = new File(System.getProperty("user.dir"));
            File fileSystemLibDir = new File(projectRoot,
                String.format("lib%s%s%s%s", File.separator, platformInfo.standardizedOsName, File.separator,
                    platformInfo.standardizedOsArch));
            File fileSystemLibFile = new File(fileSystemLibDir, platformInfo.platformSpecificLibName);

            if (fileSystemLibFile.exists() && fileSystemLibFile.isFile()) {
                String libAbsolutePath = fileSystemLibFile.getParentFile().getAbsolutePath();
                String currentJnaPath = System.getProperty("jna.library.path", "");
                String newJnaPath;
                if (currentJnaPath.isEmpty()) {
                    newJnaPath = libAbsolutePath;
                } else {
                    newJnaPath = currentJnaPath + File.pathSeparator + libAbsolutePath;
                }
                System.setProperty("jna.library.path", newJnaPath);
                log.info("DF_LOG: 已在文件系统上找到并设置 JNA 库路径: {}", newJnaPath);
                initializedPath = true;
                return;
            } else {
                log.info("DF_LOG: 未在文件系统上找到本地库: {}", fileSystemLibFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("DF_ERROR: 在文件系统上查找本地库时发生错误: {}", e.getMessage(), e);
        }

        // --- 回退到 JAR 资源提取 (现有逻辑) ---
        String resourceLibPath = String.format("/lib/%s/%s/%s", platformInfo.standardizedOsName,
            platformInfo.standardizedOsArch, platformInfo.platformSpecificLibName);
        log.info("DF_LOG: 尝试从 JAR 资源路径加载本地库: {}", resourceLibPath);

        try {
            nativeLibTempDir = Files.createTempDirectory("df_native_lib").toFile();
            nativeLibTempDir.deleteOnExit();

            File tempLibFile = new File(nativeLibTempDir, platformInfo.platformSpecificLibName);
            tempLibFile.deleteOnExit();

            try (InputStream inputStream = DeepFilterNetLibraryInitializer.class.getResourceAsStream(resourceLibPath)) {
                if (inputStream == null) {
                    log.error("DF_ERROR: JAR 资源中未找到本地库: {}", resourceLibPath);
                    throw new FileNotFoundException("JAR 资源中未找到本地库: " + resourceLibPath);
                }
                Files.copy(inputStream, tempLibFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("DF_INFO: 本地库已成功提取到临时文件: {}", tempLibFile.getAbsolutePath());
            }

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
            if (nativeLibTempDir != null && nativeLibTempDir.exists()) {
                deleteDirectory(nativeLibTempDir);
            }
        } catch (Exception e) {
            log.error("DF_ERROR: 初始化 JNA 本地库路径时发生意外错误: {}", e.getMessage(), e);
            if (nativeLibTempDir != null && nativeLibTempDir.exists()) {
                deleteDirectory(nativeLibTempDir);
            }
        }

        initializedPath = true;
    }

    public static synchronized DeepFilterNetNativeLib getNativeLibraryInstance() {
        initializeNativeLibraryPath();
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

    // 重命名并修改 getPlatformSpecificLibName 方法
    private static PlatformInfo getPlatformInfo(String osName, String osArch) {
        String libName = "df";
        String standardizedOsName = osName;
        String standardizedOsArch = osArch;
        String platformSpecificLibName = null;

        if (osName.contains("mac")) {
            standardizedOsName = "macos";
            if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                standardizedOsArch = "aarch64";
            } else if (osArch.contains("x86_64")) {
                standardizedOsArch = "x86_64";
            }
            platformSpecificLibName = "lib" + libName + ".dylib";
        } else if (osName.contains("linux")) {
            standardizedOsName = "linux";
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                standardizedOsArch = "x86_64";
            } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                standardizedOsArch = "aarch64";
            }
            platformSpecificLibName = "lib" + libName + ".so";
        } else if (osName.contains("windows")) {
            standardizedOsName = "windows";
            if (osArch.contains("amd64") || osArch.contains("x86_64")) {
                standardizedOsArch = "x86_64";
            }
            platformSpecificLibName = libName + ".dll";
        }

        if (platformSpecificLibName == null) {
            return null; // 无法确定平台特定库名
        }
        return new PlatformInfo(standardizedOsName, standardizedOsArch, platformSpecificLibName);
    }

    private static void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }

    private record PlatformInfo(String standardizedOsName, String standardizedOsArch, String platformSpecificLibName) {
    }
}
