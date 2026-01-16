package cn.sky.jnic;

import cn.sky.jnic.utils.asm.ClassWrapper;
import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import java.util.jar.JarOutputStream;
import java.io.FileOutputStream;

import java.util.jar.Manifest;

public class SkyJarLoader {

    public void loadInput() {
        try (JarFile jarFile = new JarFile(Jnic.getInstance().getConfig().getInputJar())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    manifest.write(baos);
                    Jnic.getInstance().resources.put("META-INF/MANIFEST.MF", baos.toByteArray());
                }
            }

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6);
                    
                    try {
                        Jnic.getInstance().classes.put(className, ClassWrapper.from(new ClassReader(jarFile.getInputStream(entry))));
                        Jnic.getInstance().classpath.put(className, ClassWrapper.fromLib(new ClassReader(jarFile.getInputStream(entry))));
                    } catch (Throwable e) {
                        Jnic.getLogger().warn(String.format("Error while loading input class: \"%s\" (loading as resources instead)", className));
                        Jnic.getInstance().resources.put(name, jarFile.getInputStream(entry).readAllBytes());
                    }
                } else {
                    Jnic.getInstance().resources.put(name, jarFile.getInputStream(entry).readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveOutput() {
        String outputPath = Jnic.getInstance().getConfig().getOutputJar();
        File outputFile = new File(outputPath);
        Jnic.getLogger().info("Saving output to: " + outputFile.getAbsolutePath());
        
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFile))) {
            // 2. Write classes
            for (Map.Entry<String, ClassWrapper> entry : Jnic.getInstance().getClasses().entrySet()) {
                String className = entry.getKey();
                ClassWrapper wrapper = entry.getValue();
                
                // Ensure class name uses / as separator for JAR entry
                String entryName = className.replace('\\', '/');
                if (entryName.indexOf('/') < 0 && entryName.indexOf('.') >= 0) {
                    entryName = entryName.replace('.', '/');
                }
                if (!entryName.endsWith(".class")) {
                    entryName = entryName + ".class";
                }
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(wrapper.toByteArray());
                jos.closeEntry();
            }
            
            // Write resources (includes compiled libs added by NativeProcessor)
            for (Map.Entry<String, byte[]> entry : Jnic.getInstance().getResources().entrySet()) {
                String name = entry.getKey();
                byte[] data = entry.getValue();
                
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    continue;
                }

                jos.putNextEntry(new JarEntry(name));
                jos.write(data);
                jos.closeEntry();
            }

            if (Jnic.getInstance().getResources().containsKey("META-INF/MANIFEST.MF")) {
                jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jos.write(Jnic.getInstance().getResources().get("META-INF/MANIFEST.MF"));
                jos.closeEntry();
            }
            
            Jnic.getLogger().info("Output saved successfully.");
            
        } catch (IOException e) {
            Jnic.getLogger().error("Failed to save output jar", e);
            throw new RuntimeException(e);
        }
    }

    public void loadLib() {
        for (String path : Jnic.getInstance().getConfig().getLibraries()) {
            File libFile = new File(path);
            if (!libFile.exists()) {
                Jnic.getLogger().warn(String.format("Lib file \"%s\" not found", path));
                continue;
            }

            if (libFile.isFile()) {
                this.addClasspath(libFile);
            } else if (libFile.isDirectory()) {
                Optional.ofNullable(libFile.listFiles()).ifPresent(files -> {
                    for (File file : files) {
                        this.addClasspath(file);
                    }
                });
            }
        }
    }

    private void addClasspath(File file) {
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    try {
                        Jnic.getInstance().classpath.put(name.substring(0, name.length() - 6), ClassWrapper.fromLib(new ClassReader(jarFile.getInputStream(entry))));
                    } catch (Throwable e) {
                        Jnic.getLogger().warn(String.format("Error while loading lib class: \"%s\" (loading as resources instead)", entry.getName()));
                        //this.resources.put(name, IOUtils.toByteArray(jarFile.getInputStream(entry)));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
