package cn.sky.jnic.generator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import cn.sky.jnic.config.Config;

public class Obfuscator {
    private final Config config;
    private final Random random = new Random();
    private final List<String> encryptedStrings = new ArrayList<>();

    public Obfuscator(Config config) {
        this.config = config;
    }

    public String encryptString(String input) {
        return "\"" + input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    // 加密字符串数据结构
    public record EncryptedString(byte[] encrypted, int key, int length) {
        public String cArrayLiteral() {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < encrypted.length; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(String.format("0x%02X", encrypted[i] & 0xFF));
            }
            sb.append("}");
            return sb.toString();
        }
    }

    public EncryptedString encryptStringData(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        int key = random.nextInt(256);
        byte[] encrypted = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            encrypted[i] = (byte) (bytes[i] ^ key);
        }
        return new EncryptedString(encrypted, key, bytes.length);
    }

    public String flattenControlFlow(String functionBody) {
        if (!config.isFlowObfuscation()) {
            return functionBody;
        }

        // Simplified Control Flow Flattening simulation
        // In reality, this requires parsing the C code or generating it in a flattened
        // way initially.
        // Here we wrap the body in a switch statement structure.

        StringBuilder sb = new StringBuilder();
        sb.append("    unsigned int _state = 0;\n");
        sb.append("    while(1) {\n");
        sb.append("        switch(_state) {\n");
        sb.append("            case 0:\n");
        sb.append(functionBody).append("\n");
        sb.append("                _state = 0xFFFFFFFF;\n"); // End state
        sb.append("                break;\n");
        sb.append("            case 0xFFFFFFFF:\n");
        sb.append("                return;\n");
        sb.append("        }\n");
        sb.append("    }\n");

        return sb.toString();
    }

    public String getAntiDebugCode() {
        if (!config.isAntiDebug()) {
            return "";
        }

        // Inline ASM for ARM64 and x86
        // Disabled for debugging
        return "";
        /*
         * return
         * "#if defined(__aarch64__)\\n" +
         * "    __asm__ __volatile__(" +
         * "        \"mov x0, #0\\n\" +
         * "        \"mov x1, #0\\n\" +
         * "        \"mov x2, #0\\n\" +
         * "        \"mov x3, #0\\n\" +
         * "        \"mov x8, #117\\n\" + // ptrace
         * "        \"svc #0\\n\" +
         * "    );\n" +
         * "#elif defined(__i386__)\\n" +
         * "    __asm__ __volatile__(" +
         * "        \"movl $26, %%eax\\n\" +
         * "        \"int $0x80\\n\" +
         * "    );\n" +
         * "#endif\\n";
         */
    }
}
