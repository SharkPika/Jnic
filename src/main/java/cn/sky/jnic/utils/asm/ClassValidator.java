package cn.sky.jnic.utils.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 用于验证和修复类结构的工具类
 */
public class ClassValidator implements Opcodes {

    /**
     * 验证并修复类结构
     * @param cn 要验证的类节点
     * @return 有无进行修复
     */
    public static boolean validateAndFixClass(ClassNode cn) {
        boolean modified = false;
        
        // 1. 确保有父类
        if (cn.superName == null) {
            cn.superName = "java/lang/Object";
            modified = true;
        }
        
        // 2. 确保有有效的访问标志
        if ((cn.access & ACC_INTERFACE) != 0 && (cn.access & ACC_ABSTRACT) == 0) {
            cn.access |= ACC_ABSTRACT;
            modified = true;
        }
        
        // 3. 确保有方法列表
        if (cn.methods == null) {
            cn.methods = new ArrayList<>();
            modified = true;
        }
        
        // 4. 确保有字段列表
        if (cn.fields == null) {
            cn.fields = new ArrayList<>();
            modified = true;
        }
        
        // 5. 确保有至少一个构造函数（非接口类）
        if ((cn.access & ACC_INTERFACE) == 0 && !hasConstructor(cn)) {
            addDefaultConstructor(cn);
            modified = true;
        }
        
        // 6. 验证所有方法
        for (MethodNode method : cn.methods) {
            if (validateAndFixMethod(cn, method)) {
                modified = true;
            }
        }
        
        return modified;
    }
    
    /**
     * 检查类是否有构造函数
     */
    private static boolean hasConstructor(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if ("<init>".equals(method.name)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 添加默认构造函数
     */
    private static void addDefaultConstructor(ClassNode cn) {
        MethodNode constructor = new MethodNode(
            ACC_PUBLIC,
            "<init>",
            "()V",
            null,
            null
        );
        
        InsnList il = constructor.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new MethodInsnNode(
            INVOKESPECIAL,
            cn.superName,
            "<init>",
            "()V",
            false
        ));
        il.add(new InsnNode(RETURN));
        
        constructor.maxStack = 1;
        constructor.maxLocals = 1;
        
        cn.methods.add(constructor);
    }
    
    /**
     * 验证并修复方法
     * @return 有无进行修复
     */
    private static boolean validateAndFixMethod(ClassNode cn, MethodNode method) {
        boolean modified = false;
        
        // 跳过抽象和本地方法
        if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
            return false;
        }
        
        // 确保有指令列表
        if (method.instructions == null) {
            method.instructions = new InsnList();
            modified = true;
        }
        
        // 确保构造函数有对super的调用
        if ("<init>".equals(method.name)) {
            if (!hasSuperConstructorCall(method)) {
                insertSuperConstructorCall(cn, method);
                modified = true;
            }
        }
        
        // 确保方法有返回指令
        if (!hasReturnInstruction(method)) {
            addReturnInstruction(method);
            modified = true;
        }
        
        // 确保maxStack和maxLocals设置合理
        if (method.maxStack < 1) {
            method.maxStack = 4; // 设置一个安全值
            modified = true;
        }
        
        if (method.maxLocals < 1) {
            // 计算方法所需的局部变量数量
            int argSize = 0;
            if ((method.access & ACC_STATIC) == 0) {
                argSize = 1; // this引用
            }
            
            for (Type argType : Type.getArgumentTypes(method.desc)) {
                argSize += argType.getSize();
            }
            
            method.maxLocals = argSize + 2; // 额外空间用于局部变量
            modified = true;
        }
        
        // 如果没有异常表，初始化它
        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
            modified = true;
        }
        
        // 检查局部变量表
        if (method.localVariables == null) {
            method.localVariables = new ArrayList<>();
            modified = true;
        }
        
        return modified;
    }
    
    /**
     * 检查方法是否有对超类构造函数的调用
     */
    private static boolean hasSuperConstructorCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() == INVOKESPECIAL) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if ("<init>".equals(methodInsn.name)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 在方法开头插入对超类构造函数的调用
     */
    private static void insertSuperConstructorCall(ClassNode cn, MethodNode method) {
        InsnList il = new InsnList();
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new MethodInsnNode(
            INVOKESPECIAL,
            cn.superName,
            "<init>",
            "()V",
            false
        ));
        
        if (method.instructions.size() == 0) {
            method.instructions.add(il);
        } else {
            method.instructions.insertBefore(method.instructions.getFirst(), il);
        }
    }
    
    /**
     * 检查方法是否有返回指令
     */
    private static boolean hasReturnInstruction(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            int opcode = insn.getOpcode();
            if (opcode >= IRETURN && opcode <= RETURN) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 添加适当的返回指令
     */
    private static void addReturnInstruction(MethodNode method) {
        String returnType = Type.getReturnType(method.desc).getDescriptor();
        
        switch (returnType) {
            case "V": // void
                method.instructions.add(new InsnNode(RETURN));
                break;
            case "I": // int
            case "Z": // boolean
            case "B": // byte
            case "C": // char
            case "S": // short
                method.instructions.add(new InsnNode(ICONST_0));
                method.instructions.add(new InsnNode(IRETURN));
                break;
            case "J": // long
                method.instructions.add(new InsnNode(LCONST_0));
                method.instructions.add(new InsnNode(LRETURN));
                break;
            case "F": // float
                method.instructions.add(new InsnNode(FCONST_0));
                method.instructions.add(new InsnNode(FRETURN));
                break;
            case "D": // double
                method.instructions.add(new InsnNode(DCONST_0));
                method.instructions.add(new InsnNode(DRETURN));
                break;
            default: // 引用类型
                method.instructions.add(new InsnNode(ACONST_NULL));
                method.instructions.add(new InsnNode(ARETURN));
                break;
        }
    }
    
    /**
     * 修复类中的错误跳转指令
     */
    public static boolean fixJumpInstructions(ClassNode cn) {
        boolean modified = false;
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        
        // 收集所有有效的标签
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof LabelNode) {
                    labelMap.put((LabelNode) insn, (LabelNode) insn);
                }
            }
        }
        
        // 修复跳转指令
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof JumpInsnNode) {
                    JumpInsnNode jumpInsn = (JumpInsnNode) insn;
                    if (jumpInsn.label == null || !labelMap.containsKey(jumpInsn.label)) {
                        // 创建一个新标签并插入到方法末尾
                        LabelNode newLabel = new LabelNode();
                        method.instructions.add(newLabel);
                        
                        // 更新跳转指令
                        jumpInsn.label = newLabel;
                        modified = true;
                    }
                } else if (insn instanceof TableSwitchInsnNode) {
                    TableSwitchInsnNode switchInsn = (TableSwitchInsnNode) insn;
                    
                    // 修复默认标签
                    if (switchInsn.dflt == null || !labelMap.containsKey(switchInsn.dflt)) {
                        LabelNode newLabel = new LabelNode();
                        method.instructions.add(newLabel);
                        switchInsn.dflt = newLabel;
                        modified = true;
                    }
                    
                    // 修复case标签
                    for (int i = 0; i < switchInsn.labels.size(); i++) {
                        LabelNode label = switchInsn.labels.get(i);
                        if (label == null || !labelMap.containsKey(label)) {
                            LabelNode newLabel = new LabelNode();
                            method.instructions.add(newLabel);
                            switchInsn.labels.set(i, newLabel);
                            modified = true;
                        }
                    }
                } else if (insn instanceof LookupSwitchInsnNode) {
                    LookupSwitchInsnNode switchInsn = (LookupSwitchInsnNode) insn;
                    
                    // 修复默认标签
                    if (switchInsn.dflt == null || !labelMap.containsKey(switchInsn.dflt)) {
                        LabelNode newLabel = new LabelNode();
                        method.instructions.add(newLabel);
                        switchInsn.dflt = newLabel;
                        modified = true;
                    }
                    
                    // 修复case标签
                    for (int i = 0; i < switchInsn.labels.size(); i++) {
                        LabelNode label = switchInsn.labels.get(i);
                        if (label == null || !labelMap.containsKey(label)) {
                            LabelNode newLabel = new LabelNode();
                            method.instructions.add(newLabel);
                            switchInsn.labels.set(i, newLabel);
                            modified = true;
                        }
                    }
                }
            }
        }
        
        return modified;
    }
} 