package cn.sky.jnic.utils.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.MethodNode;

/**
 * Wrapper around {@link MethodNode}.
 *
 * @author itzsomebody
 */
public class MethodWrapper implements Opcodes {
    /*
     * From https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.7.3
     *
     * Note that even though the specification lists an unsigned 32-bit integer size for code_length, the verifier gets
     * mad if any method has a code_length > 0xFFFF
     */
    private static final int MAX_CODE_SIZE = 0xFFFF;

    private MethodNode methodNode;
    private final String originalName;
    private final String originalDescriptor;
    private final ClassWrapper owner;

    public MethodWrapper(MethodNode methodNode, ClassWrapper owner) {
        this.methodNode = methodNode;
        this.originalName = methodNode.name;
        this.originalDescriptor = methodNode.desc;
        this.owner = owner;
    }

    // -----------------
    // Getters / Setters
    // -----------------

    public MethodNode getMethodNode() {
        return methodNode;
    }

    public void setMethodNode(MethodNode methodNode) {
        this.methodNode = methodNode;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getOriginalDescriptor() {
        return originalDescriptor;
    }

    public ClassWrapper getOwner() {
        return owner;
    }

    // ------------
    // Access stuff
    // ------------

    public void addAccessFlags(int flags) {
        methodNode.access |= flags;
    }

    public void removeAccessFlags(int flags) {
        methodNode.access &= ~flags;
    }

    public boolean isPublic() {
        return (ACC_PUBLIC & methodNode.access) != 0;
    }

    public boolean isPrivate() {
        return (ACC_PRIVATE & methodNode.access) != 0;
    }

    public boolean isProtected() {
        return (ACC_PROTECTED & methodNode.access) != 0;
    }

    public boolean isStatic() {
        return (ACC_STATIC & methodNode.access) != 0;
    }

    public boolean isFinal() {
        return (ACC_FINAL & methodNode.access) != 0;
    }

    public boolean isSynchronized() {
        return (ACC_SYNCHRONIZED & methodNode.access) != 0;
    }

    public boolean isBridge() {
        return (ACC_BRIDGE & methodNode.access) != 0;
    }

    public boolean isVarargs() {
        return (ACC_VARARGS & methodNode.access) != 0;
    }

    public boolean isNative() {
        return (ACC_NATIVE & methodNode.access) != 0;
    }

    public boolean isAbstract() {
        return (ACC_ABSTRACT & methodNode.access) != 0;
    }

    public boolean isStrict() {
        return (ACC_STRICT & methodNode.access) != 0;
    }

    public boolean isSynthetic() {
        return (ACC_SYNTHETIC & methodNode.access) != 0;
    }

    public boolean isMandated() {
        return (ACC_MANDATED & methodNode.access) != 0;
    }

    public boolean isDeprecated() {
        return (ACC_DEPRECATED & methodNode.access) != 0;
    }

    // -----
    // Misc.
    // -----

    public boolean hasInstructions() {
        return methodNode.instructions.size() > 0;
    }

    public boolean hasVisibleAnnotations() {
        return methodNode.visibleAnnotations != null && !methodNode.visibleAnnotations.isEmpty();
    }

    public int getCodeSize() {
        CodeSizeEvaluator evaluator = new CodeSizeEvaluator(null);
        methodNode.accept(evaluator);
        return evaluator.getMaxSize();
    }

    public int getLeewaySize() {
        return MAX_CODE_SIZE - getCodeSize();
    }

    public int allocateLocalVar(boolean twoWords) {
        methodNode.maxLocals += (twoWords ? 2 : 1);
        return methodNode.maxLocals - 1;
    }

    public static MethodWrapper from(MethodNode methodNode, ClassWrapper owner) {
        return new MethodWrapper(methodNode, owner);
    }
}
