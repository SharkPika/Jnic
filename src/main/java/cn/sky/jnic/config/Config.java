package cn.sky.jnic.config;

import lombok.Getter;
import lombok.Setter;
import org.bspfsystems.yamlconfiguration.configuration.ConfigurationSection;

import java.util.List;

@Getter
@Setter
public class Config {
    private String inputJar;
    private String outputJar;
    private List<String> libraries;
    private List<String> targets;
    private List<String> include;
    private List<String> exclude;

    // Obfuscation Settings
    private boolean stringEncryption;
    private boolean flowObfuscation;
    private boolean antiDebug;

    public Config(ConfigurationSection section) {
        this.inputJar = section.getString("input");
        if (this.inputJar == null || this.inputJar.isEmpty()) {
            throw new IllegalArgumentException("Input jar is empty.");
        }
        this.outputJar = section.getString("output");
        if (this.outputJar == null || this.outputJar.isEmpty()) {
            throw new IllegalArgumentException("Output jar is empty.");
        }
        this.libraries = section.getStringList("libs");
        this.targets = section.getStringList("target");
        if (this.targets.isEmpty()) {
            throw new IllegalArgumentException("Targets is empty.");
        }
        this.include = section.getStringList("includes");
        this.exclude = section.getStringList("excludes");

        this.stringEncryption = section.getBoolean("obfuscation.stringEncryption", true);
        this.flowObfuscation = section.getBoolean("obfuscation.flowObfuscation", true);
        this.antiDebug = section.getBoolean("obfuscation.antiDebug", true);
    }
}
