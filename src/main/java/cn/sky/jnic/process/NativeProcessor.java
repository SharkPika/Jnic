package cn.sky.jnic.process;

import cn.sky.jnic.Jnic;
import cn.sky.jnic.generator.CGenerator;
import cn.sky.jnic.utils.MatcherUtils;
import cn.sky.jnic.utils.asm.ClassWrapper;
import cn.sky.jnic.utils.asm.MethodWrapper;
import lombok.Getter;
import org.objectweb.asm.Opcodes;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

import org.objectweb.asm.ClassReader;

public class NativeProcessor {
    @Getter
    private final Jnic jnic;
    private final CGenerator generator;
    private final List<String> generatedNativeMethods = new ArrayList<>();
    private final Set<ClassWrapper> processedClasses = new HashSet<>();

    public NativeProcessor(Jnic jnic) {
        this.jnic = jnic;
        this.generator = new CGenerator(this);
    }

    public boolean isNative(String owner, String name, String desc) {
        ClassWrapper classWrapper = jnic.getClasses().get(owner);
        if (classWrapper == null)
            return false;
        if (!shouldProcessClass(classWrapper))
            return false;

        for (MethodWrapper mw : classWrapper.getMethods()) {
            if (mw.getOriginalName().equals(name) && mw.getOriginalDescriptor().equals(desc)) {
                return shouldProcessMethod(mw);
            }
        }
        return false;
    }

    public void process() {
        Jnic.getLogger().info("Starting native processing...");

        HashMap<String, ClassWrapper> temp = new HashMap<>();
        for (ClassWrapper classWrapper : jnic.getClasses().values()) {
            if (!shouldProcessClass(classWrapper))
                continue;

            boolean classModified = false;
            // Iterate over a copy to avoid ConcurrentModificationException when adding
            // helper methods
            List<MethodWrapper> methods = new ArrayList<>(classWrapper.getMethods());
            for (MethodWrapper methodWrapper : methods) {
                if (shouldProcessMethod(methodWrapper)) {
                    processMethod(classWrapper, methodWrapper);
                    classModified = true;
                }
            }

            if (classModified) {
                processedClasses.add(classWrapper);
                injectLoader(classWrapper, temp);
            }
        }

        jnic.getClasses().putAll(temp);

        // Finalize generation (write C files, compile, etc.)
        generator.finalizeGeneration();

        // Extract jni.h from resources
        try (InputStream is = getClass().getResourceAsStream("/jni.h")) {
            if (is != null) {
                Files.copy(is, new File(jnic.getTmpdir(), "jni.h").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Jnic.getLogger().warn("jni.h not found in resources. Compilation might fail if system headers are missing.");
            }
        } catch (IOException e) {
            Jnic.getLogger().error("Failed to extract jni.h: " + e.getMessage());
        }

        // Compile using Zig
        File cFile = new File(jnic.getTmpdir(), Jnic.getInstance().getTempC().toString() + ".c");

        // Output directory: Use a temporary directory for compilation artifacts
        if (cFile.exists()) {
            ZigCompiler.compile(cFile, jnic.getTmpdir(), jnic.getConfig().getTargets());

            // Collect compiled libraries and add to Jnic resources map
            // This ensures they are included in the output JAR
            // Collect compiled libraries and pack them into native.dat
            File[] files = jnic.getTmpdir().listFiles();
            if (files != null) {
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                     DataOutputStream dos = new DataOutputStream(baos)) {

                    List<File> libsToPack = new ArrayList<>();
                    for (File lib : files) {
                        String name = lib.getName();
                        if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")) {
                            libsToPack.add(lib);
                        }
                    }

                    dos.writeInt(libsToPack.size());
                    for (File lib : libsToPack) {
                        byte[] nameBytes = lib.getName().getBytes(StandardCharsets.UTF_8);
                        dos.writeInt(nameBytes.length);
                        dos.write(nameBytes);

                        byte[] content = Files.readAllBytes(lib.toPath());
                        dos.writeInt(content.length);
                        dos.write(content);

                        Jnic.getLogger().info("Packed library: " + lib.getName());
                    }

                    byte[] data = baos.toByteArray();
                    // Encrypt (XOR 0x5F)
                    for (int i = 0; i < data.length; i++) {
                        data[i] ^= 0x5F;
                    }

                    jnic.getResources().put("cn/sky/jnic/" + jnic.getTempOut().toString() + ".dat", data);
                    Jnic.getLogger().info("Generated encrypted dat file with " + libsToPack.size() + " libraries.");

                } catch (IOException e) {
                    Jnic.getLogger().error("Failed to pack native libraries: " + e.getMessage());
                }
            }

            // Clean up temp dir (optional, good for debug to keep)
            //jnic.getTmpdir().delete();

        } else {
            Jnic.getLogger().error("Native source file not found: " + cFile.getAbsolutePath());
        }
    }

    private boolean shouldProcessClass(ClassWrapper classWrapper) {
        String className = classWrapper.getName();

        // 1. Check excludes first
        List<String> excludes = jnic.getConfig().getExclude();
        if (excludes != null) {
            for (String exclude : excludes) {
                if (MatcherUtils.match(className, exclude)) {
                    return false;
                }
            }
        }

        // 2. Check includes
        List<String> includes = jnic.getConfig().getInclude();
        if (includes != null && !includes.isEmpty()) {
            boolean included = false;
            for (String include : includes) {
                if (MatcherUtils.match(className, include)) {
                    included = true;
                    break;
                }
            }
            if (!included)
                return false;
        }

        // Basic sanity checks
        return (classWrapper.getClassNode().access & Opcodes.ACC_INTERFACE) == 0;
    }

    private boolean shouldProcessMethod(MethodWrapper methodWrapper) {
        String name = methodWrapper.getOriginalName();
        // Skip constructors and static initializers
        if ("<init>".equals(name) || "<clinit>".equals(name)) {
            return false;
        }

        String desc = methodWrapper.getOriginalDescriptor();
        if ("findClass".equals(name) && "(Ljava/lang/String;)Ljava/lang/Class;".equals(desc)) {
            return false;
        }
        if ("getResourceAsStream".equals(name) && "(Ljava/lang/String;)Ljava/io/InputStream;".equals(desc)) {
            return false;
        }

        if (hasUnsupportedOpcodes(methodWrapper.getMethodNode())) {
            Jnic.getLogger().warn("Skipping method with unsupported opcodes: " + name);
            return false;
        }

        return (methodWrapper.getMethodNode().access & Opcodes.ACC_ABSTRACT) == 0 &&
                (methodWrapper.getMethodNode().access & Opcodes.ACC_NATIVE) == 0 &&
                methodWrapper.getMethodNode().instructions.size() > 0;
    }

    private boolean hasUnsupportedOpcodes(MethodNode methodNode) {
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode < 0)
                continue;
            switch (opcode) {
                case Opcodes.MULTIANEWARRAY:
                case Opcodes.JSR:
                case Opcodes.RET:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private void injectLoader(ClassWrapper classWrapper, HashMap<String, ClassWrapper> classes) {
        try {
            String loader = "cn/sky/jnic/JNICLoader";
            if (!jnic.getClasses().containsKey(loader)) {
                InputStream is = getClass().getResourceAsStream("/" + loader + ".class");
                if (is == null) {
                    throw new IOException("Could not find JNICLoader.class to inject!");
                }
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    try (DataInputStream dis = new DataInputStream(is)) {
                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = dis.read(buffer)) != -1) {
                            baos.write(buffer, 0, read);
                        }
                    }
                    byte[] originalBytes = baos.toByteArray();

                    String placeholder = "000000000000000000000000000000000000";
                    String replacement = jnic.getTempOut().toString();

                    byte[] processedBytes = replacePlaceholderInBytes(originalBytes,
                            placeholder.getBytes(StandardCharsets.UTF_8),
                            replacement.getBytes(StandardCharsets.UTF_8));

                    classes.put(loader, ClassWrapper.from(new ClassReader(processedBytes)));
                }
            }
        } catch (Exception e) {
            Jnic.getLogger().error("Failed to inject JNICLoader", e);
        }

        InsnList il = new InsnList();
        il.add(new LdcInsnNode("jnic")); // Library name
        il.add(new LdcInsnNode(Type.getObjectType(classWrapper.getName()))); // Push class
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "cn/sky/jnic/JNICLoader", "load",
                "(Ljava/lang/String;Ljava/lang/Class;)V", false));

        MethodNode clinit = classWrapper.getMethodNode("<clinit>", "()V");
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            classWrapper.addMethod(clinit);
        } else {
            if (clinit.instructions.size() > 0) {
                clinit.instructions.remove(clinit.instructions.getLast());
            }
        }

        clinit.instructions.add(il);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
    }

    private void processMethod(ClassWrapper owner, MethodWrapper method) {
        Jnic.getLogger().info("Processing method: " + owner.getName() + "." + method.getOriginalName());

        // Handle INVOKEDYNAMIC before generation
        handleInvokeDynamic(owner, method);

        // 1. Generate C code for the method
        String cCode = generator.generateMethod(owner, method);

        // 2. Modify Java method to be native
        method.getMethodNode().access |= Opcodes.ACC_NATIVE;
        method.getMethodNode().instructions.clear();
        method.getMethodNode().tryCatchBlocks.clear();
        method.getMethodNode().localVariables.clear();

        generatedNativeMethods.add(owner.getName() + "_" + method.getOriginalName());
    }

    private void handleInvokeDynamic(ClassWrapper owner, MethodWrapper method) {
        InsnList instructions = method.getMethodNode().instructions;
        List<InvokeDynamicInsnNode> indyNodes = new ArrayList<>();

        for (AbstractInsnNode insn = instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode) {
                indyNodes.add((InvokeDynamicInsnNode) insn);
            }
        }

        for (InvokeDynamicInsnNode indy : indyNodes) {
            String helperName = "indy_wrapper_" + Math.abs(indy.hashCode());

            // Create helper method: static synthetic
            MethodNode helper = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, helperName, indy.desc, null,
                    null);

            // Generate body
            InsnList il = helper.instructions;
            Type[] args = Type.getArgumentTypes(indy.desc);
            int varIndex = 0;
            for (Type arg : args) {
                il.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), varIndex));
                varIndex += arg.getSize();
            }

            // Add the invokedynamic instruction (clone it to be safe)
            il.add(indy.clone(null));

            // Return
            Type returnType = Type.getReturnType(indy.desc);
            il.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

            // Add helper to class
            owner.addMethod(helper);

            // Replace original instruction with INVOKESTATIC to helper
            instructions.set(indy,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, owner.getName(), helperName, indy.desc, false));
        }
    }

    private byte[] replacePlaceholderInBytes(byte[] original, byte[] placeholder, byte[] replacement) {
        List<Integer> positions = findBytePositions(original, placeholder);

        if (positions.isEmpty()) {
            return original;
        }

        int newLength = original.length + (replacement.length - placeholder.length) * positions.size();
        byte[] result = new byte[newLength];

        int srcPos = 0;
        int dstPos = 0;

        for (int pos : positions) {
            System.arraycopy(original, srcPos, result, dstPos, pos - srcPos);
            dstPos += pos - srcPos;

            System.arraycopy(replacement, 0, result, dstPos, replacement.length);
            dstPos += replacement.length;

            srcPos = pos + placeholder.length;
        }

        System.arraycopy(original, srcPos, result, dstPos, original.length - srcPos);

        return result;
    }

    private List<Integer> findBytePositions(byte[] data, byte[] pattern) {
        List<Integer> positions = new ArrayList<>();
        if (pattern.length == 0) return positions;

        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                positions.add(i);
            }
        }
        return positions;
    }
}
