package com.xly.codeforgesandbox.service;

import cn.hutool.core.io.FileUtil;
import com.xly.codeforgesandbox.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.util.*;
import com.xly.codeforgesandbox.model.ProcessResult;

@Slf4j
public abstract class JavaSandboxTemplate implements Sandbox {

    protected static final String MAIN_CLASS_FILE = "Main.java";
    protected static final String SEPARATOR = FileSystems.getDefault().getSeparator();

    /**
     * 用户代码的工作根目录。
     *
     * <p>必须落在**工程目录之外**：用户代码与沙箱进程跑在同一个 OS 用户下，工作目录若在
     * 工程目录内，用户代码一行 {@code ../application.yaml} 就能读到本服务的 HMAC 密钥。
     * 因此用 /tmp 下的独立目录，而不是 {@code user.dir}。</p>
     */
    @Value("${sandbox.workspace-root:/tmp/cf-sandbox}")
    protected String workspaceRoot;

    // 子类可覆盖的默认超时与资源限制
    protected long getCompileTimeoutSeconds() {
        return 10;
    }

    protected abstract long getRunTimeoutSeconds();

    /**
     * 模板方法，定义执行流程骨架
     */
    @Override
    public final ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {

        String userCodeDir = null;
        try {
            // 1. 将用户代码写入宿主机隔离目录
            userCodeDir = saveCodeToFile(request.getCode());

            // 2. 把随代码一起进工作目录的东西先落盘：用例输入（file 模式）与特判用的比对文件。
            //    必须早于准备环境 —— 容器实现会在准备阶段把整个工作目录打包灌进容器，
            //    晚于它写的文件进不去，用户程序运行时就会「文件不存在」。
            List<String> inputs = normalizeInputs(request.getInputList());
            boolean fileInput = isFileInputMode(request);
            if (fileInput) {
                materializeInputs(userCodeDir, inputs);
            }
            // argv 跨用例恒定：特判传的是 [标准答案文件, 用户输出文件] 的路径
            List<String> fileArgPaths = materializeFileArgs(request.getFileArgs(), userCodeDir);

            // 3. 准备环境（Docker：创建容器并灌入代码与上述文件；Native：无操作）
            prepareEnvironment(userCodeDir);

            // 4. 编译
            ProcessResult compileResult = compile(userCodeDir);
            if (!compileResult.isSuccess()) {
                // 编译失败：只回「失败标记 + 编译器原始 stderr」，verdict（COMPILE_ERROR）由 judge 归约
                ExecuteCodeResponse response = new ExecuteCodeResponse();
                response.setCompileError(true);
                response.setMessage(compileResult.errorOutput());
                return response;
            }

            // 5. 运行各测试用例：file 模式由用户程序按 argv 给出的路径读输入文件，其余沿用标准输入。
            List<ProcessResult> runResults = executeAllCases(userCodeDir, inputs, fileArgPaths, fileInput);

            // 5. 组装响应
            return buildResult(runResults);

        } catch (Exception e) {
            ExecuteCodeResponse response = new ExecuteCodeResponse();
            response.setSystemError(true);
            response.setMessage("系统异常: " + e.getMessage());
            return response;
        } finally {
            // 6. 清洗环境（Docker 删除容器，Native 无操作）
            try {
                cleanupEnvironment();
            } catch (Exception e) {
                log.error("清理环境失败：", e);
            }

            // 7. 始终删除宿主机临时文件
            if (userCodeDir != null) {
                boolean del = FileUtil.del(userCodeDir);
                log.debug("删除代码目录{}", del ? "成功" : "失败");
            }
        }
    }

    /** 输入承载方式：写入文件，用户程序按 argv 给出的路径读取 */
    public static final String INPUT_MODE_FILE = "file";

    /** 用例输入目录名（相对工作目录） */
    protected static final String INPUT_DIR_NAME = "in";

    /** 特判比对文件的目录名（相对工作目录） */
    protected static final String FILE_ARGS_DIR_NAME = "fileargs";

    /**
     * 用户程序自报堆峰值文件的扩展名。
     *
     * <p>驱动在退出时把本用例的堆峰值（KB）写到「输入文件路径 + 本后缀」；沙箱按
     * {@link #memoryFilePathForRun} 读取。两侧规则必须一致，改名要同时改驱动模板。</p>
     */
    protected static final String MEMORY_FILE_SUFFIX = ".mem";

    /**
     * 请求是否声明「输入走文件」。
     *
     * <p>只有显式声明才切换：抽查程序（SPJ checker）按既有约定从标准输入读测试输入，
     * 不能跟着一起改。</p>
     */
    protected static boolean isFileInputMode(ExecuteCodeRequest request) {
        return INPUT_MODE_FILE.equalsIgnoreCase(request.getInputMode());
    }

    /**
     * 用例输入规范化：空列表按「一个空输入用例」处理（有些题目本身没有输入）。
     */
    private static List<String> normalizeInputs(List<String> inputList) {
        return (inputList == null || inputList.isEmpty()) ? List.of("") : inputList;
    }

    /** 第 index 个用例的输入文件名 */
    protected static String inputFileName(int index) {
        return index + ".json";
    }

    /**
     * 把用例输入物化成工作目录下的独立文件 {@code in/<序号>.json}。
     *
     * <p>用户程序读文件而不是读标准输入：标准输入是流，边界要靠对端关闭才算到；
     * 文件读到头即结束，不依赖任何外部动作。</p>
     */
    protected void materializeInputs(String codeDir, List<String> inputs) {
        File inputDir = new File(codeDir, INPUT_DIR_NAME);
        FileUtil.mkdir(inputDir);
        for (int i = 0; i < inputs.size(); i++) {
            FileUtil.writeString(inputs.get(i) == null ? "" : inputs.get(i),
                    new File(inputDir, inputFileName(i)), StandardCharsets.UTF_8);
        }
    }

    /**
     * 用户程序运行时应该读取的输入文件路径。宿主侧与容器内侧的路径形态不同，由子类给出。
     */
    protected abstract String inputFilePathForRun(String codeDir, int index);

    /**
     * 用户程序自报堆峰值（KB）的文件路径。
     *
     * <p>由 {@link #inputFilePathForRun} 派生，所以天然带上宿主/容器两种路径形态。读取方式随实现不同：
     * 容器实现由批量脚本在容器内读（容器 tmpfs 宿主不可见），原生实现由宿主进程直接读。</p>
     */
    protected String memoryFilePathForRun(String codeDir, int index) {
        return inputFilePathForRun(codeDir, index) + MEMORY_FILE_SUFFIX;
    }

    /**
     * 读出自报的内存值（KB）。文件不存在或内容非法时返回 <b>null</b>（不是 0）——
     * 用例被 SIGKILL（超时/OOM）时驱动来不及落盘，这种「未知」必须与「确实用了 0」区分开，
     * 否则会把未知当成极小值而永远判不出内存超限。
     */
    protected static Long readMemoryKb(File memoryFile) {
        if (!memoryFile.isFile()) {
            return null;
        }
        try {
            String text = FileUtil.readString(memoryFile, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : Long.valueOf(text);
        } catch (Exception e) {
            log.debug("内存自报文件无法解析: {}", memoryFile, e);
            return null;
        }
    }

    /**
     * 把宿主侧文件路径转成「运行侧」可见的路径。
     *
     * <p>宿主进程直接用宿主路径（默认实现）；容器实现要把工作目录前缀换成容器内路径，
     * 否则 argv 里传出宿主路径，容器内根本找不到。</p>
     */
    protected String pathForRun(String codeDir, String hostPath) {
        return hostPath;
    }

    /**
     * 执行全部用例。
     *
     * <p>默认逐用例执行（标准输入模式固定走这条：抽查程序每次只判一个用例）。
     * 容器实现在文件模式下覆写为「一次 exec 跑完整批」，把 n 次容器往返压成 1 次。</p>
     */
    protected List<ProcessResult> executeAllCases(String codeDir, List<String> inputs,
                                                    List<String> fileArgPaths, boolean fileInput) {
        List<ProcessResult> results = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            results.add(runCase(codeDir, inputs.get(i), fileArgPaths, fileInput, i));
        }
        return results;
    }

    /**
     * 跑单个用例。
     *
     * <p>文件模式下 argv 末尾追加输入文件路径，标准输入送空串 —— 输入已由文件承载，
     * 送空串只是让任何残留的标准输入读法立刻拿到 EOF（读到 EOF 才结束的读法会一直阻塞到超时）。</p>
     */
    protected ProcessResult runCase(String codeDir, String stdin, List<String> fileArgPaths,
                                      boolean fileInput, int index) {
        List<String> args = new ArrayList<>(fileArgPaths.size() + 1);
        for (String fileArgPath : fileArgPaths) {
            args.add(pathForRun(codeDir, fileArgPath));
        }
        if (!fileInput) {
            return run(codeDir, stdin, args);
        }
        args.add(inputFilePathForRun(codeDir, index));
        return run(codeDir, "", args);
    }

    protected void prepareEnvironment(String userCodeDir) throws Exception {
    }

    protected void cleanupEnvironment() throws Exception {
    }

    protected ProcessResult compile(String codeDir) {
        String[] compileCmd = buildCompileCmd(codeDir);
        return executeCompileCmd(compileCmd, codeDir);
    }

    protected abstract ProcessResult executeCompileCmd(String[] compileCmd, String codeDir);

    protected String[] buildCompileCmd(String codeDir) {
        return new String[]{
                javacPath(), "-encoding", "utf-8",
                sourceFilePath(codeDir)
        };
    }

    protected abstract String javacPath();

    protected abstract String sourceFilePath(String codeDir);

    protected ProcessResult run(String codeDir, String stdin, List<String> args) {
        List<String> runCmd = buildRunCmd(codeDir, args);
        return executeRunCmd(codeDir, runCmd, stdin);
    }


    protected List<String> buildRunCmd(String codeDir, List<String> args) {
        List<String> cmd = new ArrayList<>(Arrays.asList(
                javaPath(),
                "-Xmx%sm".formatted(xmxMemoryLimit()),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-cp", runDir(codeDir),
                "Main"
        ));
        cmd.addAll(args);
        return cmd;
    }

    protected String xmxMemoryLimit() {
        return "256";
    }

    protected abstract ProcessResult executeRunCmd(String codeDir, List<String> cmd, String stdin);

    protected abstract String javaPath();

    protected abstract String runDir(String hostCodeDir);

    /**
     * 存储用户代码
     *
     * @param code 用户代码
     * @return 用户代码所在的目录
     */
    private String saveCodeToFile(String code) {
        if (!FileUtil.exist(workspaceRoot)) {
            FileUtil.mkdir(workspaceRoot);
        }
        String userCodeDir = workspaceRoot + SEPARATOR + UUID.randomUUID();
        String userCodePath = userCodeDir + SEPARATOR + MAIN_CLASS_FILE;
        FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeDir;
    }

    /**
     * 将 {@code fileArgs} 每个元素物化为工作目录下的独立文件，返回其绝对路径列表（即 argv）。
     *
     * <p>特判走这条路径：argv 传的是<b>文件路径</b>而非内容本身，因此不受
     * {@code MAX_ARG_STRLEN}=128KB 单参长度上限约束，大输出题也能正常起进程。
     * 文件落在 {@code userCodeDir} 内，随 {@code finally} 块的 {@code FileUtil.del} 一并清理。</p>
     *
     * <p>元素为空字符串也照写（路径仍有效），不跳过。普通判题不设 {@code fileArgs}，返回空 argv。</p>
     */
    private List<String> materializeFileArgs(List<String> fileArgs, String userCodeDir) {
        if (fileArgs == null || fileArgs.isEmpty()) {
            return new ArrayList<>();
        }
        String argDir = userCodeDir + SEPARATOR + FILE_ARGS_DIR_NAME;
        FileUtil.mkdir(argDir);
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < fileArgs.size(); i++) {
            String path = argDir + SEPARATOR + i; // 无扩展名，checker 按路径读
            FileUtil.writeString(fileArgs.get(i) == null ? "" : fileArgs.get(i), path, StandardCharsets.UTF_8);
            paths.add(path);
        }
        return paths;
    }

    /**
     * 组装正常路径响应：只回逐用例执行事实。
     *
     * <p>message 留空 —— 沙箱不做 verdict 判定，超时/运行失败的可见结论由 judge 侧归约后写回，
     * 避免「沙箱一份结论文案、judge 一份结论」的双份真相。</p>
     */
    private ExecuteCodeResponse buildResult(List<ProcessResult> runResults) {
        List<CaseResult> caseResults = new ArrayList<>(runResults.size());
        for (ProcessResult result : runResults) {
            caseResults.add(CaseResult.builder()
                    .exitCode(result.timedOut() ? -1 : result.exitCode())
                    .timedOut(result.timedOut())
                    .output(result.stdOutput())
                    .errorOutput(result.errorOutput())
                    .timeMs(result.executeTime())
                    .memoryKb(result.memoryKb())
                    .truncated(result.truncated())
                    .build());
        }
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setCaseResults(caseResults);
        return response;
    }
}
