package cn.sky.jnic.utils.asm;

import cn.sky.jnic.Jnic;
import lombok.Getter;
import lombok.Setter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Wrapper around {@link ClassNode}.
 *
 * @author itzsomebody
 */

@Getter
@Setter
public class ClassWrapper implements Opcodes {
    private static final int LIB_READER_FLAGS = ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE;
    private static final int INPUT_READER_FLAGS = ClassReader.SKIP_FRAMES;
    private static final int CP_COUNT_OFFSET = 0x4 + 0x2 + 0x2; // https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.1

    private ClassNode classNode;
    private final String originalName;
    private final boolean libraryNode;

    private final List<MethodWrapper> methods;
    private final List<FieldWrapper> fields;

    private final List<ClassWrapper> parents = new ArrayList<>();
    private final List<ClassWrapper> children = new ArrayList<>();

    public List<String> utf8Consts = new ArrayList<>(Collections.singletonList("SkyShield1.0"));

    public ClassWrapper(ClassReader reader, boolean libraryNode) {
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, libraryNode ? LIB_READER_FLAGS : INPUT_READER_FLAGS);

        this.classNode = classNode;
        this.originalName = classNode.name;
        this.libraryNode = libraryNode;

        this.methods = MethodWrappers.from(this);
        this.fields = FieldWrappers.from(this);
    }

    public ClassWrapper(ClassNode classNode, boolean libraryNode) {
        this.classNode = classNode;
        this.originalName = classNode.name;
        this.libraryNode = libraryNode;

        this.methods = MethodWrappers.from(this);
        this.fields = FieldWrappers.from(this);
    }

    // -----------------
    // Getters / Setters
    // -----------------

    public String getName() {
        return classNode.name;
    }

    public String getSuperName() {
        return classNode.superName;
    }

    public List<String> getInterfaceNames() {
        return classNode.interfaces;
    }

    public Stream<MethodWrapper> methodStream() {
        return getMethods().stream();
    }

    public Stream<FieldWrapper> fieldStream() {
        return getFields().stream();
    }

    // ------------
    // Access stuff
    // ------------

    public void addAccessFlags(int flags) {
        classNode.access |= flags;
    }

    public void removeAccessFlags(int flags) {
        classNode.access &= ~flags;
    }

    public boolean isPublic() {
        return (classNode.access & ACC_PUBLIC) != 0;
    }

    public boolean isPrivate() {
        return (classNode.access & ACC_PRIVATE) != 0;
    }

    public boolean isProtected() {
        return (classNode.access & ACC_PROTECTED) != 0;
    }

    public boolean isFinal() {
        return (classNode.access & ACC_FINAL) != 0;
    }

    public boolean isSuper() {
        return (classNode.access & ACC_SUPER) != 0;
    }

    public boolean isInterface() {
        return (classNode.access & ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (classNode.access & ACC_ABSTRACT) != 0;
    }

    public boolean isSynthetic() {
        return (classNode.access & ACC_SYNTHETIC) != 0;
    }

    public boolean isAnnotation() {
        return (classNode.access & ACC_ANNOTATION) != 0;
    }

    public boolean isEnum() {
        return (classNode.access & ACC_ENUM) != 0;
    }

    public boolean isModule() {
        return (classNode.access & ACC_MODULE) != 0;
    }

    public boolean isRecord() {
        return (classNode.access & ACC_RECORD) != 0;
    }

    public boolean isDeprecated() {
        return (classNode.access & ACC_DEPRECATED) != 0;
    }

    // -----
    // Misc.
    // -----

    /**
     * Adds a {@link MethodNode} to this {@link ClassWrapper}.
     *
     * @param methodNode {@link MethodNode} to add.
     */
    public void addMethod(MethodNode methodNode) {
        classNode.methods.add(methodNode);
        methods.add(MethodWrapper.from(methodNode, this));
    }

    /**
     * Adds a {@link FieldNode} to this {@link ClassWrapper}.
     *
     * @param fieldNode {@link FieldNode} to add.
     */
    public void addField(FieldNode fieldNode) {
        classNode.fields.add(fieldNode);
        fields.add(FieldWrapper.from(fieldNode, this));
    }

    public MethodNode getMethodNode(String name, String desc) {
        return classNode.methods.stream().filter(methodNode -> name.equals(methodNode.name) && desc.equals(methodNode.desc)).findAny().orElse(null);
    }

    public FieldNode getFieldNode(String name, String desc) {
        return classNode.fields.stream().filter(fieldNode -> name.equals(fieldNode.name) && desc.equals(fieldNode.desc)).findAny().orElse(null);
    }

    public boolean containsMethodNode(String name, String desc) {
        return classNode.methods.stream().anyMatch(methodNode -> name.equals(methodNode.name) && desc.equals(methodNode.desc));
    }

    public boolean containsFieldNode(String name, String desc) {
        return classNode.fields.stream().anyMatch(fieldNode -> name.equals(fieldNode.name) && desc.equals(fieldNode.desc));
    }

    /**
     * Returns true if the class allows dynamic constants (Java 11 and above).
     */
    public boolean allowsConstDy() {
        return (classNode.version >= V11) && (classNode.version != V1_1);
    }

    /**
     * Returns true if this class allows invokedynamic instructions (Java 7 and above).
     */
    public boolean allowsIndy() {
        return (classNode.version >= V1_7) && (classNode.version != V1_1);
    }

    /**
     * Returns true if this class allows JSR and RET instructions (Java 5 and below).
     */
    public boolean allowsJsr() {
        return (classNode.version <= V1_5) || (classNode.version == V1_1);
    }

    public boolean hasVisibleAnnotations() {
        return classNode.visibleAnnotations != null && !classNode.visibleAnnotations.isEmpty();
    }

    /**
     * Returns the count of the constant pool of the classfile this {@link ClassWrapper} represents.
     * <p>
     * This is done by writing the class bytes via {@link ClassWriter} with flags set to 0. The constant pool count is
     * then manually constructed by directly accessing the relevant offsets. This is computationally expensive and should
     * be done sparingly
     */
    public int computePoolCount() {
        // Hopefully setting flags to 0 doesn't affect CP count
        // If it does then pepega
        ClassWriter writer = new ClassWriter(0);
        utf8Consts.forEach(writer::newUTF8);
        classNode.accept(writer);

        // Grab byte array
        byte[] bytes = writer.toByteArray();

        // Construct unsigned short
        // todo: check to make sure this isn't broken
        return ((bytes[CP_COUNT_OFFSET] & 0xFF) << 8) | (bytes[CP_COUNT_OFFSET + 1] & 0xFF);
    }

    public void addUtf8Const(String s) {
        utf8Consts.add(s);
    }

    /**
     * Converts the class this {@link ClassWrapper} represents into a byte array.
     */
    public byte[] toByteArray() {
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        try {
            utf8Consts.forEach(classWriter::newUTF8);

            classNode.accept(classWriter);
            return classWriter.toByteArray();
        } catch (Throwable t) {
            Jnic.getLogger().warn(String.format("Error writing class %s. Trying with basic writer.", classNode.name + ".class"));
            t.printStackTrace(System.out);

            classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            utf8Consts.forEach(classWriter::newUTF8);

            classNode.accept(classWriter);
            return classWriter.toByteArray();
        }
    }


    public static ClassWrapper from(ClassReader reader) {
        return new ClassWrapper(reader, false);
    }

    public static ClassWrapper fromLib(ClassReader reader) {
        return new ClassWrapper(reader, true);
    }
}
