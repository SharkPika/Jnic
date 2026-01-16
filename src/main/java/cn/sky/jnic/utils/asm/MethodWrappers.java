package cn.sky.jnic.utils.asm;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

public class MethodWrappers {
    public static List<MethodWrapper> from(ClassWrapper classWrapper) {
        ClassNode classNode = classWrapper.getClassNode();
        List<MethodWrapper> methodWrappers = new ArrayList<>();
        classNode.methods.forEach(methodNode -> methodWrappers.add(MethodWrapper.from(methodNode, classWrapper)));

        return methodWrappers;
    }
}
