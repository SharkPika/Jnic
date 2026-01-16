package cn.sky.jnic.utils.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

public class ASMUtils {
    public static AbstractInsnNode getNumberInsn(int number) {
        if (number >= -1 && number <= 5)
            return new InsnNode(number + 3);
        else if (number >= -128 && number <= 127)
            return new IntInsnNode(Opcodes.BIPUSH, number);
        else if (number >= -32768 && number <= 32767)
            return new IntInsnNode(Opcodes.SIPUSH, number);
        else
            return new LdcInsnNode(number);
    }

    public static AbstractInsnNode getNumberInsn(long number) {
        if (number == 0L || number == 1L)
            return new InsnNode((int) (number + 9));
        else
            return new LdcInsnNode(number);
    }

    public static AbstractInsnNode getNumberInsn(float number) {
        if (number == 0F || number == 1F || number == 2F) {
            return new InsnNode((int) (number + 11));
        } else {
            return new LdcInsnNode(number);
        }
    }

    public static AbstractInsnNode getNumberInsn(double number) {
        if (number == 0D || number == 1D)
            return new InsnNode((int) (number + 14));
        else
            return new LdcInsnNode(number);
    }

    public static int getIntegerFromInsn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();

        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - 3;
        } else if (insn instanceof IntInsnNode && insn.getOpcode() != Opcodes.NEWARRAY) {
            return ((IntInsnNode) insn).operand;
        } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) insn).cst;
        }

        throw new RuntimeException("Attempted to get integer constant from " + insn.getOpcode() + " : " + insn);
    }
}
