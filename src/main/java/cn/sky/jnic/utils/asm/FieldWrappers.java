package cn.sky.jnic.utils.asm;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

public class FieldWrappers {
    public static List<FieldWrapper> from(ClassWrapper classWrapper) {
        ClassNode classNode = classWrapper.getClassNode();
        List<FieldWrapper> fieldWrappers = new ArrayList<>();
        classNode.fields.forEach(fieldNode -> fieldWrappers.add(FieldWrapper.from(fieldNode, classWrapper)));

        return fieldWrappers;
    }
}
