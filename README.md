# Jnic

Jnic 是一个将 Java 字节码方法“本地化”的构建工具：它会在构建阶段扫描 `input.jar` 中符合规则的方法，将其改写为 `native`，同时生成对应的 C 代码，并通过 Zig 交叉编译出多平台动态库；最终把动态库以加密资源的形式打包回输出 JAR，运行期再由注入的 `JNICLoader` 自动解包并加载。

> 适用场景：代码保护/混淆实验、JNI/字节码工程学习、构建期生成本地桥接层等。  
> 注意：这是“语义保持”的困难问题，复杂字节码/特殊指令/反射等场景可能不完全等价。

---

## 特性

- 构建期处理：输入 JAR → 生成 C → Zig 编译 → 输出 JAR
- 运行期自动加载：注入 `JNICLoader.load("jnic", clazz)` 到被处理类的 `<clinit>`
- 多目标交叉编译：Windows / Linux / macOS / Android（由配置 `target` 决定）
- 资源打包：将编译产物打包为 `cn/sky/jnic/<uuid>.dat` 并做 XOR 加密
- 可选字符串加密、简单控制流处理（见 `config.yml` 的 `obfuscation`, 仅实现了最简单的字符串异或加密）

---

## 快速开始

### 1) 环境要求

- JDK 17（`build.gradle` 目标为 Java 17）
- Windows 下可直接使用仓库内置的 Zig（`zig-x86_64-windows/`）；其他系统请自行准备 `zig` 并确保可在 PATH 中调用

### 2) 准备输入

把待处理的 JAR 放到项目根目录（或自行改路径）：

- `input.jar`：需要被处理的输入 JAR
- `libs/`：可选依赖库（用于补全 classpath，便于分析/生成）

### 3) 配置 `config.yml`

项目根目录的 `config.yml` 示例（可按需修改）：

```yml
input: ./input.jar
output: ./output.jar
libs:
  - ./libs
target:
  - WINDOWS_X86_64
  - LINUX_X86_64
  # - ANDROID_ARM64
includes:
  - "*"
excludes: 
  - ""
obfuscation:
  stringEncryption: true
  flowObfuscation: false
  antiDebug: true
  level: 1
```

说明：

- `includes/excludes` 使用类的 internal name（如 `cn/sky/**`，分隔符为 `/`），支持 `*`、`**`、`?`
- 建议不要把 `includes/excludes` 留成空数组项（如 `-`），避免匹配逻辑出现空字符串

### 4) 构建并运行

构建：

```bash
./gradlew.bat build
```

运行（生成工具本体）：

```bash
java -jar jnic.jar
```

运行完成后将生成：

- `output.jar`：包含被改写为 `native` 的类，以及注入的加载器与加密后的本地库资源

---

## 工作原理（流程图）

```mermaid
flowchart LR
  A[input.jar] --> B[SkyJarLoader 读取 class/资源]
  B --> C[NativeProcessor 扫描/筛选方法]
  C --> D[CGenerator 生成 native-lib.c]
  D --> E[ZigCompiler 交叉编译多目标动态库]
  E --> F[打包为 cn/sky/jnic/<uuid>.dat (XOR)]
  F --> G[SkyJarLoader 写出 output.jar]
  G --> H[运行期 JNICLoader 解包 + System.load]
  H --> I[registerNatives(clazz)]
```

---

## 目标平台与命名规则

### 配置 target → Zig target

在 [ZigCompiler.java](src/main/java/cn/sky/jnic/process/ZigCompiler.java) 内部映射：

- `WINDOWS_X86_64` → `x86_64-windows`
- `LINUX_X86_64` → `x86_64-linux`
- `MACOS_X86_64` → `x86_64-macos`
- `MACOS_ARM64` → `aarch64-macos`
- `ANDROID_ARM64` → `aarch64-linux-android`
- `ANDROID_ARM32` → `arm-linux-androideabi`
- `ANDROID_X86` → `x86-linux-android`
- `ANDROID_X86_64` → `x86_64-linux-android`

### 动态库文件名（编译期 ↔ 运行期统一）

运行期加载器 `JNICLoader` 会根据系统信息拼出目标库名：

```
lib<libName>_<arch>-<platform>.<ext>
```

示例：

- Windows x86_64：`libjnic_x86_64-windows.dll`
- Linux x86_64：`libjnic_x86_64-linux.so`
- Android arm64：`libjnic_aarch64-android.so`

---

## 常见问题

### 1) Zig 找不到怎么办

- Windows：优先使用jnic.jar根目录下的 `zig-x86_64-windows/`（或自行把 `zig` 加入 PATH）
- 非 Windows：请安装 zig，并保证命令行能直接运行 `zig`

---

## 项目结构（简要）

- `src/main/java/cn/sky/jnic/`
  - `Jnic`：主流程与临时目录/资源键生成
  - `SkyJarLoader`：读取/写出 JAR（classes + resources）
  - `process/NativeProcessor`：筛选方法、注入加载器、打包本地库
  - `generator/CGenerator`：C 代码生成与 `RegisterNatives` 生成
  - `process/ZigCompiler`：Zig 编译与目标映射
  - `JNICLoader`：运行期解包并加载动态库
- `src/main/resources/jni.h`：打包的 JNI 头文件（用于 Zig 编译）

---

## 后续待更

### 1) native层性能优化

### 2) 增加控制流混淆以及增加字符串混淆强度

### 3) Rename混淆

## 免责声明

本项目涉及对字节码的改写与本地代码生成，可能引入兼容性与安全风险。请在受控环境中使用，并自行评估输出产物的稳定性与合规性。
