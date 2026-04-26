/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 */

package cn.tohsaka.factory.zstdnet.proxy;

import com.github.luben.zstd.util.Native;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class AndroidZstdNativeLoader {
    private static final String ZSTD_JNI_VERSION = "1.5.7-7";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private AndroidZstdNativeLoader() {
    }

    static void prepare(Logger logger) {
        if (Native.isLoaded() || !isAndroidLikeRuntime()) {
            return;
        }
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }

        String abi = androidAbi();
        if (abi == null) {
            logger.warn(
                "zstdnet: Android/FCL runtime detected, but os.arch={} is not mapped to a bundled Android zstd-jni ABI",
                System.getProperty("os.arch")
            );
            return;
        }

        String resource = "/zstdnet/android/" + abi + "/libzstd-jni-" + ZSTD_JNI_VERSION + ".so";
        try {
            File extracted = extract(resource, abi);
            System.setProperty("ZstdNativePath", extracted.getAbsolutePath());
            Native.load();
            logger.info("zstdnet: loaded Android zstd-jni native library for {} via zstd-jni loader from bundled resource {}", abi, resource);
        } catch (Throwable e) {
            logger.warn(
                "zstdnet: failed to preload Android zstd-jni native library from {}; zstd-jni default loader will be used: {}",
                resource,
                e.toString(),
                e
            );
        }
    }

    private static boolean isAndroidLikeRuntime() {
        String osVersion = lower(System.getProperty("os.version"));
        String vmName = lower(System.getProperty("java.vm.name"));
        return osVersion.contains("android")
            || System.getenv("POJAV_LAUNCHER") != null
            || System.getenv("FCL_VERSION") != null
            || vmName.contains("android");
    }

    private static String androidAbi() {
        String arch = lower(System.getProperty("os.arch"));
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64-v8a";
        }
        if (arch.startsWith("arm")) {
            return "armeabi-v7a";
        }
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return "x86_64";
        }
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            return "x86";
        }
        return null;
    }

    private static File extract(String resource, String abi) throws IOException {
        try (InputStream in = AndroidZstdNativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }

            File temp = File.createTempFile("zstdnet-android-zstd-jni-" + abi + "-", ".so");
            temp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            return temp;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
