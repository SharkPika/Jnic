package cn.sky.jnic;

import cn.sky.jnic.config.Config;
import cn.sky.jnic.process.NativeProcessor;
import cn.sky.jnic.utils.asm.ClassWrapper;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bspfsystems.yamlconfiguration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class Jnic {

    @Getter
    public static Logger logger = LogManager.getLogger(Jnic.class);
    @Getter
    public static Jnic instance;

    private final SkyJarLoader loader;
    private final NativeProcessor processor;

    public final File configFile;
    public final Config config;

    public final Map<String, ClassWrapper> classes;
    public final Map<String, ClassWrapper> classpath;
    public final Map<String, byte[]> resources;

    public final File tmpdir = new File(System.getProperty("java.io.tmpdir"), "jnic_" + UUID.randomUUID());
    public final UUID tempC = UUID.randomUUID();
    public final UUID tempOut = UUID.randomUUID();

    public Jnic() {
        instance = this;

        if (!this.tmpdir.exists()) {
            this.tmpdir.mkdirs();
        }

        this.configFile = new File("config.yml");
        this.config = new Config(YamlConfiguration.loadConfiguration(this.configFile));

        this.classes = new HashMap<>();
        this.classpath = new HashMap<>();
        this.resources = new HashMap<>();

        this.loader = new SkyJarLoader();
        this.loader.loadInput();
        this.loader.loadLib();

        this.processor = new NativeProcessor(this);
        this.processor.process();

        this.deleteDirectory(this.tmpdir);
        //this.cleanup();

        this.loader.saveOutput();
    }

    private void cleanup() {
        logger.info("Skipping cleanup for debug...");
        deleteDirectory(this.tmpdir);
        /*File zigCache = new File(this.configFile.getParentFile(), ".zig-cache");
        deleteDirectory(zigCache);*/
    }

    private void deleteFile(File file) {
        if (!file.exists()) return;
        if (!file.delete()) {
            logger.warn("无法删除文件: " + file.getAbsolutePath());
        }
    }

    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        deleteFile(file);
                    }
                }
            }
            deleteFile(directory);
        }
    }
}
