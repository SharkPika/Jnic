package cn.sky.jnic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class JNICLoader {

    private static boolean loaded = false;

    public static void load(String libName, Class<?> clazz) {
        if (!loaded) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                String arch = System.getProperty("os.arch").toLowerCase();
                String platform = "unknown";
                String ext = ".so";

                if (os.contains("win")) {
                    platform = "windows";
                    ext = ".dll";
                } else if (os.contains("mac")) {
                    platform = "macos";
                    ext = ".dylib";
                } else if (os.contains("linux") || os.contains("android")) {
                    platform = "linux-gnu";
                    if (System.getProperty("java.vendor", "").toLowerCase().contains("android")) {
                        platform = "android";
                    }
                }

                if (arch.contains("64") && !arch.contains("aarch64")) {
                    arch = "x86_64";
                } else if (arch.equals("aarch64")) {
                    // Keep aarch64
                } else if (arch.contains("arm")) {
                    arch = "arm";
                } else {
                    arch = "x86";
                }

                String targetName = "lib" + libName + "_" + arch + "-" + platform + ext;

                byte[] encrypted;
                try (InputStream is = JNICLoader.class.getResourceAsStream("/cn/sky/jnic/000000000000000000000000000000000000.dat")) {
                    if (is == null) {
                        throw new UnsatisfiedLinkError("Could not find native library");
                    }
                    // Read unique encrypted blob
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    int nRead;
                    byte[] data = new byte[8192];
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                    encrypted = buffer.toByteArray();
                }

                // Decrypt (XOR 0x5F)
                for (int i = 0; i < encrypted.length; i++) {
                    encrypted[i] ^= 0x5F;
                }

                // Parse: [int count] [int nameLen] [name] [int contentLen] [content] ...
                ByteBuffer buf = ByteBuffer.wrap(encrypted);
                int count = buf.getInt();
                boolean found = false;

                for (int i = 0; i < count; i++) {
                    int nameLen = buf.getInt();
                    byte[] nameBytes = new byte[nameLen];
                    buf.get(nameBytes);
                    String name = new String(nameBytes, StandardCharsets.UTF_8);

                    int contentLen = buf.getInt();
                    if (!found && name.equals(targetName)) {
                        byte[] content = new byte[contentLen];
                        buf.get(content);
                        File tempDir = new File(System.getProperty("java.io.tmpdir"));
                        File tempFile = new File(tempDir, UUID.randomUUID() + ".tmp");
                        if (!tempFile.exists() || tempFile.length() != content.length) {
                            try (OutputStream osStream = new FileOutputStream(tempFile)) {
                                osStream.write(content);
                            }
                        }
                        System.load(tempFile.getAbsolutePath());
                        tempFile.deleteOnExit();
                        found = true;
                    } else {
                        buf.position(buf.position() + contentLen);
                    }
                }

                if (!found) {
                    throw new UnsatisfiedLinkError("Native library not found in data");
                }

                loaded = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load native library", e);
            }
        }
        registerNatives(clazz);
    }

    private static native void registerNatives(Class<?> clazz);
}
