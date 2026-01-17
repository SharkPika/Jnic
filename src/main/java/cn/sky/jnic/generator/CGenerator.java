package cn.sky.jnic.generator;

import cn.sky.jnic.Jnic;
import cn.sky.jnic.config.Config;
import cn.sky.jnic.utils.asm.ClassWrapper;
import cn.sky.jnic.utils.asm.MethodWrapper;
import cn.sky.jnic.process.NativeProcessor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CGenerator {
    private final Config config;
    private final NativeProcessor processor;
    private final Obfuscator obfuscator;
    private final StringBuilder globalCode = new StringBuilder();

    // Helper class for registration
    private static class NativeEntry {
        String className;
        String methodName;
        String signature;
        String cFunctionName;
        boolean isStatic;

        NativeEntry(String c, String m, String s, String cf, boolean stat) {
            className = c;
            methodName = m;
            signature = s;
            cFunctionName = cf;
            isStatic = stat;
        }
    }

    private final List<NativeEntry> nativeEntries = new ArrayList<>();
    private final Map<String, String> generatedMethods = new HashMap<>(); // Legacy map
    private final StringBuilder functionPrototypes = new StringBuilder(); // For forward declarations

    // State for current method generation
    private List<TryCatchBlockNode> currentTryCatchBlocks;
    private Map<LabelNode, Integer> currentLabelMap;
    private Frame<BasicValue>[] currentFrames;
    private String currentClassName; // Internal name
    private String currentMethodName;
    private ClassWrapper currentClass;

    public CGenerator(NativeProcessor processor) {
        this.processor = processor;
        this.config = processor.getJnic().getConfig();
        this.obfuscator = new Obfuscator(config);

        // Add headers
        // Use standard JNI header (must be provided in include path or same directory)
        globalCode.append("#include \"jni.h\"\n");
        globalCode.append("#include <stdint.h>\n");
        globalCode.append("#include <stdlib.h>\n");
        globalCode.append("#include <string.h>\n");
        globalCode.append("#include <stdio.h>\n");
        globalCode.append("#include <math.h>\n");
        globalCode.append("#include <stdarg.h>\n\n");

        // Define StackValue union
        globalCode.append("typedef union {\n");
        globalCode.append("    jint i;\n");
        globalCode.append("    jlong j;\n");
        globalCode.append("    jfloat f;\n");
        globalCode.append("    jdouble d;\n");
        globalCode.append("    jobject l;\n");
        globalCode.append("} StackValue;\n\n");

        // Add helper functions
        globalCode.append(getHelperFunctions());

        // Placeholder for prototypes - will be inserted here in finalizeGeneration if
        // we use a different structure
        // But since we append linearly, we need to insert them BEFORE methods.
        // We can't insert into StringBuilder easily at specific index without shifting.
        // Better: store methods in a separate buffer and combine at the end.
    }

    // Buffer for method implementations
    private final StringBuilder methodImplementations = new StringBuilder();

    private String getHelperFunctions() {
        return """
                // ==================== 调试日志 ====================
                #ifndef JNIC_DEBUG
                #define JNIC_DEBUG 0
                #endif
                #if JNIC_DEBUG
                void log_debug(const char* format, ...) {
                    FILE *f = fopen("native_debug.log", "a");
                    if (f) {
                        va_list args;
                        va_start(args, format);
                        vfprintf(f, format, args);
                        va_end(args);
                        fclose(f);
                    }
                }
                #else
                void log_debug(const char* format, ...) { (void)format; }
                #endif

                // ==================== 全局缓存 ====================
                static JavaVM* g_jvm = NULL;
                static jclass g_cls_String = NULL;
                static jclass g_cls_StringBuilder = NULL;
                static jclass g_cls_Object = NULL;
                static jclass g_cls_Class = NULL;
                static jclass g_cls_System = NULL;
                static jclass g_cls_Math = NULL;
                static jclass g_cls_Arrays = NULL;
                static jclass g_cls_NullPointerException = NULL;
                static jclass g_cls_ArrayIndexOutOfBoundsException = NULL;
                static jclass g_cls_ArithmeticException = NULL;
                static jclass g_cls_ClassCastException = NULL;

                static jmethodID g_mid_String_length = NULL;
                static jmethodID g_mid_String_hashCode = NULL;
                static jmethodID g_mid_String_charAt = NULL;
                static jmethodID g_mid_StringBuilder_init = NULL;
                static jmethodID g_mid_StringBuilder_append_String = NULL;
                static jmethodID g_mid_StringBuilder_append_int = NULL;
                static jmethodID g_mid_StringBuilder_append_long = NULL;
                static jmethodID g_mid_StringBuilder_append_double = NULL;
                static jmethodID g_mid_StringBuilder_append_Object = NULL;
                static jmethodID g_mid_StringBuilder_toString = NULL;
                static jmethodID g_mid_Object_getClass = NULL;
                static jmethodID g_mid_Object_hashCode = NULL;
                static jmethodID g_mid_Object_toString = NULL;
                static jmethodID g_mid_Class_getName = NULL;
                static jmethodID g_mid_System_arraycopy = NULL;

                // ==================== 缓存初始化 ====================
                static int g_cache_initialized = 0;
                static int g_cache_initializing = 0;

                jclass get_or_cache_class(JNIEnv* env, jclass* cache, const char* name) {
                    jclass cls = *cache;
                    if (cls != NULL) return cls;
                    jclass tmp = (*env)->FindClass(env, name);
                    if (tmp == NULL) {
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        return NULL;
                    }
                    cls = (*env)->NewGlobalRef(env, tmp);
                    (*env)->DeleteLocalRef(env, tmp);
                    *cache = cls;
                    return cls;
                }

                void init_global_cache(JNIEnv* env) {
                    if (env == NULL) return;
                    if (g_cache_initialized) return;
                    if (g_cache_initializing) return;
                    g_cache_initializing = 1;

                    jclass tmp;

                    #define CACHE_CLASS(var, name) \\
                        if ((var) == NULL) { \\
                            tmp = (*env)->FindClass(env, name); \\
                            if (tmp) { var = (*env)->NewGlobalRef(env, tmp); (*env)->DeleteLocalRef(env, tmp); } \\
                            else { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); } \\
                        }

                    CACHE_CLASS(g_cls_String, "java/lang/String");
                    CACHE_CLASS(g_cls_StringBuilder, "java/lang/StringBuilder");
                    CACHE_CLASS(g_cls_Object, "java/lang/Object");
                    CACHE_CLASS(g_cls_Class, "java/lang/Class");
                    CACHE_CLASS(g_cls_System, "java/lang/System");
                    CACHE_CLASS(g_cls_Math, "java/lang/Math");
                    CACHE_CLASS(g_cls_Arrays, "java/util/Arrays");
                    CACHE_CLASS(g_cls_NullPointerException, "java/lang/NullPointerException");
                    CACHE_CLASS(g_cls_ArrayIndexOutOfBoundsException, "java/lang/ArrayIndexOutOfBoundsException");
                    CACHE_CLASS(g_cls_ArithmeticException, "java/lang/ArithmeticException");
                    CACHE_CLASS(g_cls_ClassCastException, "java/lang/ClassCastException");

                    #undef CACHE_CLASS

                    if (g_cls_String) {
                        if (g_mid_String_length == NULL) g_mid_String_length = (*env)->GetMethodID(env, g_cls_String, "length", "()I");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_String_hashCode == NULL) g_mid_String_hashCode = (*env)->GetMethodID(env, g_cls_String, "hashCode", "()I");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_String_charAt == NULL) g_mid_String_charAt = (*env)->GetMethodID(env, g_cls_String, "charAt", "(I)C");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    }
                    if (g_cls_StringBuilder) {
                        if (g_mid_StringBuilder_init == NULL) g_mid_StringBuilder_init = (*env)->GetMethodID(env, g_cls_StringBuilder, "<init>", "()V");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_append_String == NULL) g_mid_StringBuilder_append_String = (*env)->GetMethodID(env, g_cls_StringBuilder, "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_append_int == NULL) g_mid_StringBuilder_append_int = (*env)->GetMethodID(env, g_cls_StringBuilder, "append", "(I)Ljava/lang/StringBuilder;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_append_long == NULL) g_mid_StringBuilder_append_long = (*env)->GetMethodID(env, g_cls_StringBuilder, "append", "(J)Ljava/lang/StringBuilder;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_append_double == NULL) g_mid_StringBuilder_append_double = (*env)->GetMethodID(env, g_cls_StringBuilder, "append", "(D)Ljava/lang/StringBuilder;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_append_Object == NULL) g_mid_StringBuilder_append_Object = (*env)->GetMethodID(env, g_cls_StringBuilder, "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_StringBuilder_toString == NULL) g_mid_StringBuilder_toString = (*env)->GetMethodID(env, g_cls_StringBuilder, "toString", "()Ljava/lang/String;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    }
                    if (g_cls_Object) {
                        if (g_mid_Object_getClass == NULL) g_mid_Object_getClass = (*env)->GetMethodID(env, g_cls_Object, "getClass", "()Ljava/lang/Class;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_Object_hashCode == NULL) g_mid_Object_hashCode = (*env)->GetMethodID(env, g_cls_Object, "hashCode", "()I");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        if (g_mid_Object_toString == NULL) g_mid_Object_toString = (*env)->GetMethodID(env, g_cls_Object, "toString", "()Ljava/lang/String;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    }
                    if (g_cls_Class) {
                        if (g_mid_Class_getName == NULL) g_mid_Class_getName = (*env)->GetMethodID(env, g_cls_Class, "getName", "()Ljava/lang/String;");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    }
                    if (g_cls_System) {
                        if (g_mid_System_arraycopy == NULL) g_mid_System_arraycopy = (*env)->GetStaticMethodID(env, g_cls_System, "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    }

                    g_cache_initialized = 1;
                    g_cache_initializing = 0;
                }

                // ==================== 字符串解密 ====================
                char* decrypt_string_len(const unsigned char* encrypted, int len, int key) {
                    char* decrypted = (char*)malloc((size_t)len + 1);
                    if (decrypted == NULL) return NULL;
                    for (int i = 0; i < len; i++) decrypted[i] = (char)(encrypted[i] ^ key);
                    decrypted[len] = 0;
                    return decrypted;
                }

                void throw_npe(JNIEnv* env, const char* msg);
                void throw_aioobe(JNIEnv* env, const char* msg);
                void throw_arith(JNIEnv* env, const char* msg);

                // ==================== C 层内联实现 ====================
                // String.equals - 直接比较字符
                jboolean inline_string_equals(JNIEnv *env, jobject s1, jobject s2) {
                    init_global_cache(env);
                    if (s1 == s2) return JNI_TRUE;
                    if (s1 == NULL) return JNI_FALSE;
                    if (s2 == NULL) return JNI_FALSE;
                    jclass stringCls = g_cls_String;
                    jclass localStringCls = NULL;
                    if (stringCls == NULL) {
                        localStringCls = (*env)->FindClass(env, "java/lang/String");
                        if (localStringCls == NULL) {
                            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                            return JNI_FALSE;
                        }
                        stringCls = localStringCls;
                    }
                    if (!(*env)->IsInstanceOf(env, s2, stringCls)) {
                        if (localStringCls != NULL) (*env)->DeleteLocalRef(env, localStringCls);
                        return JNI_FALSE;
                    }
                    if (localStringCls != NULL) (*env)->DeleteLocalRef(env, localStringCls);

                    jint len1 = (*env)->GetStringLength(env, (jstring)s1);
                    jint len2 = (*env)->GetStringLength(env, (jstring)s2);
                    if (len1 != len2) return JNI_FALSE;

                    const jchar* c1 = (*env)->GetStringChars(env, (jstring)s1, NULL);
                    const jchar* c2 = (*env)->GetStringChars(env, (jstring)s2, NULL);
                    jboolean eq = JNI_TRUE;
                    for (int i = 0; i < len1; i++) {
                        if (c1[i] != c2[i]) { eq = JNI_FALSE; break; }
                    }
                    (*env)->ReleaseStringChars(env, (jstring)s1, c1);
                    (*env)->ReleaseStringChars(env, (jstring)s2, c2);
                    return eq;
                }

                // String.length - 直接 JNI
                jint inline_string_length(JNIEnv* env, jstring s) {
                    return s ? (*env)->GetStringLength(env, s) : 0;
                }

                // String.hashCode - Java 规范算法
                jint inline_string_hashCode(JNIEnv* env, jstring s) {
                    if (s == NULL) return 0;
                    jint len = (*env)->GetStringLength(env, s);
                    if (len == 0) return 0;
                    const jchar* chars = (*env)->GetStringChars(env, s, NULL);
                    jint h = 0;
                    for (int i = 0; i < len; i++) {
                        h = 31 * h + chars[i];
                    }
                    (*env)->ReleaseStringChars(env, s, chars);
                    return h;
                }

                // String.charAt
                jchar inline_string_charAt(JNIEnv* env, jstring s, jint index) {
                    if (s == NULL) return 0;
                    jint len = (*env)->GetStringLength(env, s);
                    if (index < 0 || index >= len) {
                        throw_aioobe(env, "String index out of range");
                        return 0;
                    }
                    const jchar* chars = (*env)->GetStringChars(env, s, NULL);
                    jchar c = chars[index];
                    (*env)->ReleaseStringChars(env, s, chars);
                    return c;
                }

                // Object.getClass
                jclass inline_object_getClass(JNIEnv* env, jobject obj) {
                    return obj ? (*env)->GetObjectClass(env, obj) : NULL;
                }

                // System.arraycopy - 使用 JNI 批量操作
                void inline_system_arraycopy(JNIEnv* env, jobject src, jint srcPos, jobject dest, jint destPos, jint length) {
                    if (src == NULL || dest == NULL) {
                        throw_npe(env, "arraycopy: null array");
                        return;
                    }
                    init_global_cache(env);
                    if (g_cls_System != NULL && g_mid_System_arraycopy != NULL) {
                        (*env)->CallStaticVoidMethod(env, g_cls_System, g_mid_System_arraycopy, src, srcPos, dest, destPos, length);
                        return;
                    }
                    jclass sysCls = (*env)->FindClass(env, "java/lang/System");
                    if (sysCls == NULL) {
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        return;
                    }
                    jmethodID mid = (*env)->GetStaticMethodID(env, sysCls, "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");
                    if (mid == NULL) {
                        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                        (*env)->DeleteLocalRef(env, sysCls);
                        return;
                    }
                    (*env)->CallStaticVoidMethod(env, sysCls, mid, src, srcPos, dest, destPos, length);
                    (*env)->DeleteLocalRef(env, sysCls);
                }

                // Math 函数 - 直接 C 实现
                jdouble inline_math_abs_d(jdouble a) { return fabs(a); }
                jfloat inline_math_abs_f(jfloat a) { return fabsf(a); }
                jint inline_math_abs_i(jint a) { return a < 0 ? -a : a; }
                jlong inline_math_abs_l(jlong a) { return a < 0 ? -a : a; }
                jdouble inline_math_max_d(jdouble a, jdouble b) { return a > b ? a : b; }
                jdouble inline_math_min_d(jdouble a, jdouble b) { return a < b ? a : b; }
                jint inline_math_max_i(jint a, jint b) { return a > b ? a : b; }
                jint inline_math_min_i(jint a, jint b) { return a < b ? a : b; }
                jdouble inline_math_sin(jdouble a) { return sin(a); }
                jdouble inline_math_cos(jdouble a) { return cos(a); }
                jdouble inline_math_tan(jdouble a) { return tan(a); }
                jdouble inline_math_sqrt(jdouble a) { return sqrt(a); }
                jdouble inline_math_pow(jdouble a, jdouble b) { return pow(a, b); }
                jdouble inline_math_log(jdouble a) { return log(a); }
                jdouble inline_math_exp(jdouble a) { return exp(a); }
                jdouble inline_math_floor(jdouble a) { return floor(a); }
                jdouble inline_math_ceil(jdouble a) { return ceil(a); }
                jdouble inline_math_round(jdouble a) { return round(a); }

                // 抛出异常辅助
                void throw_npe(JNIEnv* env, const char* msg) {
                    init_global_cache(env);
                    jclass cls = get_or_cache_class(env, &g_cls_NullPointerException, "java/lang/NullPointerException");
                    if (cls) (*env)->ThrowNew(env, cls, msg);
                }
                void throw_aioobe(JNIEnv* env, const char* msg) {
                    init_global_cache(env);
                    jclass cls = get_or_cache_class(env, &g_cls_ArrayIndexOutOfBoundsException, "java/lang/ArrayIndexOutOfBoundsException");
                    if (cls) (*env)->ThrowNew(env, cls, msg);
                }
                void throw_arith(JNIEnv* env, const char* msg) {
                    init_global_cache(env);
                    jclass cls = get_or_cache_class(env, &g_cls_ArithmeticException, "java/lang/ArithmeticException");
                    if (cls) (*env)->ThrowNew(env, cls, msg);
                }

                """;
    }

    private String generateExceptionHandling(int index, Type returnType) {
        if (currentTryCatchBlocks == null || currentTryCatchBlocks.isEmpty()) {
            // Default behavior: check and return if exception
            StringBuilder sb = new StringBuilder();
            sb.append("    if ((*env)->ExceptionCheck(env)) {\n");
            if (returnType.getSort() == Type.VOID) {
                sb.append("        return;\n");
            } else {
                sb.append("        return 0;\n");
            }
            sb.append("    }\n");
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    if ((*env)->ExceptionCheck(env)) {\n");
        sb.append("        jthrowable ex = (*env)->ExceptionOccurred(env);\n");
        sb.append("        (*env)->ExceptionClear(env);\n");

        for (TryCatchBlockNode tcb : currentTryCatchBlocks) {
            Integer start = currentLabelMap.get(tcb.start);
            Integer end = currentLabelMap.get(tcb.end);
            Integer handler = currentLabelMap.get(tcb.handler);

            if (start != null && end != null && handler != null && index >= start && index < end) {
                if (tcb.type == null) {
                    sb.append("        stack[sp++].l = ex;\n");
                    sb.append("        goto L").append(tcb.handler.hashCode()).append(";\n");
                } else {
                    sb.append("        {\n");
                    sb.append("            jclass tc_cls = (*env)->FindClass(env, \"").append(tcb.type)
                            .append("\");\n");
                    sb.append("            if (tc_cls == NULL) {\n");
                    sb.append("                (*env)->ExceptionClear(env);\n");
                    sb.append("            } else {\n");
                    sb.append("                jboolean match = (*env)->IsInstanceOf(env, ex, tc_cls);\n");
                    sb.append("                (*env)->DeleteLocalRef(env, tc_cls);\n");
                    sb.append("                if (match) {\n");
                    sb.append("                    stack[sp++].l = ex;\n");
                    sb.append("                    goto L").append(tcb.handler.hashCode()).append(";\n");
                    sb.append("                }\n");
                    sb.append("            }\n");
                    sb.append("        }\n");
                }
            }
        }

        // Fallthrough: rethrow
        sb.append("        (*env)->Throw(env, ex);\n");
        if (returnType.getSort() == Type.VOID) {
            sb.append("        return;\n");
        } else {
            sb.append("        return 0;\n");
        }
        sb.append("    }\n");
        return sb.toString();
    }

    public String generateMethod(ClassWrapper owner, MethodWrapper method) {
        StringBuilder methodBody = new StringBuilder();
        // Use hex string to avoid negative hash code issues and ensure valid C
        // identifier
        String functionName = "native_" + Integer.toHexString(method.getOriginalName().hashCode()) + "_"
                + Integer.toHexString(owner.getName().hashCode());
        Type returnType = Type.getReturnType(method.getOriginalDescriptor());

        // Method Signature
        methodBody.append("JNIEXPORT ").append(getJNIType(returnType)).append(" JNICALL ").append(functionName)
                .append("(JNIEnv *env, jobject thiz");
        Type[] argTypes = Type.getArgumentTypes(method.getOriginalDescriptor());
        for (int i = 0; i < argTypes.length; i++) {
            methodBody.append(", ").append(getJNIType(argTypes[i])).append(" arg").append(i);
        }
        methodBody.append(") {\n");

        // Forward declaration
        functionPrototypes.append("JNIEXPORT ").append(getJNIType(returnType)).append(" JNICALL ").append(functionName)
                .append("(JNIEnv *env, jobject thiz");
        for (int i = 0; i < argTypes.length; i++) {
            functionPrototypes.append(", ").append(getJNIType(argTypes[i])).append(" arg").append(i);
        }
        functionPrototypes.append(");\n");

        // Anti-Debug Injection
        methodBody.append(obfuscator.getAntiDebugCode());

        // Body Generation
        StringBuilder bodyContent = new StringBuilder();

        // Stack and Locals initialization
        int maxStack = method.getMethodNode().maxStack + 10; // Safety buffer
        int maxLocals = method.getMethodNode().maxLocals + 10;

        // Append declarations directly to methodBody or a prologue buffer that gets
        // used
        StringBuilder prologue = new StringBuilder();
        prologue.append("    StackValue stack[").append(maxStack).append("];\n");
        prologue.append("    memset(stack, 0, sizeof(stack));\n");
        prologue.append("    StackValue locals[").append(maxLocals).append("];\n");
        prologue.append("    memset(locals, 0, sizeof(locals));\n");
        // prologue.append(" int sp = 0;\n\n"); // Optimized out

        // Initialize locals from arguments
        int localIndex = 0;
        if (!method.isStatic()) {
            prologue.append("    locals[").append(localIndex++).append("].l = thiz;\n");
        }
        for (int i = 0; i < argTypes.length; i++) {
            Type type = argTypes[i];
            prologue.append("    locals[").append(localIndex).append("].").append(getTypeField(type)).append(" = arg")
                    .append(i).append(";\n");
            localIndex += type.getSize();
        }
        prologue.append("\n");

        // Append prologue to methodBody
        methodBody.append(prologue);
        methodBody.append("    log_debug(\"Enter: ").append(functionName)
                .append(" env: %p, thiz: %p\\n\", env, thiz);\n");
        methodBody.append("    if ((*env)->PushLocalFrame(env, 256) < 0) {\n");
        if (returnType.getSort() == Type.VOID) {
            methodBody.append("        return;\n");
        } else {
            methodBody.append("        return 0;\n");
        }
        methodBody.append("    }\n");

        InsnList instructions = method.getMethodNode().instructions;

        // Analyze stack for optimization
        Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
        try {
            currentFrames = analyzer.analyze(owner.getName(), method.getMethodNode());
        } catch (AnalyzerException e) {
            e.printStackTrace();
            throw new RuntimeException("Stack analysis failed for " + method.getOriginalName(), e);
        }

        // Map labels to C case IDs
        Map<LabelNode, Integer> labelMap = new HashMap<>();
        int instructionIndex = 0;
        for (AbstractInsnNode insn : instructions) {
            if (insn instanceof LabelNode) {
                labelMap.put((LabelNode) insn, instructionIndex);
            }
            instructionIndex++;
        }

        currentTryCatchBlocks = method.getMethodNode().tryCatchBlocks;
        currentLabelMap = labelMap;
        currentClassName = owner.getName();
        currentMethodName = method.getOriginalName();
        currentClass = owner;

        // Generate Code (Linear)
        StringBuilder cBody = new StringBuilder();
        int currentIndex = 0;
        for (AbstractInsnNode insn : instructions) {
            // Insert Label
            for (Map.Entry<LabelNode, Integer> entry : labelMap.entrySet()) {
                if (entry.getValue() == currentIndex) {
                    cBody.append("L").append(entry.getKey().hashCode()).append(":;\n");
                }
            }

            cBody.append(generateInstruction(insn, labelMap, currentIndex, returnType));
            currentIndex++;

        }

        methodBody.append(cBody);

        // Default return for safety (void or zero)
        methodBody.append("    (*env)->PopLocalFrame(env, NULL);\n");
        if (returnType.getSort() == Type.VOID) {
            methodBody.append("    return;\n");
        } else {
            methodBody.append("    return 0;\n");
        }

        methodBody.append("}\n\n");

        String fullCode = methodBody.toString();
        methodImplementations.append(fullCode); // Append to buffer instead of globalCode

        generatedMethods.put(owner.getName() + "_" + method.getOriginalName(), functionName);
        nativeEntries.add(new NativeEntry(owner.getName(), method.getOriginalName(), method.getOriginalDescriptor(),
                functionName, method.isStatic()));

        // Clear state
        currentTryCatchBlocks = null;
        currentLabelMap = null;
        currentFrames = null;
        currentClassName = null;

        return fullCode;
    }

    private int getCompressedStackSize(Frame<BasicValue> frame) {
        int size = 0;
        for (int i = 0; i < frame.getStackSize(); i++) {
            size++;
            if (frame.getStack(i).getSize() == 2) {
                if (i + 1 < frame.getStackSize()) {
                    BasicValue next = frame.getStack(i + 1);
                    if (next != null && next.equals(BasicValue.UNINITIALIZED_VALUE)) {
                        i++;
                    }
                }
            }
        }
        return size;
    }

    private String getTypeField(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return "i";
            case Type.FLOAT:
                return "f";
            case Type.LONG:
                return "j";
            case Type.DOUBLE:
                return "d";
            case Type.ARRAY:
            case Type.OBJECT:
                return "l";
            default:
                return "l";
        }
    }

    private boolean isUnconditionalJump(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW ||
                opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH;
    }

    private boolean isReturn(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    private String generateInstruction(AbstractInsnNode insn, Map<LabelNode, Integer> labelMap, int currentIndex,
            Type returnType) {
        // Handle Frame/Label nodes which are virtual
        if (insn instanceof FrameNode || insn instanceof LabelNode || insn instanceof LineNumberNode) {
            return ""; // No code generation needed, just pass through to next state
        }

        Frame<BasicValue> frame = currentFrames[currentIndex];
        if (frame == null) {
            return ""; // Unreachable code
        }
        int sp = getCompressedStackSize(frame);

        StringBuilder code = new StringBuilder();
        // code.append(" // Instruction Index: ").append(currentIndex).append(" Opcode:
        // ").append(insn.getOpcode())
        // .append("\n");
        code.append("    { int sp = ").append(sp).append(";\n");

        int opcode = insn.getOpcode();

        switch (opcode) {
            case Opcodes.NOP:
                break;
            case Opcodes.ACONST_NULL:
                code.append("    stack[sp++].l = NULL;\n");
                break;
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
                code.append("    stack[sp++].i = ").append(opcode - Opcodes.ICONST_0).append(";\n");
                break;
            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
                code.append("    stack[sp++].j = ").append(opcode - Opcodes.LCONST_0).append("LL;\n");
                break;
            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2:
                code.append("    stack[sp++].f = ").append(opcode - Opcodes.FCONST_0).append(".0f;\n");
                break;
            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1:
                code.append("    stack[sp++].d = ").append(opcode - Opcodes.DCONST_0).append(".0;\n");
                break;
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                code.append("    stack[sp++].i = ").append(((IntInsnNode) insn).operand).append(";\n");
                break;
            case Opcodes.LDC:
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (ldc.cst instanceof Integer) {
                    code.append("    stack[sp++].i = ").append(ldc.cst).append(";\n");
                } else if (ldc.cst instanceof Float) {
                    code.append("    stack[sp++].f = ").append(ldc.cst).append("f;\n");
                } else if (ldc.cst instanceof Long) {
                    code.append("    stack[sp++].j = ").append(ldc.cst).append("LL;\n");
                } else if (ldc.cst instanceof Double) {
                    code.append("    stack[sp++].d = ").append(ldc.cst).append(";\n");
                } else if (ldc.cst instanceof String) {
                    code.append("    {\n");
                    int sid = Math.abs(insn.hashCode());
                    code.append("        static jstring cached_").append(sid).append(" = NULL;\n");
                    code.append("        if (cached_").append(sid).append(" == NULL) {\n");
                    if (config.isStringEncryption()) {
                        Obfuscator.EncryptedString enc = obfuscator.encryptStringData((String) ldc.cst);
                        code.append("            const unsigned char enc_").append(sid).append("[] = ")
                                .append(enc.cArrayLiteral()).append(";\n");
                        code.append("            char* dec_").append(sid).append(" = decrypt_string_len(enc_")
                                .append(sid).append(", ").append(enc.length()).append(", ").append(enc.key())
                                .append(");\n");
                        code.append("            if (dec_").append(sid).append(" == NULL) {\n");
                        if (returnType.getSort() == Type.VOID) {
                            code.append("                return;\n");
                        } else {
                            code.append("                return 0;\n");
                        }
                        code.append("            }\n");
                        code.append("            jstring tmp = (*env)->NewStringUTF(env, dec_").append(sid)
                                .append(");\n");
                        code.append("            free(dec_").append(sid).append(");\n");
                        code.append("            if (tmp == NULL) {\n");
                        if (returnType.getSort() == Type.VOID) {
                            code.append("                return;\n");
                        } else {
                            code.append("                return 0;\n");
                        }
                        code.append("            }\n");
                        code.append("            cached_").append(sid).append(" = (*env)->NewGlobalRef(env, tmp);\n");
                        code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                    } else {
                        code.append("            jstring tmp = (*env)->NewStringUTF(env, ")
                                .append(obfuscator.encryptString((String) ldc.cst)).append(");\n");
                        code.append("            if (tmp == NULL) {\n");
                        if (returnType.getSort() == Type.VOID) {
                            code.append("                return;\n");
                        } else {
                            code.append("                return 0;\n");
                        }
                        code.append("            }\n");
                        code.append("            cached_").append(sid).append(" = (*env)->NewGlobalRef(env, tmp);\n");
                        code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                    }
                    code.append("            if (cached_").append(sid).append(" == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("                return;\n");
                    } else {
                        code.append("                return 0;\n");
                    }
                    code.append("            }\n");
                    code.append("        }\n");
                    code.append("        stack[sp++].l = (*env)->NewLocalRef(env, cached_").append(sid).append(");\n");
                    code.append("    }\n");
                } else if (ldc.cst instanceof Type) {
                    Type type = (Type) ldc.cst;
                    code.append("    {\n");
                    code.append("        static jclass cached_").append(Math.abs(insn.hashCode())).append(" = NULL;\n");
                    code.append("        if (cached_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                    code.append("            jclass tmp = (*env)->FindClass(env, \"").append(type.getInternalName())
                            .append("\");\n");
                    code.append("            if (tmp == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("                return;\n");
                    } else {
                        code.append("                return 0;\n");
                    }
                    code.append("            }\n");
                    code.append("            cached_").append(Math.abs(insn.hashCode()))
                            .append(" = (*env)->NewGlobalRef(env, tmp);\n");
                    code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                    code.append("            if (cached_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("                return;\n");
                    } else {
                        code.append("                return 0;\n");
                    }
                    code.append("            }\n");
                    code.append("        }\n");
                    code.append("        stack[sp++].l = (*env)->NewLocalRef(env, cached_")
                            .append(Math.abs(insn.hashCode())).append(");\n");
                    code.append("    }\n");
                }
                break;

            // Loads
            case Opcodes.ILOAD:
                code.append("    stack[sp++].i = locals[").append(((VarInsnNode) insn).var).append("].i;\n");
                break;
            case Opcodes.LLOAD:
                code.append("    stack[sp++].j = locals[").append(((VarInsnNode) insn).var).append("].j;\n");
                break;
            case Opcodes.FLOAD:
                code.append("    stack[sp++].f = locals[").append(((VarInsnNode) insn).var).append("].f;\n");
                break;
            case Opcodes.DLOAD:
                code.append("    stack[sp++].d = locals[").append(((VarInsnNode) insn).var).append("].d;\n");
                break;
            case Opcodes.ALOAD:
                code.append("    stack[sp++].l = (*env)->NewLocalRef(env, locals[").append(((VarInsnNode) insn).var)
                        .append("].l);\n");
                break;

            // Field Access
            case Opcodes.GETSTATIC:
            case Opcodes.PUTSTATIC:
            case Opcodes.GETFIELD:
            case Opcodes.PUTFIELD:
                FieldInsnNode finsn = (FieldInsnNode) insn;
                String fieldName = finsn.name;
                String fieldDesc = finsn.desc;
                String fieldOwner = finsn.owner;
                Type fieldType = Type.getType(fieldDesc);
                boolean isStaticField = (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC);
                boolean isPut = (opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD);

                if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                    /* PushLocalFrame removed */
                }

                String fieldHash = String.valueOf(Math.abs(insn.hashCode()));

                // Get FieldID with Caching
                code.append("    static jfieldID fid_").append(fieldHash).append(" = NULL;\n");
                if (isStaticField) {
                    code.append("    static jclass fcls_").append(fieldHash).append(" = NULL;\n");
                    code.append("    if (fid_").append(fieldHash).append(" == NULL) {\n");
                    code.append("        jclass tmp = (*env)->FindClass(env, \"").append(fieldOwner).append("\");\n");
                    code.append("        if (tmp == NULL) {\n");
                    if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                        /* PopLocalFrame removed */
                    }
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    code.append("        fcls_").append(fieldHash).append(" = (*env)->NewGlobalRef(env, tmp);\n");
                    code.append("        if (fcls_").append(fieldHash).append(" == NULL) {\n");
                    code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                    if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                        /* PopLocalFrame removed */
                    }
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    code.append("        fid_").append(fieldHash).append(" = (*env)->GetStaticFieldID(env, fcls_")
                            .append(fieldHash).append(", \"").append(fieldName).append("\", \"").append(fieldDesc)
                            .append("\");\n");
                    code.append("        (*env)->DeleteLocalRef(env, tmp);\n");
                    code.append("    }\n");
                } else {
                    code.append("    if (fid_").append(fieldHash).append(" == NULL) {\n");
                    code.append("        jclass tmp = (*env)->FindClass(env, \"").append(fieldOwner).append("\");\n");
                    code.append("        if (tmp == NULL) {\n");
                    if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                        /* PopLocalFrame removed */
                    }
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    code.append("        fid_").append(fieldHash).append(" = (*env)->GetFieldID(env, tmp, \"")
                            .append(fieldName).append("\", \"").append(fieldDesc).append("\");\n");
                    code.append("        (*env)->DeleteLocalRef(env, tmp);\n");
                    code.append("    }\n");
                }

                code.append("    if (fid_").append(fieldHash).append(" == NULL) {\n");
                if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                    /* PopLocalFrame removed */
                }
                if (returnType.getSort() == Type.VOID) {
                    code.append("        return;\n");
                } else {
                    code.append("        return 0;\n");
                }
                code.append("    }\n");

                String typeName = getJNICallType(fieldType);
                String unionField = getTypeField(fieldType);

                if (isPut) {
                    // PUTFIELD/PUTSTATIC
                    code.append("    ");
                    String valVar = "val_" + fieldHash;
                    code.append(getJNIType(fieldType)).append(" ").append(valVar).append(" = stack[--sp].")
                            .append(unionField).append(";\n");

                    code.append("    jobject obj_").append(fieldHash).append(" = ");
                    if (isStaticField) {
                        code.append("NULL;\n");
                    } else {
                        code.append("stack[--sp].l;\n"); // Pop object ref
                    }

                    if (!isStaticField) {
                        code.append("    if (obj_").append(fieldHash).append(" == NULL) {\n");
                        code.append(
                                "        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                        code.append("        if (npeCls != NULL) {\n");
                        code.append("            (*env)->ThrowNew(env, npeCls, \"Null pointer access\");\n");
                        code.append("        }\n");
                        if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                            /* PopLocalFrame removed */
                        }
                        if (returnType.getSort() == Type.VOID) {
                            code.append("        return;\n");
                        } else {
                            code.append("        return 0;\n");
                        }
                        code.append("    }\n");
                    }

                    String setFunc = isStaticField ? "SetStatic" + typeName + "Field" : "Set" + typeName + "Field";
                    code.append("    (*env)->").append(setFunc).append("(env, ");
                    if (isStaticField) {
                        code.append("fcls_").append(fieldHash);
                    } else {
                        code.append("obj_").append(fieldHash);
                    }
                    code.append(", fid_").append(fieldHash).append(", ").append(valVar).append(");\n");

                    if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                        /* PopLocalFrame removed */
                    }

                } else {
                    // GETFIELD/GETSTATIC
                    String getFunc = isStaticField ? "GetStatic" + typeName + "Field" : "Get" + typeName + "Field";

                    code.append("    jobject obj_").append(fieldHash).append(" = ");
                    if (isStaticField) {
                        code.append("NULL;\n");
                    } else {
                        code.append("stack[--sp].l;\n"); // Pop object ref
                    }

                    if (!isStaticField) {
                        code.append("    if (obj_").append(fieldHash).append(" == NULL) {\n");
                        code.append(
                                "        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                        code.append("        if (npeCls != NULL) {\n");
                        code.append("            (*env)->ThrowNew(env, npeCls, \"Null pointer access\");\n");
                        code.append("        }\n");
                        if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                            /* PopLocalFrame removed */
                        }
                        if (returnType.getSort() == Type.VOID) {
                            code.append("        return;\n");
                        } else {
                            code.append("        return 0;\n");
                        }
                        code.append("    }\n");
                    }

                    code.append("    ").append(getJNIType(fieldType)).append(" res_").append(fieldHash)
                            .append(" = (*env)->").append(getFunc).append("(env, ");
                    if (isStaticField) {
                        code.append("fcls_").append(fieldHash);
                    } else {
                        code.append("obj_").append(fieldHash);
                    }
                    code.append(", fid_").append(fieldHash).append(");\n");

                    code.append("    stack[sp++].").append(unionField).append(" = res_").append(fieldHash)
                            .append(";\n");

                    if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                        /* PopLocalFrame removed */
                    }
                }
                code.append(generateExceptionHandling(currentIndex, returnType));
                break;

            // Array Operations
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.AALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
                code.append("    {\n");
                code.append("        jint idx = stack[--sp].i;\n");
                if (opcode == Opcodes.AALOAD) {
                    code.append("        jobjectArray arr = (jobjectArray)stack[--sp].l;\n");
                    code.append("        jobject val = NULL;\n");
                    code.append("        if (arr != NULL) {\n");
                    code.append("            val = (*env)->GetObjectArrayElement(env, arr, idx);\n");
                    code.append("        } else {\n");
                    code.append(
                            "            jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                    code.append("            if (npeCls != NULL) {\n");
                    code.append("                (*env)->ThrowNew(env, npeCls, \"Array is null\");\n");
                    code.append("            }\n");
                    code.append("        }\n");
                    code.append("        stack[sp++].l = val;\n");
                } else {
                    String valType, stackField, funcName, arrayCast;
                    if (opcode == Opcodes.IALOAD) {
                        valType = "jint";
                        stackField = "i";
                        funcName = "GetIntArrayRegion";
                        arrayCast = "jintArray";
                    } else if (opcode == Opcodes.LALOAD) {
                        valType = "jlong";
                        stackField = "j";
                        funcName = "GetLongArrayRegion";
                        arrayCast = "jlongArray";
                    } else if (opcode == Opcodes.FALOAD) {
                        valType = "jfloat";
                        stackField = "f";
                        funcName = "GetFloatArrayRegion";
                        arrayCast = "jfloatArray";
                    } else if (opcode == Opcodes.DALOAD) {
                        valType = "jdouble";
                        stackField = "d";
                        funcName = "GetDoubleArrayRegion";
                        arrayCast = "jdoubleArray";
                    } else if (opcode == Opcodes.CALOAD) {
                        valType = "jchar";
                        stackField = "i";
                        funcName = "GetCharArrayRegion";
                        arrayCast = "jcharArray";
                    } else if (opcode == Opcodes.SALOAD) {
                        valType = "jshort";
                        stackField = "i";
                        funcName = "GetShortArrayRegion";
                        arrayCast = "jshortArray";
                    } else {
                        /* BALOAD */ valType = "jint";
                        stackField = "i";
                        funcName = "GetByteArrayRegion";
                        arrayCast = "jbyteArray";
                    }

                    code.append("        ").append(arrayCast).append(" arr = (").append(arrayCast)
                            .append(")stack[--sp].l;\n");
                    code.append("        ").append(valType).append(" val = 0;\n");
                    code.append("        if (arr != NULL) {\n");

                    if (opcode == Opcodes.BALOAD) {
                        code.append(
                                "            if ((*env)->IsInstanceOf(env, arr, (*env)->FindClass(env, \"[Z\"))) {\n");
                        code.append("                jboolean b = 0;\n");
                        code.append(
                                "                (*env)->GetBooleanArrayRegion(env, (jbooleanArray)arr, idx, 1, &b);\n");
                        code.append("                val = b;\n");
                        code.append("            } else {\n");
                        code.append("                jbyte b = 0;\n");
                        code.append("                (*env)->GetByteArrayRegion(env, (jbyteArray)arr, idx, 1, &b);\n");
                        code.append("                val = b;\n");
                        code.append("            }\n");
                    } else {
                        code.append("            (*env)->").append(funcName).append("(env, arr, idx, 1, &val);\n");
                    }

                    code.append("        } else {\n");
                    code.append(
                            "            jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                    code.append("            if (npeCls != NULL) {\n");
                    code.append("                (*env)->ThrowNew(env, npeCls, \"Array is null\");\n");
                    code.append("            }\n");
                    code.append("        }\n");
                    code.append("        stack[sp++].").append(stackField).append(" = val;\n");
                }
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    }\n");
                break;

            // Stack Manipulation
            case Opcodes.POP: {
                BasicValue val = frame.getStack(frame.getStackSize() - 1);
                if (isReferenceType(val)) {
                    code.append("    if (stack[sp-1].l != NULL) {\n");
                    code.append("        (*env)->DeleteLocalRef(env, stack[sp-1].l);\n");
                    code.append("    }\n");
                }
                code.append("    sp--;\n");
            }
                break;
            case Opcodes.POP2: {
                BasicValue val1 = frame.getStack(frame.getStackSize() - 1);
                if (val1.getSize() == 2) {
                    // Long/Double. Pop 1 slot.
                    code.append("    sp--;\n");
                } else {
                    // val1 is size 1. val2 must be size 1.
                    if (isReferenceType(val1)) {
                        code.append("    if (stack[sp-1].l != NULL) {\n");
                        code.append("        (*env)->DeleteLocalRef(env, stack[sp-1].l);\n");
                        code.append("    }\n");
                    }

                    BasicValue val2 = frame.getStack(frame.getStackSize() - 2);
                    if (isReferenceType(val2)) {
                        code.append("    if (stack[sp-2].l != NULL) {\n");
                        code.append("        (*env)->DeleteLocalRef(env, stack[sp-2].l);\n");
                        code.append("    }\n");
                    }
                    code.append("    sp -= 2;\n");
                }
            }
                break;
            case Opcodes.DUP: {
                BasicValue val = frame.getStack(frame.getStackSize() - 1);
                if (isReferenceType(val)) {
                    code.append("    stack[sp].l = (*env)->NewLocalRef(env, stack[sp-1].l);\n");
                    code.append("    sp++;\n");
                } else {
                    code.append("    stack[sp] = stack[sp-1]; sp++;\n");
                }
            }
                break;
            case Opcodes.DUP_X1:
            // ..., value2, value1 -> ..., value1, value2, value1
            {
                BasicValue val1 = frame.getStack(frame.getStackSize() - 1);
                code.append("    stack[sp] = stack[sp-1];\n");
                code.append("    stack[sp-1] = stack[sp-2];\n");
                code.append("    stack[sp-2] = stack[sp];\n");
                if (isReferenceType(val1)) {
                    code.append("    stack[sp].l = (*env)->NewLocalRef(env, stack[sp].l);\n");
                }
                code.append("    sp++;\n");
            }
                break;
            case Opcodes.DUP_X2:
            // ..., value3, value2, value1 -> ..., value1, value3, value2, value1
            {
                BasicValue val1 = frame.getStack(frame.getStackSize() - 1);
                BasicValue val2 = frame.getStack(frame.getStackSize() - 2);

                if (val2.getSize() == 2) {
                    // Form 2: value1, value2(long). C stack: sp-1(val1), sp-2(val2).
                    // Like DUP_X1 in C.
                    code.append("    stack[sp] = stack[sp-1];\n");
                    code.append("    stack[sp-1] = stack[sp-2];\n");
                    code.append("    stack[sp-2] = stack[sp];\n");
                    if (isReferenceType(val1)) {
                        code.append("    stack[sp].l = (*env)->NewLocalRef(env, stack[sp].l);\n");
                    }
                    code.append("    sp++;\n");
                } else {
                    // Form 1: value1, value2, value3.
                    code.append("    stack[sp] = stack[sp-1];\n");
                    code.append("    stack[sp-1] = stack[sp-2];\n");
                    code.append("    stack[sp-2] = stack[sp-3];\n");
                    code.append("    stack[sp-3] = stack[sp];\n");
                    if (isReferenceType(val1)) {
                        code.append("    stack[sp].l = (*env)->NewLocalRef(env, stack[sp].l);\n");
                    }
                    code.append("    sp++;\n");
                }
            }
                break;
            case Opcodes.DUP2: {
                BasicValue val1 = frame.getStack(frame.getStackSize() - 1);
                if (val1.getSize() == 2) {
                    // Form 2: value1(long).
                    // In C, this is DUP.
                    code.append("    stack[sp] = stack[sp-1];\n");
                    code.append("    sp++;\n");
                } else {
                    // Form 1: value1, value2.
                    BasicValue val2 = frame.getStack(frame.getStackSize() - 2);

                    code.append("    stack[sp] = stack[sp-2];\n");
                    if (isReferenceType(val2)) {
                        code.append("    stack[sp].l = (*env)->NewLocalRef(env, stack[sp].l);\n");
                    }

                    code.append("    stack[sp+1] = stack[sp-1];\n");
                    if (isReferenceType(val1)) {
                        code.append("    stack[sp+1].l = (*env)->NewLocalRef(env, stack[sp+1].l);\n");
                    }
                    code.append("    sp += 2;\n");
                }
            }
                break;
            case Opcodes.SWAP:
                code.append("    StackValue tmp_").append(Math.abs(insn.hashCode())).append(" = stack[sp-1];\n");
                code.append("    stack[sp-1] = stack[sp-2];\n");
                code.append("    stack[sp-2] = tmp_").append(Math.abs(insn.hashCode())).append(";\n");
                break;

            // Object Creation
            case Opcodes.NEW:
                /* PushLocalFrame removed */
                TypeInsnNode typeInsn = (TypeInsnNode) insn;
                // code.append(" printf(\"New: ").append(typeInsn.desc).append("\\n\");
                // fflush(stdout);\n");
                code.append("    jclass cls_").append(Math.abs(insn.hashCode())).append(" = (*env)->FindClass(env, \"")
                        .append(typeInsn.desc).append("\");\n");
                code.append("    if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("        return;\n");
                } else {
                    code.append("        return 0;\n");
                }
                code.append("    }\n");
                code.append("    jobject obj_").append(Math.abs(insn.hashCode()))
                        .append(" = (*env)->AllocObject(env, cls_").append(Math.abs(insn.hashCode())).append(");\n");
                code.append("    stack[sp++].l = obj_").append(Math.abs(insn.hashCode())).append(";\n");
                code.append("    if ((*env)->ExceptionCheck(env)) {\n");
                code.append("        jthrowable ex = (*env)->ExceptionOccurred(env);\n");
                code.append("        (*env)->ExceptionClear(env);\n");

                code.append("        (*env)->Throw(env, ex);\n");
                code.append("        stack[sp-1].l = NULL;\n");
                code.append("    } else {\n");
                code.append("        stack[sp-1].l = stack[sp-1].l;\n");
                code.append("    }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                break;
            case Opcodes.NEWARRAY:
                IntInsnNode newarr = (IntInsnNode) insn;
                code.append("    jsize len_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].i;\n");
                String arrayFunc = getNewArrayFunc(newarr.operand);
                code.append("    stack[sp++].l = (*env)->").append(arrayFunc).append("(env, len_")
                        .append(Math.abs(insn.hashCode())).append(");\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                break;
            case Opcodes.ANEWARRAY:
                /* PushLocalFrame removed */
                TypeInsnNode anew = (TypeInsnNode) insn;
                code.append("    jsize len_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].i;\n");
                code.append("    jclass cls_").append(Math.abs(insn.hashCode())).append(" = (*env)->FindClass(env, \"")
                        .append(anew.desc).append("\");\n");
                code.append("    if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("        return;\n");
                } else {
                    code.append("        return 0;\n");
                }
                code.append("    }\n");
                code.append("    stack[sp++].l = (*env)->NewObjectArray(env, len_").append(Math.abs(insn.hashCode()))
                        .append(", cls_").append(Math.abs(insn.hashCode())).append(", NULL);\n");
                /* PopLocalFrame removed */
                code.append("    (*env)->ExceptionCheck(env);\n");
                break;
            case Opcodes.ARRAYLENGTH:
                code.append("    if (stack[sp-1].l == NULL) {\n");
                code.append("        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                code.append("        if (npeCls != NULL) {\n");
                code.append("            (*env)->ThrowNew(env, npeCls, \"Array is null\");\n");
                code.append("        }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    } else {\n");
                code.append("        stack[sp-1].i = (*env)->GetArrayLength(env, (jarray)stack[sp-1].l);\n");
                code.append("    }\n");
                break;
            case Opcodes.ATHROW:
                code.append("    {\n");
                code.append("        jobject ex = stack[--sp].l;\n");
                code.append("        (*env)->Throw(env, (jthrowable)ex);\n");
                code.append("    }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                break;
            case Opcodes.CHECKCAST:
                TypeInsnNode checkcast = (TypeInsnNode) insn;
                code.append("    if (stack[sp-1].l != NULL) {\n");
                code.append("        static jclass cls_").append(Math.abs(insn.hashCode())).append(" = NULL;\n");
                code.append("        if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                code.append("            jclass tmp = (*env)->FindClass(env, \"").append(checkcast.desc)
                        .append("\");\n");
                code.append("            if (tmp == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("                return;\n");
                } else {
                    code.append("                return 0;\n");
                }
                code.append("            }\n");
                code.append("            cls_").append(Math.abs(insn.hashCode()))
                        .append(" = (*env)->NewGlobalRef(env, tmp);\n");
                code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                code.append("            if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("                return;\n");
                } else {
                    code.append("                return 0;\n");
                }
                code.append("            }\n");
                code.append("        }\n");
                code.append("        if (!(*env)->IsInstanceOf(env, stack[sp-1].l, cls_")
                        .append(Math.abs(insn.hashCode())).append(")) {\n");
                code.append("            jclass castEx = (*env)->FindClass(env, \"java/lang/ClassCastException\");\n");
                code.append("            if (castEx != NULL) {\n");
                code.append("                (*env)->ThrowNew(env, castEx, \"").append(checkcast.desc).append("\");\n");
                code.append("            }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("        }\n");
                code.append("    }\n");
                break;
            case Opcodes.INSTANCEOF:
                TypeInsnNode instanceofInsn = (TypeInsnNode) insn;
                code.append("    {\n");
                code.append("        static jclass cls_").append(Math.abs(insn.hashCode())).append(" = NULL;\n");
                code.append("        if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                code.append("            jclass tmp = (*env)->FindClass(env, \"").append(instanceofInsn.desc)
                        .append("\");\n");
                code.append("            if (tmp == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("                return;\n");
                } else {
                    code.append("                return 0;\n");
                }
                code.append("            }\n");
                code.append("            cls_").append(Math.abs(insn.hashCode()))
                        .append(" = (*env)->NewGlobalRef(env, tmp);\n");
                code.append("            (*env)->DeleteLocalRef(env, tmp);\n");
                code.append("            if (cls_").append(Math.abs(insn.hashCode())).append(" == NULL) {\n");
                if (returnType.getSort() == Type.VOID) {
                    code.append("                return;\n");
                } else {
                    code.append("                return 0;\n");
                }
                code.append("            }\n");
                code.append("        }\n");
                code.append("        stack[sp-1].i = (*env)->IsInstanceOf(env, stack[sp-1].l, cls_")
                        .append(Math.abs(insn.hashCode())).append(");\n");
                code.append("    }\n");
                break;
            case Opcodes.MONITORENTER:
                code.append("    (*env)->MonitorEnter(env, stack[--sp].l);\n");
                break;
            case Opcodes.MONITOREXIT:
                code.append("    (*env)->MonitorExit(env, stack[--sp].l);\n");
                break;

            // Stores
            case Opcodes.ISTORE:
                code.append("    locals[").append(((VarInsnNode) insn).var).append("].i = stack[--sp].i;\n");
                break;
            case Opcodes.LSTORE:
                code.append("    locals[").append(((VarInsnNode) insn).var).append("].j = stack[--sp].j;\n");
                break;
            case Opcodes.FSTORE:
                code.append("    locals[").append(((VarInsnNode) insn).var).append("].f = stack[--sp].f;\n");
                break;
            case Opcodes.DSTORE:
                code.append("    locals[").append(((VarInsnNode) insn).var).append("].d = stack[--sp].d;\n");
                break;
            case Opcodes.ASTORE:
                int var = ((VarInsnNode) insn).var;
                code.append("    {\n");
                code.append("        jobject tmp_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].l;\n");
                code.append("        if (locals[").append(var).append("].l != NULL && locals[").append(var)
                        .append("].l != tmp_").append(Math.abs(insn.hashCode())).append(") {\n");
                code.append("            (*env)->DeleteLocalRef(env, locals[").append(var).append("].l);\n");
                code.append("        }\n");
                code.append("        locals[").append(var).append("].l = tmp_").append(Math.abs(insn.hashCode()))
                        .append(";\n");
                code.append("    }\n");
                break;

            // Math
            case Opcodes.IADD:
                code.append("    sp--; __asm__(\"add %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LADD:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j + stack[sp].j;\n");
                break;
            case Opcodes.FADD:
                code.append("    sp--; stack[sp-1].f = stack[sp-1].f + stack[sp].f;\n");
                break;
            case Opcodes.DADD:
                code.append("    sp--; stack[sp-1].d = stack[sp-1].d + stack[sp].d;\n");
                break;

            case Opcodes.ISUB:
                code.append("    sp--; __asm__(\"sub %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LSUB:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j - stack[sp].j;\n");
                break;
            case Opcodes.FSUB:
                code.append("    sp--; stack[sp-1].f = stack[sp-1].f - stack[sp].f;\n");
                break;
            case Opcodes.DSUB:
                code.append("    sp--; stack[sp-1].d = stack[sp-1].d - stack[sp].d;\n");
                break;

            case Opcodes.IMUL:
                code.append("    sp--; __asm__(\"imul %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LMUL:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j * stack[sp].j;\n");
                break;
            case Opcodes.FMUL:
                code.append("    sp--; stack[sp-1].f = stack[sp-1].f * stack[sp].f;\n");
                break;
            case Opcodes.DMUL:
                code.append("    sp--; stack[sp-1].d = stack[sp-1].d * stack[sp].d;\n");
                break;

            case Opcodes.IDIV:
                code.append("    sp--;\n");
                code.append("    if (stack[sp].i == 0) {\n");
                code.append("        jclass arithEx = (*env)->FindClass(env, \"java/lang/ArithmeticException\");\n");
                code.append("        if (arithEx != NULL) {\n");
                code.append("            (*env)->ThrowNew(env, arithEx, \"/ by zero\");\n");
                code.append("        }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    } else {\n");
                code.append("        stack[sp-1].i = stack[sp-1].i / stack[sp].i;\n");
                code.append("    }\n");
                break;
            case Opcodes.LDIV:
                code.append("    sp--;\n");
                code.append("    if (stack[sp].j == 0) {\n");
                code.append("        jclass arithEx = (*env)->FindClass(env, \"java/lang/ArithmeticException\");\n");
                code.append("        if (arithEx != NULL) {\n");
                code.append("            (*env)->ThrowNew(env, arithEx, \"/ by zero\");\n");
                code.append("        }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    } else {\n");
                code.append("        stack[sp-1].j = stack[sp-1].j / stack[sp].j;\n");
                code.append("    }\n");
                break;
            case Opcodes.FDIV:
                code.append("    sp--; stack[sp-1].f = stack[sp-1].f / stack[sp].f;\n");
                break;
            case Opcodes.DDIV:
                code.append("    sp--; stack[sp-1].d = stack[sp-1].d / stack[sp].d;\n");
                break;

            case Opcodes.IREM:
                code.append("    sp--;\n");
                code.append("    if (stack[sp].i == 0) {\n");
                code.append("        jclass arithEx = (*env)->FindClass(env, \"java/lang/ArithmeticException\");\n");
                code.append("        if (arithEx != NULL) {\n");
                code.append("            (*env)->ThrowNew(env, arithEx, \"/ by zero\");\n");
                code.append("        }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    } else {\n");
                code.append("        stack[sp-1].i = stack[sp-1].i % stack[sp].i;\n");
                code.append("    }\n");
                break;
            case Opcodes.LREM:
                code.append("    sp--;\n");
                code.append("    if (stack[sp].j == 0) {\n");
                code.append("        jclass arithEx = (*env)->FindClass(env, \"java/lang/ArithmeticException\");\n");
                code.append("        if (arithEx != NULL) {\n");
                code.append("            (*env)->ThrowNew(env, arithEx, \"/ by zero\");\n");
                code.append("        }\n");
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    } else {\n");
                code.append("        stack[sp-1].j = stack[sp-1].j % stack[sp].j;\n");
                code.append("    }\n");
                break;
            case Opcodes.FREM:
                code.append("    sp--; stack[sp-1].f = (jfloat)fmod(stack[sp-1].f, stack[sp].f);\n");
                break;
            case Opcodes.DREM:
                code.append("    sp--; stack[sp-1].d = fmod(stack[sp-1].d, stack[sp].d);\n");
                break;

            case Opcodes.INEG:
                code.append("    __asm__(\"neg %0\" : \"+r\"(stack[sp-1].i));\n");
                break;
            case Opcodes.LNEG:
                code.append("    stack[sp-1].j = -stack[sp-1].j;\n");
                break;
            case Opcodes.FNEG:
                code.append("    stack[sp-1].f = -stack[sp-1].f;\n");
                break;
            case Opcodes.DNEG:
                code.append("    stack[sp-1].d = -stack[sp-1].d;\n");
                break;

            case Opcodes.ISHL:
                code.append("    sp--; stack[sp-1].i = stack[sp-1].i << stack[sp].i;\n");
                break;
            case Opcodes.LSHL:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j << stack[sp].i;\n");
                break;
            case Opcodes.ISHR:
                code.append("    sp--; stack[sp-1].i = stack[sp-1].i >> stack[sp].i;\n");
                break;
            case Opcodes.LSHR:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j >> stack[sp].i;\n");
                break;
            case Opcodes.IUSHR:
                code.append("    sp--; stack[sp-1].i = (jint)((unsigned int)stack[sp-1].i >> stack[sp].i);\n");
                break;
            case Opcodes.LUSHR:
                code.append("    sp--; stack[sp-1].j = (jlong)((unsigned long long)stack[sp-1].j >> stack[sp].i);\n");
                break;

            case Opcodes.IAND:
                code.append("    sp--; __asm__(\"and %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LAND:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j & stack[sp].j;\n");
                break;
            case Opcodes.IOR:
                code.append("    sp--; __asm__(\"or %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LOR:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j | stack[sp].j;\n");
                break;
            case Opcodes.IXOR:
                code.append("    sp--; __asm__(\"xor %1, %0\" : \"+r\"(stack[sp-1].i) : \"r\"(stack[sp].i));\n");
                break;
            case Opcodes.LXOR:
                code.append("    sp--; stack[sp-1].j = stack[sp-1].j ^ stack[sp].j;\n");
                break;

            case Opcodes.IINC:
                IincInsnNode iinc = (IincInsnNode) insn;
                code.append("    __asm__(\"add %1, %0\" : \"+r\"(locals[").append(iinc.var).append("].i) : \"r\"(")
                        .append(iinc.incr).append("));\n");
                break;

            case Opcodes.LCMP:
                code.append("    sp--;\n");
                code.append("    if (stack[sp-1].j > stack[sp].j) stack[sp-1].i = 1;\n");
                code.append("    else if (stack[sp-1].j == stack[sp].j) stack[sp-1].i = 0;\n");
                code.append("    else stack[sp-1].i = -1;\n");
                break;
            case Opcodes.FCMPL:
            case Opcodes.FCMPG:
                // TODO: Handle NaN correctly for L/G
                code.append("    sp--;\n");
                code.append("    if (stack[sp-1].f > stack[sp].f) stack[sp-1].i = 1;\n");
                code.append("    else if (stack[sp-1].f == stack[sp].f) stack[sp-1].i = 0;\n");
                code.append("    else if (stack[sp-1].f < stack[sp].f) stack[sp-1].i = -1;\n");
                code.append("    else stack[sp-1].i = ").append(opcode == Opcodes.FCMPG ? "1" : "-1").append(";\n");
                break;
            case Opcodes.DCMPL:
            case Opcodes.DCMPG:
                // TODO: Handle NaN correctly
                code.append("    sp--;\n");
                code.append("    if (stack[sp-1].d > stack[sp].d) stack[sp-1].i = 1;\n");
                code.append("    else if (stack[sp-1].d == stack[sp].d) stack[sp-1].i = 0;\n");
                code.append("    else if (stack[sp-1].d < stack[sp].d) stack[sp-1].i = -1;\n");
                code.append("    else stack[sp-1].i = ").append(opcode == Opcodes.DCMPG ? "1" : "-1").append(";\n");
                break;

            // Array Stores
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.AASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                code.append("    {\n");
                if (opcode == Opcodes.AASTORE) {
                    code.append("        jobject val = stack[--sp].l;\n");
                    code.append("        jint idx = stack[--sp].i;\n");
                    code.append("        jobjectArray arr = (jobjectArray)stack[--sp].l;\n");
                    code.append("        if (arr != NULL) {\n");
                    code.append("            (*env)->SetObjectArrayElement(env, arr, idx, val);\n");
                    code.append("        } else {\n");
                    code.append(
                            "            jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                    code.append("            if (npeCls != NULL) {\n");
                    code.append("                (*env)->ThrowNew(env, npeCls, \"Array is null\");\n");
                    code.append("            }\n");
                    code.append("        }\n");
                } else {
                    String valType, stackField, funcName, arrayCast;
                    if (opcode == Opcodes.IASTORE) {
                        valType = "jint";
                        stackField = "i";
                        funcName = "SetIntArrayRegion";
                        arrayCast = "jintArray";
                    } else if (opcode == Opcodes.LASTORE) {
                        valType = "jlong";
                        stackField = "j";
                        funcName = "SetLongArrayRegion";
                        arrayCast = "jlongArray";
                    } else if (opcode == Opcodes.FASTORE) {
                        valType = "jfloat";
                        stackField = "f";
                        funcName = "SetFloatArrayRegion";
                        arrayCast = "jfloatArray";
                    } else if (opcode == Opcodes.DASTORE) {
                        valType = "jdouble";
                        stackField = "d";
                        funcName = "SetDoubleArrayRegion";
                        arrayCast = "jdoubleArray";
                    } else if (opcode == Opcodes.CASTORE) {
                        valType = "jchar";
                        stackField = "i";
                        funcName = "SetCharArrayRegion";
                        arrayCast = "jcharArray";
                    } else if (opcode == Opcodes.SASTORE) {
                        valType = "jshort";
                        stackField = "i";
                        funcName = "SetShortArrayRegion";
                        arrayCast = "jshortArray";
                    } else {
                        /* BASTORE */ valType = "jint";
                        stackField = "i";
                        funcName = "SetByteArrayRegion";
                        arrayCast = "jbyteArray";
                    }

                    code.append("        ").append(valType).append(" val = stack[--sp].").append(stackField)
                            .append(";\n");
                    code.append("        jint idx = stack[--sp].i;\n");
                    code.append("        jarray arr = (jarray)stack[--sp].l;\n");
                    code.append("        if (arr != NULL) {\n");

                    if (opcode == Opcodes.BASTORE) {
                        code.append(
                                "            if ((*env)->IsInstanceOf(env, arr, (*env)->FindClass(env, \"[Z\"))) {\n");
                        code.append("                jboolean b = (jboolean)val;\n");
                        code.append(
                                "                (*env)->SetBooleanArrayRegion(env, (jbooleanArray)arr, idx, 1, &b);\n");
                        code.append("            } else {\n");
                        code.append("                jbyte b = (jbyte)val;\n");
                        code.append("                (*env)->SetByteArrayRegion(env, (jbyteArray)arr, idx, 1, &b);\n");
                        code.append("            }\n");
                    } else {
                        code.append("            (*env)->").append(funcName).append("(env, (").append(arrayCast)
                                .append(")arr, idx, 1, &val);\n");
                    }

                    code.append("        } else {\n");
                    code.append(
                            "            jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                    code.append("            if (npeCls != NULL) {\n");
                    code.append("                (*env)->ThrowNew(env, npeCls, \"Array is null\");\n");
                    code.append("            }\n");
                    code.append("        }\n");
                }
                code.append(generateExceptionHandling(currentIndex, returnType));
                code.append("    }\n");
                break;

            // Method Calls
            case Opcodes.INVOKEVIRTUAL:
            case Opcodes.INVOKESTATIC:
            case Opcodes.INVOKESPECIAL:
            case Opcodes.INVOKEINTERFACE:
                MethodInsnNode minsn = (MethodInsnNode) insn;
                String methodName = minsn.name;
                String methodDesc = minsn.desc;
                String ownerClass = minsn.owner;
                Type callReturnType = Type.getReturnType(methodDesc);
                Type[] argTypes = Type.getArgumentTypes(methodDesc);

                String methodHash = String.valueOf(Math.abs(insn.hashCode()));

                // ==================== C 层内联函数路由 ====================
                // 检查是否可以使用 C 层内联实现
                String inlineCode = getInlineImplementation(ownerClass, methodName, methodDesc, methodHash, returnType);
                if (inlineCode != null) {
                    code.append(inlineCode);
                    break;
                }
                // ==================== End 内联路由 ====================

                // Optimize: Direct C call for same-class static/private methods
                boolean isStatic = (opcode == Opcodes.INVOKESTATIC);
                boolean isSpecial = (opcode == Opcodes.INVOKESPECIAL);

                // Pop arguments from stack (Common for both paths)
                /* Comment removed */
                // To support both without complex logic branching here, we'll implement JNI
                // call first,
                // then Refactor for Direct Call if condition met.

                // To enable direct calls, we need to know if it's safe.
                // User requirement: "Same Class methods use static function"
                // Let's assume we can try to link. If the symbol is missing, C compiler fails.
                // But we don't want build failure.
                // So we should only do it for methods we KNOW we are generating.
                // Since we don't have a global view here, we rely on the deterministic name.

                // NOTE: We need to know the current class name to check "Same Class".
                // I will update generateMethod to store currentClassName.

                /* PushLocalFrame removed */

                code.append("    jvalue args_").append(methodHash).append("[").append(Math.max(1, argTypes.length))
                        .append("];\n");
                code.append("    memset(args_").append(methodHash).append(", 0, sizeof(args_").append(methodHash)
                        .append("));\n");
                for (int i = argTypes.length - 1; i >= 0; i--) {
                    Type argType = argTypes[i];
                    String field = getTypeField(argType);
                    if (argType.getSort() == Type.OBJECT || argType.getSort() == Type.ARRAY) {
                        code.append("    {\n");
                        code.append("        jobject tmp = stack[--sp].l;\n");
                        code.append("        if (tmp != NULL) {\n");
                        code.append("            args_").append(methodHash).append("[").append(i).append("].l = tmp;\n");
                        code.append("        } else {\n");
                        code.append("            args_").append(methodHash).append("[").append(i)
                                .append("].l = NULL;\n");
                        code.append("        }\n");
                        code.append("    }\n");
                    } else {
                        code.append("    args_").append(methodHash).append("[").append(i).append("].").append(field)
                                .append(" = stack[--sp].").append(field).append(";\n");
                    }
                }

                // Optimized Direct Call Logic
                // Check if target is native-ized
                boolean isNativeTarget = processor.isNative(ownerClass, methodName, methodDesc);

                boolean canDirectCall = isStatic || isSpecial;
                if (!canDirectCall && isNativeTarget) {
                    ClassWrapper ownerCW = processor.getJnic().getClasses().get(ownerClass);
                    if (ownerCW != null) {
                        if (ownerCW.isFinal()) {
                            canDirectCall = true;
                        } else {
                            MethodNode mn = ownerCW.getMethodNode(methodName, methodDesc);
                            if (mn != null && (mn.access & Opcodes.ACC_FINAL) != 0) {
                                canDirectCall = true;
                            }
                        }
                    }
                }

                if (canDirectCall && isNativeTarget
                        && !methodName.startsWith("<") && !methodName.startsWith("indy_wrapper_")) {
                    // Direct Call
                    // Use hex string here too
                    String cFunc = "native_" + Integer.toHexString(methodName.hashCode()) + "_"
                            + Integer.toHexString(ownerClass.hashCode());

                    if (!isStatic) {
                        code.append("    jobject obj_").append(methodHash).append(";\n");
                        code.append("    {\n");
                        code.append("        jobject tmp = stack[--sp].l;\n");
                        code.append("        if (tmp != NULL) {\n");
                        code.append("            obj_").append(methodHash).append(" = tmp;\n");
                        code.append("        } else {\n");
                        code.append("            obj_").append(methodHash).append(" = NULL;\n");
                        code.append("        }\n");
                        code.append("    }\n");
                        code.append("    if (obj_").append(methodHash).append(" == NULL) {\n");
                        code.append(
                                "        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                        code.append("        if (npeCls != NULL) {\n");
                        code.append("            (*env)->ThrowNew(env, npeCls, \"Null pointer access\");\n");
                        code.append("        }\n");
                        code.append("    } else {\n");

                        code.append("        ");
                        if (callReturnType.getSort() != Type.VOID) {
                            code.append(getJNIType(callReturnType)).append(" res_").append(methodHash).append(" = ");
                        }
                        code.append(cFunc).append("(env, obj_").append(methodHash);
                        for (int i = 0; i < argTypes.length; i++) {
                            code.append(", args_").append(methodHash).append("[").append(i).append("].")
                                    .append(getTypeField(argTypes[i]));
                        }
                        code.append(");\n");

                        if (callReturnType.getSort() != Type.VOID) {
                            code.append("        stack[sp++].").append(getTypeField(callReturnType)).append(" = res_")
                                    .append(methodHash).append(";\n");
                        }
                        code.append("    }\n");
                    } else {
                        code.append("    ");
                        if (callReturnType.getSort() != Type.VOID) {
                            code.append(getJNIType(callReturnType)).append(" res_").append(methodHash).append(" = ");
                        }
                        code.append(cFunc).append("(env, NULL");
                        for (int i = 0; i < argTypes.length; i++) {
                            code.append(", args_").append(methodHash).append("[").append(i).append("].")
                                    .append(getTypeField(argTypes[i]));
                        }
                        code.append(");\n");

                        if (callReturnType.getSort() != Type.VOID) {
                            code.append("    stack[sp++].").append(getTypeField(callReturnType)).append(" = res_")
                                    .append(methodHash).append(";\n");
                        }
                    }

                    /* PopLocalFrame removed */

                    code.append(generateExceptionHandling(currentIndex, returnType));

                } else if (ownerClass.equals("java/lang/String") && methodName.equals("equals")
                        && methodDesc.equals("(Ljava/lang/Object;)Z")) {
                    // Optimized String.equals implementation in C
                    // Args are already popped into args_...
                    // arg0: this (from stack), arg1: other (args_[0])
                    // Wait, args_... calculation above pops args in reverse order.
                    // Stack: [..., this, arg0, arg1]
                    // Loop pops: arg1 -> args[1], arg0 -> args[0].
                    // Then stack top is 'this'.
                    // For INVOKEVIRTUAL:
                    // stack[--sp].l is 'this'.

                    // We need to access 'this' and the argument.
                    // The 'args_' array logic above only captured the METHOD ARGUMENTS.
                    // 'this' is popped separately for INVOKEVIRTUAL/SPECIAL/INTERFACE.
                    // See below: "jobject obj_... = stack[--sp].l;"

                    // So we can insert our optimization here.

                    // 1. Get 'this' object
                    code.append("    jobject obj_").append(methodHash).append(";\n");
                    code.append("    {\n");
                    code.append("        jobject tmp = stack[--sp].l;\n");
                    code.append("        if (tmp != NULL) {\n");
                    code.append("            obj_").append(methodHash).append(" = tmp;\n");
                    code.append("        } else {\n");
                    code.append("            obj_").append(methodHash).append(" = NULL;\n");
                    code.append("        }\n");
                    code.append("    }\n");

                    // 2. Get argument (Object other)
                    code.append("    jobject other_").append(methodHash).append(" = args_").append(methodHash)
                            .append("[0].l;\n");

                    // 3. Check for NULL 'this' (Java throws NPE)
                    code.append("    if (obj_").append(methodHash).append(" == NULL) {\n");
                    code.append(
                            "        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                    code.append("        if (npeCls != NULL) {\n");
                    code.append("            (*env)->ThrowNew(env, npeCls, \"Null pointer access\");\n");
                    code.append("        }\n");
                    code.append("    } else {\n");

                    // 4. Call helper function
                    code.append("        jboolean res_").append(methodHash).append(" = inline_string_equals(env, obj_")
                            .append(methodHash).append(", other_").append(methodHash).append(");\n");
                    code.append("        stack[sp++].i = res_").append(methodHash).append(";\n");
                    code.append("    }\n");

                    code.append(generateExceptionHandling(currentIndex, returnType));

                } else {
                    // Standard JNI Call
                    code.append("    static jclass cls_").append(methodHash).append(" = NULL;\n");
                    code.append("    static jmethodID mid_").append(methodHash).append(" = NULL;\n");
                    code.append("    if (mid_").append(methodHash).append(" == NULL) {\n");
                    code.append("        jclass tmp = (*env)->FindClass(env, \"").append(ownerClass).append("\");\n");
                    code.append("        if (tmp == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    code.append("        cls_").append(methodHash).append(" = (*env)->NewGlobalRef(env, tmp);\n");
                    code.append("        (*env)->DeleteLocalRef(env, tmp);\n");
                    code.append("        if (cls_").append(methodHash).append(" == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    String midFunc = isStatic ? "GetStaticMethodID" : "GetMethodID";
                    code.append("        mid_").append(methodHash).append(" = (*env)->").append(midFunc)
                            .append("(env, cls_").append(methodHash).append(", \"").append(methodName).append("\", \"")
                            .append(methodDesc).append("\");\n");
                    code.append("        if (mid_").append(methodHash).append(" == NULL) {\n");
                    if (returnType.getSort() == Type.VOID) {
                        code.append("            return;\n");
                    } else {
                        code.append("            return 0;\n");
                    }
                    code.append("        }\n");
                    code.append("    }\n");

                    if (!isStatic) {
                        code.append("    jobject obj_").append(methodHash).append(";\n");
                        code.append("    {\n");
                        code.append("        jobject tmp = stack[--sp].l;\n");
                        code.append("        if (tmp != NULL) {\n");
                        code.append("            obj_").append(methodHash).append(" = tmp;\n");
                        code.append("        } else {\n");
                        code.append("            obj_").append(methodHash).append(" = NULL;\n");
                        code.append("        }\n");
                        code.append("    }\n");
                    }

                    if (!isStatic) {
                        code.append("    if (obj_").append(methodHash).append(" == NULL) {\n");
                        code.append(
                                "        jclass npeCls = (*env)->FindClass(env, \"java/lang/NullPointerException\");\n");
                        code.append("        if (npeCls != NULL) {\n");
                        code.append("            (*env)->ThrowNew(env, npeCls, \"Null pointer access\");\n");
                        code.append("        }\n");
                        if (returnType.getSort() == Type.VOID) {
                            code.append("        return;\n");
                        } else {
                            code.append("        return 0;\n");
                        }
                        code.append("    }\n");
                    }

                    String callType = getJNICallType(callReturnType);
                    String callFunc = isStatic ? "CallStatic" + callType + "MethodA" : "Call" + callType + "MethodA";

                    code.append("    ");
                    if (callReturnType.getSort() != Type.VOID) {
                        code.append(getJNIType(
                                callType.equals("Object") ? Type.getObjectType("java/lang/Object") : callReturnType))
                                .append(" res_").append(methodHash).append(" = ");
                    }
                    code.append("(*env)->").append(callFunc).append("(env, ");
                    if (isStatic) {
                        code.append("cls_").append(methodHash);
                    } else {
                        code.append("obj_").append(methodHash);
                    }
                    code.append(", mid_").append(methodHash).append(", args_").append(methodHash).append(");\n");

                    if (callReturnType.getSort() != Type.VOID) {
                        String field = getTypeField(callReturnType);
                        code.append("    stack[sp++].").append(field).append(" = res_").append(methodHash)
                                .append(";\n");
                    }

                    code.append(generateExceptionHandling(currentIndex, returnType));
                }
                break;

            // Branch Instructions
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
                JumpInsnNode jinsn = (JumpInsnNode) insn;
                int targetState = jinsn.label.hashCode();

                code.append("    if (stack[--sp].i ");
                switch (opcode) {
                    case Opcodes.IFEQ:
                        code.append("== 0");
                        break;
                    case Opcodes.IFNE:
                        code.append("!= 0");
                        break;
                    case Opcodes.IFLT:
                        code.append("< 0");
                        break;
                    case Opcodes.IFGE:
                        code.append(">= 0");
                        break;
                    case Opcodes.IFGT:
                        code.append("> 0");
                        break;
                    case Opcodes.IFLE:
                        code.append("<= 0");
                        break;
                }
                code.append(") { goto L").append(targetState).append("; }\n");
                break;

            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
                JumpInsnNode jinsn2 = (JumpInsnNode) insn;
                int targetState2 = jinsn2.label.hashCode();

                code.append("    jint v2_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].i;\n");
                code.append("    jint v1_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].i;\n");
                code.append("    if (v1_").append(Math.abs(insn.hashCode())).append(" ");
                switch (opcode) {
                    case Opcodes.IF_ICMPEQ:
                        code.append("==");
                        break;
                    case Opcodes.IF_ICMPNE:
                        code.append("!=");
                        break;
                    case Opcodes.IF_ICMPLT:
                        code.append("<");
                        break;
                    case Opcodes.IF_ICMPGE:
                        code.append(">=");
                        break;
                    case Opcodes.IF_ICMPGT:
                        code.append(">");
                        break;
                    case Opcodes.IF_ICMPLE:
                        code.append("<=");
                        break;
                }
                code.append(" v2_").append(Math.abs(insn.hashCode())).append(") { goto L").append(targetState2)
                        .append("; }\n");
                break;

            case Opcodes.IF_ACMPEQ:
            case Opcodes.IF_ACMPNE:
                JumpInsnNode jinsn3 = (JumpInsnNode) insn;
                int targetState3 = jinsn3.label.hashCode();

                code.append("    jobject v2_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].l;\n");
                code.append("    jobject v1_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].l;\n");
                if (opcode == Opcodes.IF_ACMPEQ) {
                    code.append("    if ((*env)->IsSameObject(env, v1_").append(Math.abs(insn.hashCode()))
                            .append(", v2_").append(Math.abs(insn.hashCode())).append(")) { goto L")
                            .append(targetState3).append("; }\n");
                } else {
                    code.append("    if (!(*env)->IsSameObject(env, v1_").append(Math.abs(insn.hashCode()))
                            .append(", v2_").append(Math.abs(insn.hashCode())).append(")) { goto L")
                            .append(targetState3).append("; }\n");
                }
                // We should also delete the refs?
                // They are popped.
                code.append("    if (v1_").append(Math.abs(insn.hashCode()))
                        .append(" != NULL) (*env)->DeleteLocalRef(env, v1_").append(Math.abs(insn.hashCode()))
                        .append(");\n");
                code.append("    if (v2_").append(Math.abs(insn.hashCode()))
                        .append(" != NULL) (*env)->DeleteLocalRef(env, v2_").append(Math.abs(insn.hashCode()))
                        .append(");\n");
                break;

            case Opcodes.GOTO:
                JumpInsnNode gotoInsn = (JumpInsnNode) insn;
                code.append("    goto L").append(gotoInsn.label.hashCode()).append(";\n");
                break;

            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
                JumpInsnNode jnull = (JumpInsnNode) insn;
                int targetNull = jnull.label.hashCode();
                code.append("    jobject vnull_").append(Math.abs(insn.hashCode())).append(" = stack[--sp].l;\n");
                code.append("    if (vnull_").append(Math.abs(insn.hashCode()))
                        .append(opcode == Opcodes.IFNULL ? " == " : " != ").append("NULL) { goto L").append(targetNull)
                        .append("; }\n");
                code.append("    if (vnull_").append(Math.abs(insn.hashCode()))
                        .append(" != NULL) (*env)->DeleteLocalRef(env, vnull_").append(Math.abs(insn.hashCode()))
                        .append(");\n");
                break;

            // Type Conversion
            case Opcodes.I2L:
                code.append("    stack[sp-1].j = (jlong)stack[sp-1].i;\n");
                break;
            case Opcodes.I2F:
                code.append("    stack[sp-1].f = (jfloat)stack[sp-1].i;\n");
                break;
            case Opcodes.I2D:
                code.append("    stack[sp-1].d = (jdouble)stack[sp-1].i;\n");
                break;
            case Opcodes.L2I:
                code.append("    stack[sp-1].i = (jint)stack[sp-1].j;\n");
                break;
            case Opcodes.L2F:
                code.append("    stack[sp-1].f = (jfloat)stack[sp-1].j;\n");
                break;
            case Opcodes.L2D:
                code.append("    stack[sp-1].d = (jdouble)stack[sp-1].j;\n");
                break;
            case Opcodes.F2I:
                code.append("    stack[sp-1].i = (jint)stack[sp-1].f;\n");
                break;
            case Opcodes.F2L:
                code.append("    stack[sp-1].j = (jlong)stack[sp-1].f;\n");
                break;
            case Opcodes.F2D:
                code.append("    stack[sp-1].d = (jdouble)stack[sp-1].f;\n");
                break;
            case Opcodes.D2I:
                code.append("    stack[sp-1].i = (jint)stack[sp-1].d;\n");
                break;
            case Opcodes.D2L:
                code.append("    stack[sp-1].j = (jlong)stack[sp-1].d;\n");
                break;
            case Opcodes.D2F:
                code.append("    stack[sp-1].f = (jfloat)stack[sp-1].d;\n");
                break;
            case Opcodes.I2B:
                code.append("    stack[sp-1].i = (jbyte)stack[sp-1].i;\n");
                break;
            case Opcodes.I2C:
                code.append("    stack[sp-1].i = (jchar)stack[sp-1].i;\n");
                break;
            case Opcodes.I2S:
                code.append("    stack[sp-1].i = (jshort)stack[sp-1].i;\n");
                break;

            // Returns
            case Opcodes.RETURN:
                code.append("    (*env)->PopLocalFrame(env, NULL);\n");
                code.append("    return;\n");
                break;
            case Opcodes.IRETURN:
                code.append("    (*env)->PopLocalFrame(env, NULL);\n");
                code.append("    return stack[--sp].i;\n");
                break;
            case Opcodes.LRETURN:
                code.append("    (*env)->PopLocalFrame(env, NULL);\n");
                code.append("    return stack[--sp].j;\n");
                break;
            case Opcodes.FRETURN:
                code.append("    (*env)->PopLocalFrame(env, NULL);\n");
                code.append("    return stack[--sp].f;\n");
                break;
            case Opcodes.DRETURN:
                code.append("    (*env)->PopLocalFrame(env, NULL);\n");
                code.append("    return stack[--sp].d;\n");
                break;
            case Opcodes.ARETURN:
                code.append("    return (*env)->PopLocalFrame(env, stack[--sp].l);\n");
                break;

            default:
                code.append("    // Unhandled opcode: ").append(opcode).append("\n");
                break;
        }
        code.append(" }\n");
        // code.append(" // End Instruction Index: ").append(currentIndex).append("\n");
        String result = code.toString();
        int braceCount = 0;
        for (char c : result.toCharArray()) {
            if (c == '{')
                braceCount++;
            else if (c == '}')
                braceCount--;
        }
        if (braceCount != 0) {
            throw new RuntimeException("Unbalanced braces in instruction " + currentIndex + " opcode " + opcode + ": "
                    + braceCount + "\nCode:\n" + result);
        }
        return result;
    }

    private String getNewArrayFunc(int type) {
        switch (type) {
            case Opcodes.T_BOOLEAN:
                return "NewBooleanArray";
            case Opcodes.T_CHAR:
                return "NewCharArray";
            case Opcodes.T_FLOAT:
                return "NewFloatArray";
            case Opcodes.T_DOUBLE:
                return "NewDoubleArray";
            case Opcodes.T_BYTE:
                return "NewByteArray";
            case Opcodes.T_SHORT:
                return "NewShortArray";
            case Opcodes.T_INT:
                return "NewIntArray";
            case Opcodes.T_LONG:
                return "NewLongArray";
            default:
                return "NewIntArray"; // Should not happen
        }
    }

    private String getJNICallType(Type type) {
        switch (type.getSort()) {
            case Type.VOID:
                return "Void";
            case Type.BOOLEAN:
                return "Boolean";
            case Type.CHAR:
                return "Char";
            case Type.BYTE:
                return "Byte";
            case Type.SHORT:
                return "Short";
            case Type.INT:
                return "Int";
            case Type.FLOAT:
                return "Float";
            case Type.LONG:
                return "Long";
            case Type.DOUBLE:
                return "Double";
            case Type.ARRAY:
            case Type.OBJECT:
                return "Object";
            default:
                return "Object";
        }
    }

    private String getJNIType(Type type) {
        switch (type.getSort()) {
            case Type.VOID:
                return "void";
            case Type.BOOLEAN:
                return "jboolean";
            case Type.CHAR:
                return "jchar";
            case Type.BYTE:
                return "jbyte";
            case Type.SHORT:
                return "jshort";
            case Type.INT:
                return "jint";
            case Type.FLOAT:
                return "jfloat";
            case Type.LONG:
                return "jlong";
            case Type.DOUBLE:
                return "jdouble";
            case Type.ARRAY:
            case Type.OBJECT:
                return "jobject";
            default:
                return "jobject";
        }
    }

    private boolean isReferenceType(BasicValue value) {
        if (value == null)
            return false;
        Type type = value.getType();
        return type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
    }

    private int getValueSize(BasicValue value) {
        return value.getSize();
    }

    /**
     * 检查方法是否可以使用 C 层内联实现，返回生成的 C 代码或 null
     */
    private String getInlineImplementation(String ownerClass, String methodName, String methodDesc, String methodHash,
            Type returnType) {
        StringBuilder code = new StringBuilder();

        // ==================== java/lang/String ====================
        if ("java/lang/String".equals(ownerClass)) {
            if ("equals".equals(methodName) && "(Ljava/lang/Object;)Z".equals(methodDesc)) {
                code.append("    jobject str_other_").append(methodHash).append(" = stack[--sp].l;\n");
                code.append("    jobject str_this_").append(methodHash).append(" = stack[--sp].l;\n");
                code.append("    if (str_this_").append(methodHash)
                        .append(" == NULL) { throw_npe(env, \"String.equals on null\"); }\n");
                code.append("    else { stack[sp++].i = inline_string_equals(env, str_this_").append(methodHash)
                        .append(", str_other_").append(methodHash).append("); }\n");
                return code.toString();
            }
            if ("length".equals(methodName) && "()I".equals(methodDesc)) {
                code.append("    jstring str_").append(methodHash).append(" = (jstring)stack[--sp].l;\n");
                code.append("    stack[sp++].i = inline_string_length(env, str_").append(methodHash).append(");\n");
                return code.toString();
            }
            if ("hashCode".equals(methodName) && "()I".equals(methodDesc)) {
                code.append("    jstring str_").append(methodHash).append(" = (jstring)stack[--sp].l;\n");
                code.append("    stack[sp++].i = inline_string_hashCode(env, str_").append(methodHash).append(");\n");
                return code.toString();
            }
            if ("charAt".equals(methodName) && "(I)C".equals(methodDesc)) {
                code.append("    jint idx_").append(methodHash).append(" = stack[--sp].i;\n");
                code.append("    jstring str_").append(methodHash).append(" = (jstring)stack[--sp].l;\n");
                code.append("    stack[sp++].i = inline_string_charAt(env, str_").append(methodHash).append(", idx_")
                        .append(methodHash).append(");\n");
                return code.toString();
            }
        }

        // ==================== java/lang/Object ====================
        if ("java/lang/Object".equals(ownerClass)) {
            if ("getClass".equals(methodName) && "()Ljava/lang/Class;".equals(methodDesc)) {
                code.append("    jobject obj_").append(methodHash).append(" = stack[--sp].l;\n");
                code.append("    stack[sp++].l = inline_object_getClass(env, obj_").append(methodHash).append(");\n");
                return code.toString();
            }
        }

        // ==================== java/lang/Math ====================
        if ("java/lang/Math".equals(ownerClass)) {
            // Math.abs
            if ("abs".equals(methodName)) {
                if ("(D)D".equals(methodDesc)) {
                    code.append("    stack[sp-1].d = inline_math_abs_d(stack[sp-1].d);\n");
                    return code.toString();
                }
                if ("(F)F".equals(methodDesc)) {
                    code.append("    stack[sp-1].f = inline_math_abs_f(stack[sp-1].f);\n");
                    return code.toString();
                }
                if ("(I)I".equals(methodDesc)) {
                    code.append("    stack[sp-1].i = inline_math_abs_i(stack[sp-1].i);\n");
                    return code.toString();
                }
                if ("(J)J".equals(methodDesc)) {
                    code.append("    stack[sp-1].j = inline_math_abs_l(stack[sp-1].j);\n");
                    return code.toString();
                }
            }
            // Math.max/min
            if ("max".equals(methodName)) {
                if ("(DD)D".equals(methodDesc)) {
                    code.append("    sp--; stack[sp-1].d = inline_math_max_d(stack[sp-1].d, stack[sp].d);\n");
                    return code.toString();
                }
                if ("(II)I".equals(methodDesc)) {
                    code.append("    sp--; stack[sp-1].i = inline_math_max_i(stack[sp-1].i, stack[sp].i);\n");
                    return code.toString();
                }
            }
            if ("min".equals(methodName)) {
                if ("(DD)D".equals(methodDesc)) {
                    code.append("    sp--; stack[sp-1].d = inline_math_min_d(stack[sp-1].d, stack[sp].d);\n");
                    return code.toString();
                }
                if ("(II)I".equals(methodDesc)) {
                    code.append("    sp--; stack[sp-1].i = inline_math_min_i(stack[sp-1].i, stack[sp].i);\n");
                    return code.toString();
                }
            }
            // Math trigonometric
            if ("sin".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_sin(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("cos".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_cos(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("tan".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_tan(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("sqrt".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_sqrt(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("pow".equals(methodName) && "(DD)D".equals(methodDesc)) {
                code.append("    sp--; stack[sp-1].d = inline_math_pow(stack[sp-1].d, stack[sp].d);\n");
                return code.toString();
            }
            if ("log".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_log(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("exp".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_exp(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("floor".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_floor(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("ceil".equals(methodName) && "(D)D".equals(methodDesc)) {
                code.append("    stack[sp-1].d = inline_math_ceil(stack[sp-1].d);\n");
                return code.toString();
            }
            if ("round".equals(methodName) && "(D)J".equals(methodDesc)) {
                code.append("    stack[sp-1].j = (jlong)inline_math_round(stack[sp-1].d);\n");
                return code.toString();
            }
        }

        // ==================== java/lang/System ====================
        if ("java/lang/System".equals(ownerClass)) {
            if ("arraycopy".equals(methodName) && "(Ljava/lang/Object;ILjava/lang/Object;II)V".equals(methodDesc)) {
                code.append("    jint len_").append(methodHash).append(" = stack[--sp].i;\n");
                code.append("    jint destPos_").append(methodHash).append(" = stack[--sp].i;\n");
                code.append("    jobject dest_").append(methodHash).append(" = stack[--sp].l;\n");
                code.append("    jint srcPos_").append(methodHash).append(" = stack[--sp].i;\n");
                code.append("    jobject src_").append(methodHash).append(" = stack[--sp].l;\n");
                code.append("    inline_system_arraycopy(env, src_").append(methodHash).append(", srcPos_")
                        .append(methodHash);
                code.append(", dest_").append(methodHash).append(", destPos_").append(methodHash).append(", len_")
                        .append(methodHash).append(");\n");
                return code.toString();
            }
        }

        return null; // 无内联实现
    }

    public void finalizeGeneration() {
        // Append prototypes
        globalCode.append("\n// Forward Declarations\n");
        globalCode.append(functionPrototypes);
        globalCode.append("\n");

        // Append method implementations
        globalCode.append(methodImplementations);

        // Generate JNI_OnLoad with global cache initialization
        globalCode.append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {\n");
        globalCode.append("    g_jvm = vm;\n");
        globalCode.append("    JNIEnv* env;\n");
        globalCode.append("    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {\n");
        globalCode.append("        init_global_cache(env);\n");
        globalCode.append("    }\n");
        globalCode.append("    return JNI_VERSION_1_6;\n");
        globalCode.append("}\n\n");

        // Generate Java_cn_sky_jnic_JNICLoader_registerNatives
        globalCode.append(
                "JNIEXPORT void JNICALL Java_cn_sky_jnic_JNICLoader_registerNatives(JNIEnv *env, jclass loader, jclass target) {\n");

        // Debug prints
        globalCode
                .append("    log_debug(\"JNICLoader_registerNatives called. env=%p, target=%p\\n\", env, target);\n");

        globalCode.append("    if (target == NULL) {\n");
        globalCode.append("        log_debug(\"target is NULL\\n\");\n");
        globalCode.append("        return;\n");
        globalCode.append("    }\n\n");

        globalCode.append("    jclass cls_class = (*env)->GetObjectClass(env, target);\n");
        globalCode.append(
                "    jmethodID mid_getName = (*env)->GetMethodID(env, cls_class, \"getName\", \"()Ljava/lang/String;\");\n");
        globalCode.append("    if (mid_getName == NULL) {\n");
        globalCode.append("        log_debug(\"mid_getName is NULL\\n\");\n");
        globalCode.append("        return;\n");
        globalCode.append("    }\n\n");

        globalCode.append("    jstring nameStr = (jstring)(*env)->CallObjectMethod(env, target, mid_getName);\n");
        globalCode.append("    if (nameStr == NULL) {\n");
        globalCode.append("        log_debug(\"nameStr is NULL\\n\");\n");
        globalCode.append("        return;\n");
        globalCode.append("    }\n\n");

        globalCode.append("    const char *className = (*env)->GetStringUTFChars(env, nameStr, 0);\n");
        globalCode.append("    if (className == NULL) {\n");
        globalCode.append("        log_debug(\"className is NULL\\n\");\n");
        globalCode.append("        return;\n");
        globalCode.append("    }\n");
        globalCode.append("    log_debug(\"Registering natives for class: %s\\n\", className);\n\n");

        // Group methods by class
        Map<String, List<NativeEntry>> classGroups = new HashMap<>();
        for (NativeEntry entry : nativeEntries) {
            classGroups.computeIfAbsent(entry.className, k -> new ArrayList<>()).add(entry);
        }

        boolean first = true;
        for (Map.Entry<String, List<NativeEntry>> group : classGroups.entrySet()) {
            String internalName = group.getKey(); // e.g. java/lang/String
            String dotName = internalName.replace('/', '.');
            List<NativeEntry> methods = group.getValue();
            String safeClassName = internalName.replace('/', '_').replace('$', '_');

            if (!first)
                globalCode.append("    else ");
            else
                first = false;

            globalCode.append("if (strcmp(className, \"").append(dotName).append("\") == 0) {\n");
            // globalCode.append(" printf(\"Match found for %s, registering %d methods\\n\",
            // className, ").append(methods.size()).append("); fflush(stdout);\n");

            globalCode.append("        JNINativeMethod methods_").append(safeClassName).append("[] = {\n");
            for (NativeEntry method : methods) {
                globalCode.append("            {\"").append(method.methodName).append("\", \"")
                        .append(method.signature).append("\", (void *)&").append(method.cFunctionName).append("},\n");
            }
            globalCode.append("        };\n");

            // RegisterNatives expects the target class, which is passed as 'target'
            // argument.
            globalCode.append("        if ((*env)->RegisterNatives(env, target, methods_").append(safeClassName)
                    .append(", ").append(methods.size()).append(") < 0) {\n");
            // globalCode.append(" printf(\"RegisterNatives failed for %s\\n\", className);
            // fflush(stdout);\n");
            globalCode.append("            (*env)->ExceptionDescribe(env);\n");
            globalCode.append("            (*env)->ExceptionClear(env);\n");
            globalCode.append("        } else {\n");
            // globalCode.append(" printf(\"RegisterNatives success for %s\\n\", className);
            // fflush(stdout);\n");
            globalCode.append("        }\n");
            globalCode.append("    }\n");
        }

        globalCode.append("\n    (*env)->ReleaseStringUTFChars(env, nameStr, className);\n");
        globalCode.append("}\n");

        // Write to file
        File outFile = new File(Jnic.getInstance().getTmpdir(), Jnic.getInstance().getTempC().toString() + ".c");
        try (FileWriter writer = new FileWriter(outFile)) {
            writer.write(globalCode.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
