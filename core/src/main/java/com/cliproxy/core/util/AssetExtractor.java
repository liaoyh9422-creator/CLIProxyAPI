package com.cliproxy.core.util;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 资源释放工具类：
 * 提供从 assets 目录解压单文件、归档 tar 包以及递归释放目录的能力。
 */
public class AssetExtractor {

    /** 释放单个 asset 文件至目标路径 */
    public static void extractAsset(Context context, String name, File target) throws IOException {
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        try (InputStream is = context.getAssets().open(name);
             OutputStream os = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    /** 释放压缩包并通过 tar 命令行解压到目标目录 */
    public static void extractAssetTar(Context context, String assetName, File destDir) throws IOException {
        destDir.mkdirs();
        File tarFile = new File(destDir, ".tmp_extract.tar.gz");
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(tarFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
        Process tarProc = new ProcessBuilder("tar", "xzf", tarFile.getAbsolutePath())
                .directory(destDir)
                .redirectErrorStream(true)
                .start();
        try {
            tarProc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tarFile.delete();
    }

    /** 递归释放 assets 子目录到文件系统 */
    public static void extractAssetDir(Context context, String assetPath, File outDir) throws IOException {
        AssetManager am = context.getAssets();
        String[] children = am.list(assetPath);
        if (children == null) return;
        if (children.length == 0) {
            File outFile = new File(outDir.getParentFile(), outDir.getName());
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            try (InputStream is = am.open(assetPath);
                 OutputStream os = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            return;
        }
        for (String child : children) {
            String childPath = assetPath + "/" + child;
            String[] subChildren = am.list(childPath);
            if (subChildren != null && subChildren.length > 0) {
                File subDir = new File(outDir, child);
                subDir.mkdirs();
                extractAssetDir(context, childPath, subDir);
            } else {
                File outFile = new File(outDir, child);
                if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
                try (InputStream is = am.open(childPath);
                     OutputStream os = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
        }
    }

    /** 直接解压外部 tar 文件 */
    public static void extractTarFile(File tarFile, File destDir) throws Exception {
        destDir.mkdirs();
        ProcessBuilder pb = new ProcessBuilder("tar", "xf", tarFile.getAbsolutePath())
                .directory(destDir)
                .redirectErrorStream(true);
        Process p = pb.start();
        byte[] buf = new byte[4096];
        while (p.getInputStream().read(buf) != -1) { }
        p.waitFor();
    }
}
