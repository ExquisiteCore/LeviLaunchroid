package org.levimc.launcher.core.minecraft;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.jetbrains.annotations.NotNull;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.util.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MinecraftLauncher {
    private final Context context;
    private final ClassLoader classLoader;
    public static final String MC_PACKAGE_NAME = "com.mojang.minecraftpe";
    private static final String LAUNCHER_DEX_NAME = "launcher.dex";
    public native void nativeSetModPath(String modsDir, String configPath);
    public static String abiToSystemLibDir(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("armeabi-v7a".equals(abi)) return "arm";
        return abi;
    }
    void copyDirectoryRecursively(File src, File dst) throws IOException {
        if (!dst.exists()) dst.mkdirs();
        for (File f : src.listFiles()) {
            File destF = new File(dst, f.getName());
            if (f.isDirectory()) copyDirectoryRecursively(f, destF);
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.copy(f.toPath(), destF.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public ApplicationInfo createFakeApplicationInfo(File versionDir, String packageName) throws IOException {
        ApplicationInfo fakeInfo = new ApplicationInfo();
        File apkFile = new File(versionDir, "base.apk");
        fakeInfo.sourceDir = apkFile.getAbsolutePath();
        fakeInfo.publicSourceDir = fakeInfo.sourceDir;

        String systemAbi = abiToSystemLibDir(Build.SUPPORTED_ABIS[0]);
        File srcLibDir = new File(versionDir, "lib/" + systemAbi);
        File dstLibDir = new File(context.getCacheDir(), "mc_libs/" + systemAbi);
        copyDirectoryRecursively(srcLibDir, dstLibDir);

        fakeInfo.nativeLibraryDir = dstLibDir.getAbsolutePath();

        fakeInfo.packageName = packageName;
        fakeInfo.dataDir = versionDir.getAbsolutePath();

        File splitsFolder = new File(versionDir, "splits");
        if (splitsFolder.exists() && splitsFolder.isDirectory()) {
            File[] splits = splitsFolder.listFiles();
            if (splits != null) {
                ArrayList<String> splitPathList = new ArrayList<>();
                for (File f : splits) {
                    if (f.isFile() && f.getName().endsWith(".apk")) {
                        splitPathList.add(f.getAbsolutePath());
                    }
                }
                if (!splitPathList.isEmpty()) {
                    fakeInfo.splitSourceDirs = splitPathList.toArray(new String[0]);
                }
            }
        }
        return fakeInfo;
    }

    public MinecraftLauncher(Context context, ClassLoader classLoader) {
        this.context = context;
        this.classLoader = classLoader;
    }

    public void launch(Intent sourceIntent, GameVersion version) {
        try {
            if (version == null) return;
            ApplicationInfo mcInfo = version.isInstalled ?
                    getApplicationInfo(version.packageName) :
                    createFakeApplicationInfo(version.versionDir, MC_PACKAGE_NAME);
            if (version.isInstalled) {
                File dexCacheDir = createCacheDexDir();
                cleanCacheDirectory(dexCacheDir);
                Object pathList = getPathList(classLoader);
                processDexFiles(mcInfo, dexCacheDir, pathList);
                injectNativeLibraries(mcInfo, pathList);
                launchMinecraftActivity(mcInfo, sourceIntent);
            } else {
                File dexCacheDir = createCacheDexDir();
                cleanCacheDirectory(dexCacheDir);
                Object pathList = getPathList(classLoader);
                processDexFiles(mcInfo, dexCacheDir, pathList);
                injectNativeLibraries(mcInfo, pathList);
                Logger.get().info(version.modsDir.getAbsolutePath());
                nativeSetModPath(version.modsDir.getAbsolutePath(),version.modsDir.getAbsolutePath() + "/mods_config.json");
                launchMinecraftActivity(mcInfo, sourceIntent);
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private File createCacheDexDir() {
        File dexCacheDir = new File(context.getCodeCacheDir(), "dex");
        if (!dexCacheDir.exists() && !dexCacheDir.mkdirs()) {
            throw new RuntimeException("Unable to create dex cache directory.");
        }
        return dexCacheDir;
    }

    @SuppressLint("SetTextI18n")
    private void cleanCacheDirectory(File cacheDir) {
        if (cacheDir.isDirectory()) {
            updateListenerText("Cleaning cache directory...");
            for (File file : Objects.requireNonNull(cacheDir.listFiles())) {
                if (file.delete()) {
                    updateListenerText("Deleted: " + file.getName());
                }
            }
        } else {
            updateListenerText("Cache directory doesn't exist or is empty.");
        }
    }

    private ApplicationInfo getApplicationInfo(String packageName) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
    }

    private Object getPathList(@NotNull ClassLoader loader) throws ReflectiveOperationException {
        Field field = Objects.requireNonNull(loader.getClass().getSuperclass()).getDeclaredField("pathList");
        field.setAccessible(true);
        return field.get(loader);
    }

    private Method findMethod(@NotNull Object target, @NotNull String methodName, @NotNull Class<?>... params)
            throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(methodName, params);
        method.setAccessible(true);
        return method;
    }

    private void processDexFiles(ApplicationInfo mcInfo, File dexCacheDir, Object pathList)
            throws IOException, ReflectiveOperationException {

        Method addDexPathMethod = findMethod(pathList, "addDexPath", String.class, File.class);

        File launcherDexFile = new File(dexCacheDir, LAUNCHER_DEX_NAME);
        copyAssetToFile(LAUNCHER_DEX_NAME, launcherDexFile);
        addDexFileToPathList(launcherDexFile, addDexPathMethod, pathList);

        try (ZipFile mcApkZip = new ZipFile(mcInfo.sourceDir)) {
            for (int i = 10; i >= 0; i--) {
                String dexName = "classes" + (i == 0 ? "" : i) + ".dex";
                ZipEntry dexEntry = mcApkZip.getEntry(dexName);
                if (dexEntry != null) {
                    File mcDexFile = new File(dexCacheDir, dexName);
                    copyFile(mcApkZip.getInputStream(dexEntry), mcDexFile);
                    addDexFileToPathList(mcDexFile, addDexPathMethod, pathList);
                }
            }
        }
    }

    private void addDexFileToPathList(File dexFile, Method addDexPathMethod, Object pathList)
            throws ReflectiveOperationException {
        if (dexFile.setReadOnly()) {
            addDexPathMethod.invoke(pathList, dexFile.getAbsolutePath(), null);
            updateListenerText("Loaded dex: " + dexFile.getName());
        }
    }

    private void injectNativeLibraries(ApplicationInfo mcInfo, Object pathList) throws ReflectiveOperationException {
        try {
            final File newLibDir = new File(mcInfo.nativeLibraryDir);
            Logger.get().info(newLibDir.getAbsolutePath());

            Field nativeLibraryDirectoriesField = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
            nativeLibraryDirectoriesField.setAccessible(true);

            Collection<File> currentDirs = (Collection<File>) nativeLibraryDirectoriesField.get(pathList);
            if (currentDirs == null) {
                currentDirs = new ArrayList<>();
            }

            List<File> libDirs = new ArrayList<>(currentDirs);

            Iterator<File> it = libDirs.iterator();
            while (it.hasNext()) {
                File libDir = it.next();
                if (newLibDir.equals(libDir)) {
                    it.remove();
                    break;
                }
            }
            libDirs.add(0, newLibDir);
            nativeLibraryDirectoriesField.set(pathList, libDirs);

            Field nativeLibraryPathElementsField = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
            nativeLibraryPathElementsField.setAccessible(true);

            Object[] elements;

            if (Build.VERSION.SDK_INT >= 25) {
                Method makePathElements = pathList.getClass().getDeclaredMethod("makePathElements", List.class);
                makePathElements.setAccessible(true);

                Field systemNativeLibDirsField = pathList.getClass().getDeclaredField("systemNativeLibraryDirectories");
                systemNativeLibDirsField.setAccessible(true);
                List<File> systemLibDirs = (List<File>) systemNativeLibDirsField.get(pathList);
                if (systemLibDirs != null) {
                    libDirs.addAll(systemLibDirs);
                }

                elements = (Object[]) makePathElements.invoke(pathList, libDirs);
            } else {
                Method makePathElements = pathList.getClass().getDeclaredMethod("makePathElements", List.class, File.class, List.class);
                makePathElements.setAccessible(true);

                Field systemNativeLibDirsField = pathList.getClass().getDeclaredField("systemNativeLibraryDirectories");
                systemNativeLibDirsField.setAccessible(true);
                List<File> systemLibDirs = (List<File>) systemNativeLibDirsField.get(pathList);
                if (systemLibDirs != null) {
                    libDirs.addAll(systemLibDirs);
                }
                ArrayList<Throwable> suppressedExceptions = new ArrayList<>();
                elements = (Object[]) makePathElements.invoke(pathList, libDirs, null, suppressedExceptions);
            }
            nativeLibraryPathElementsField.set(pathList, elements);


        } catch (NoSuchFieldException | NoSuchMethodException e) {
            throw new ReflectiveOperationException("Unable to inject native libraries", e);
        }
    }

    private void launchMinecraftActivity(ApplicationInfo mcInfo, Intent sourceIntent) {
        new Thread(() -> {
            Class<?> launcherClass = null;
            try {
                launcherClass = classLoader.loadClass("com.mojang.minecraftpe.Launcher");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            Intent mcActivity = sourceIntent.setClass(context, launcherClass);
        mcActivity.putExtra("MC_SRC", mcInfo.sourceDir);
        if (mcInfo.splitSourceDirs != null) {
            mcActivity.putExtra("MC_SPLIT_SRC", new ArrayList<>(Arrays.asList(mcInfo.splitSourceDirs)));
        }

        if (context instanceof Activity) {
            context.startActivity(mcActivity);
            ((Activity) context).finish();
        } else {
            mcActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mcActivity);
        }
        }).start();
    }

    private void copyAssetToFile(String assetName, @NotNull File destFile) throws IOException {
        try (InputStream is = context.getAssets().open(assetName)) {
            copyFile(is, destFile);
        }
    }

    private void copyFile(InputStream inputStream, @NotNull File destFile) throws IOException {
        if (!destFile.getParentFile().exists() && !destFile.getParentFile().mkdirs()) {
            throw new IOException("Unable to create parent directories: " + destFile.getParentFile());
        }
        try (BufferedInputStream in = new BufferedInputStream(inputStream);
             BufferedOutputStream out = new BufferedOutputStream(
                     Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                             Files.newOutputStream(destFile.toPath()) :
                             new FileOutputStream(destFile))
        ) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }
        updateListenerText("Copied file: " + destFile.getName());
    }

    private void updateListenerText(String message) {
        Logger.get().info(message);
    }
}