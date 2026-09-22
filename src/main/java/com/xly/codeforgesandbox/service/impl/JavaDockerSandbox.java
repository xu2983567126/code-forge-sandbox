package com.xly.codeforgesandbox.service.impl;

import com.xly.codeforgesandbox.docker.ContainerPool;
import com.xly.codeforgesandbox.docker.DockerContainerManager;
import com.xly.codeforgesandbox.docker.TarBuilder;
import com.xly.codeforgesandbox.service.JavaSandboxTemplate;
import com.xly.codeforgesandbox.model.ProcessResult;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JavaDockerSandbox extends JavaSandboxTemplate {

    private static final long RUN_TIMEOUT_SEC = 5;

    /**
     * 用例被 CPU 时间限制杀死时的 shell 退出码：128 + SIGXCPU(24)。
     *
     * <p>用软/硬限分离（软限到点内核发 SIGXCPU，硬限兜底发 SIGKILL）就是为了拿到这个可区分的码：
     * 若只设单值，超时与容器 OOM 杀进程同为 SIGKILL(137)，超时会被误判成运行错误。</p>
     */
    private static final int SIGXCPU_EXIT_CODE = 152;

    /** 硬限比软限宽出的秒数，留出信号投递与 JVM 收尾的时间。 */
    private static final int CPU_HARD_LIMIT_GRACE_SEC = 2;

    /**
     * 容器内布局：代码、用例输入与编译产物都落在 tmpfs 挂载的 /work（见 DockerContainerManager）。
     * 根文件系统整体只读，容器内没有任何可写路径能逃出 /work。
     */
    public static final String CONTAINER_CODE_DIR = "/work";
    private static final String CONTAINER_OUT_DIR = "/work/out";

    /**
     * 单个用例输出回传时的截断上限，与 {@code ProcessExecutor} 的默认单流上限同值。
     * 先截断再 base64，避免单个用例的巨量输出把整批回传撑爆。
     */
    private static final int MAX_CASE_OUTPUT_BYTES = 1024 * 1024;

    /**
     * 批量回传的额外余量：协议行、base64 膨胀（4/3）与峰值内存一行的开销。
     */
    private static final int BATCH_OUTPUT_HEADROOM_BYTES = 1024 * 1024;

    /**
     * 每容器内存 cgroup 硬限(MB)，同步派生成 JVM -Xmx（见 xmxMemoryLimit）。
     * 默认 256，可用 sandbox.container.memory-mb 调整；与容器池预热共用，改一处即可。
     */
    @Value("${sandbox.container.memory-mb:256}")
    private long containerMemoryMb;

    /**
     * 每容器 --cpus 硬配额(核)。默认 1，可用 sandbox.container.cpu-count 调整。
     */
    @Value("${sandbox.container.cpu-count:1}")
    private int containerCpuCount;

    /**
     * 是否走容器池复用常驻容器。默认 true；false 时退化为原始「每请求建/删容器」。
     */
    @Value("${sandbox.pool.enabled:true}")
    private boolean poolEnabled;

    /**
     * 单次执行的容器状态。
     *
     * <p>本类是单例 Bean，容器 ID 与管理器绝不能做实例字段（并发提交会互相踩容器）。
     * 模板的 {@code executeCode} 从准备到清理在同一个线程上同步跑完，
     * 所以用 ThreadLocal 携带请求态，finally 清理时必须显式 remove。</p>
     */
    private final ThreadLocal<DockerContainerManager> managerHolder = new ThreadLocal<>();
    private final ThreadLocal<String> containerIdHolder = new ThreadLocal<>();

    /**
     * 本请求是否病态（编译/运行超时等）。标记后 cleanupEnvironment 走
     * {@code pool.release(mgr, false)} 直接回收容器并补一个干净的，不赌「resetWorkdir 能清干净」。
     */
    private final ThreadLocal<Boolean> dirtyHolder = new ThreadLocal<>();

    /**
     * 容器池。复用常驻容器省去每次 docker run/rm 冷启动；pool.enabled=false 时本字段不会被读取。
     */
    @Resource
    private ContainerPool pool;

    @Value("${sandbox.docker.image:eclipse-temurin:latest}")
    private String dockerImage;

    @Value("${sandbox.docker.pull-if-missing:true}")
    private boolean pullIfMissing;

    @Override
    protected long getRunTimeoutSeconds() {
        return RUN_TIMEOUT_SEC;
    }

    @Override
    protected void prepareEnvironment(String codeDir) throws Exception {
        DockerContainerManager manager;
        if (poolEnabled) {
            manager = pool.acquire();
            // 复用前清空上一份代码的 /work tmpfs，防静态变量/残留 .mem 串台；
            // resetWorkdir 失败说明容器已病，直接归还(删+补)并上抛，不进入灌码。
            try {
                manager.resetWorkdir();
            } catch (Exception e) {
                pool.release(manager, false);
                throw e;
            }
        } else {
            manager = new DockerContainerManager(dockerImage, pullIfMissing);
        }
        try {
            manager.extractArchiveViaExec(manager.getContainerId(), buildWorkspaceArchive(codeDir), CONTAINER_CODE_DIR);
        } catch (Exception e) {
            if (poolEnabled) {
                pool.release(manager, false);
            } else {
                manager.removeContainer(manager.getContainerId());
                manager.close();
            }
            throw e;
        }
        managerHolder.set(manager);
        containerIdHolder.set(manager.getContainerId());
    }

    /**
     * 把工作目录里的「用户代码 + 全部用例输入」打成一个 tar 归档。
     *
     * <p>一次 exec 灌完：内容经 {@code docker exec -i} 的标准输入送进容器，
     * 与文件个数无关 —— 用例再多也只有一次容器往返。</p>
     */
    private byte[] buildWorkspaceArchive(String codeDir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Path mainJava = Path.of(codeDir, MAIN_CLASS_FILE);
        entries.put(MAIN_CLASS_FILE, Files.readString(mainJava, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8));
        for (File input : listInputFiles(codeDir)) {
            entries.put(INPUT_DIR_NAME + "/" + input.getName(), Files.readAllBytes(input.toPath()));
        }
        // 特判比对文件：argv 里给的是容器内路径，文件必须先灌进去，否则容器内找不到
        File[] argFiles = new File(codeDir, FILE_ARGS_DIR_NAME).listFiles();
        if (argFiles != null) {
            for (File argFile : argFiles) {
                entries.put(FILE_ARGS_DIR_NAME + "/" + argFile.getName(), Files.readAllBytes(argFile.toPath()));
            }
        }
        return TarBuilder.multiFile(entries);
    }

    /**
     * 列出工作目录下的用例输入文件，按序号升序 —— 归档顺序稳定，便于排查。
     */
    private List<File> listInputFiles(String codeDir) {
        File[] files = new File(codeDir, INPUT_DIR_NAME).listFiles();
        if (files == null) {
            return List.of();
        }
        List<File> sorted = new ArrayList<>(List.of(files));
        // 驱动运行时会往同目录写 <用例输入>.mem（自报堆峰值），它不是用例输入，别打进归档
        sorted.removeIf(file -> file.getName().endsWith(MEMORY_FILE_SUFFIX));
        sorted.sort(Comparator.comparingInt(f -> inputIndex(f.getName())));
        return sorted;
    }

    private static int inputIndex(String fileName) {
        try {
            return Integer.parseInt(fileName.substring(0, fileName.indexOf('.')));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    protected String[] buildCompileCmd(String codeDir) {
        // 编译产物定向到独立的 out 目录，保持代码目录只读不可写
        return new String[]{
                javacPath(), "-encoding", "utf-8",
                "-d", CONTAINER_OUT_DIR,
                sourceFilePath(codeDir)
        };
    }

    @Override
    protected ProcessResult executeCompileCmd(String[] compileCmd, String codeDir) {
        // 编译超时统一走基类钩子 getCompileTimeoutSeconds()，不再用本类独立常量
        ProcessResult result = managerHolder.get().executeCommandInContainer(
                containerIdHolder.get(), compileCmd, null, getCompileTimeoutSeconds()
        );
        if (result.timedOut()) {
            dirtyHolder.set(Boolean.TRUE);
        }
        return result;
    }

    @Override
    protected String javacPath() {
        return "javac";
    }

    @Override
    protected String sourceFilePath(String codeDir) {
        return CONTAINER_CODE_DIR + "/" + MAIN_CLASS_FILE;
    }

    @Override
    protected String inputFilePathForRun(String codeDir, int index) {
        return CONTAINER_CODE_DIR + "/" + INPUT_DIR_NAME + "/" + inputFileName(index);
    }

    /**
     * 工作目录内的宿主路径换成容器内路径；目录外的路径原样返回（容器内本就不可见）。
     */
    @Override
    protected String pathForRun(String codeDir, String hostPath) {
        return hostPath.startsWith(codeDir)
                ? CONTAINER_CODE_DIR + hostPath.substring(codeDir.length())
                : hostPath;
    }

    @Override
    protected ProcessResult executeRunCmd(String codeDir, List<String> cmd, String stdin) {
        ProcessResult result = managerHolder.get().executeCommandInContainer(
                containerIdHolder.get(), cmd.toArray(new String[0]), stdin, RUN_TIMEOUT_SEC
        );
        if (result.timedOut()) {
            dirtyHolder.set(Boolean.TRUE);
        }
        return result;
    }

    @Override
    protected String javaPath() {
        return "java";
    }

    @Override
    protected String runDir(String hostCodeDir) {
        return CONTAINER_OUT_DIR;
    }

    /**
     * JVM 堆上限派生自容器内存限制，保证 -Xmx 与 cgroup 限制对齐（改一处即可，不再双源）。
     */
    @Override
    protected String xmxMemoryLimit() {
        return String.valueOf(containerMemoryMb);
    }

    /**
     * 文件模式下一次 exec 跑完整批：把 n 次容器往返压成 1 次。
     *
     * <p>标准输入模式（抽查程序）不批量 —— 那类调用每次只判一个用例，批量没有收益，
     * 且抽查程序按既有约定读标准输入。</p>
     */
    @Override
    protected List<ProcessResult> executeAllCases(String codeDir, List<String> inputs,
                                                    List<String> fileArgPaths, boolean fileInput) {
        if (!fileInput) {
            return super.executeAllCases(codeDir, inputs, fileArgPaths, false);
        }
        ProcessResult batch = managerHolder.get().executeCommandInContainer(
                containerIdHolder.get(),
                new String[]{"sh", "-c", buildBatchScript(codeDir, inputs.size(), fileArgPaths)},
                null,
                batchTimeoutSeconds(inputs.size()),
                batchOutputBudgetBytes(inputs.size()));
        if (batch.timedOut()) {
            dirtyHolder.set(Boolean.TRUE);
        }
        return parseBatchOutput(batch, inputs.size());
    }

    /**
     * 整批的硬超时（墙钟）。
     *
     * <p>容器内逐用例限的是 <b>CPU 时间</b>，纯挂起（{@code Thread.sleep}、阻塞读）不会被它约束，
     * 因此整批还需要一个墙钟上限兜底；到点即杀容器，缺记录的用例按整批超时处理。</p>
     */
    private long batchTimeoutSeconds(int caseCount) {
        return caseCount * RUN_TIMEOUT_SEC + 10;
    }

    /**
     * 整批的回传预算：每个用例的读出与出错各一份 base64（膨胀 4/3），再留头部余量。
     */
    private int batchOutputBudgetBytes(int caseCount) {
        return caseCount * 2 * (MAX_CASE_OUTPUT_BYTES + MAX_CASE_OUTPUT_BYTES / 2) + BATCH_OUTPUT_HEADROOM_BYTES;
    }

    /**
     * 生成「跑完整批 + 回传结果」的容器内脚本。
     *
     * <p>回传协议（每行一条，按前缀解析）：
     * {@code CASE <序号> <退出码> <毫秒>}、{@code MEM <序号> <KB>}（缺失表示该用例没留下内存自报）、
     * {@code OUT <序号> <base64>}、{@code ERR <序号> <base64>}。
     * 输出先 {@code head -c} 截断再 base64，保证单条协议行不会失控。</p>
     *
     * <p>每个用例起独立 JVM：用例间的静态状态、泄漏线程、输出重定向都不会串到下一个用例；
     * 单用例的 CPU 上限由子 shell 内的 {@code ulimit} 施加。</p>
     */
    private String buildBatchScript(String codeDir, int caseCount, List<String> fileArgPaths) {
        StringBuilder extraArgs = new StringBuilder();
        for (String arg : fileArgPaths) {
            extraArgs.append(' ').append(shellQuote(pathForRun(codeDir, arg)));
        }
        String outDir = runDir(codeDir);
        String runCmd = javaPath() + " -Xmx" + xmxMemoryLimit() + "m"
                + " -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
                + " -cp " + outDir + " Main" + extraArgs;

        StringBuilder script = new StringBuilder(512);
        script.append("mkdir -p ").append(outDir).append("; ");
        for (int i = 0; i < caseCount; i++) {
            String outFile = outDir + "/" + i + ".out";
            String errFile = outDir + "/" + i + ".err";
            String memFile = memoryFilePathForRun(codeDir, i);
            // 超时靠「子 shell 内 ulimit + exec」，不用外部 timeout 命令：
            // ① 本镜像的 timeout 是 Rust coreutils 二进制，光启动就 ~103ms（裸 java 才 ~23ms/用例）；
            // ② ulimit 是 shell 内建，零额外进程，CPU 上限直接落在 java 进程上；
            // ③ exec 让 java 顶替子 shell，不多留一层壳，$? 就是 java 的退出码。
            script.append("s=$(date +%s%N); ")
                    .append("( ").append(cpuTimeLimitPrelude())
                    .append("exec ").append(runCmd).append(' ').append(inputFilePathForRun(codeDir, i))
                    .append(" ) > ").append(outFile).append(" 2> ").append(errFile).append("; ")
                    .append("rc=$?; e=$(date +%s%N); ")
                    .append("printf 'CASE ").append(i).append(" %s %s\\n' \"$rc\" \"$(( (e-s)/1000000 ))\"; ")
                    // 内存由用户程序自报（驱动退出时写 <输入文件>.mem）；用例被 SIGKILL 时文件不存在，
                    // 此时整行不发，判题侧据此把该用例内存记为未知而不是 0
                    .append("if [ -f ").append(memFile).append(" ]; then printf 'MEM ").append(i)
                    .append(" %s\\n' \"$(cat ").append(memFile).append(")\"; fi; ");
        }
        for (int i = 0; i < caseCount; i++) {
            script.append("printf 'OUT ").append(i).append(" '; head -c ").append(MAX_CASE_OUTPUT_BYTES)
                    .append(' ').append(outDir).append('/').append(i).append(".out | base64 -w0; echo; ")
                    .append("printf 'ERR ").append(i).append(" '; head -c ").append(MAX_CASE_OUTPUT_BYTES)
                    .append(' ').append(outDir).append('/').append(i).append(".err | base64 -w0; echo; ");
        }
        return script.toString();
    }

    /**
     * 单用例的 CPU 时间限制前置语句，形如 {@code ulimit -S -t 5; ulimit -H -t 7; }。
     *
     * <p>软硬限分离：软限到点由内核发 SIGXCPU（退出码 152），进程若忽略则由硬限 SIGKILL 兜底。
     * 限的是 CPU 时间而非墙钟 —— 用户代码基本都是纯计算所以两者等价，纯挂起交给整批硬超时。</p>
     */
    private static String cpuTimeLimitPrelude() {
        return "ulimit -S -t " + RUN_TIMEOUT_SEC + "; ulimit -H -t "
                + (RUN_TIMEOUT_SEC + CPU_HARD_LIMIT_GRACE_SEC) + "; ";
    }

    /**
     * 解析批量回传。缺记录的用例按整批退出码兜底，避免少一个用例就丢掉整个提交的结论。
     */
    private List<ProcessResult> parseBatchOutput(ProcessResult batch, int caseCount) {
        Integer[] exitCodes = new Integer[caseCount];
        Long[] elapsedMs = new Long[caseCount];
        Long[] memoryKbs = new Long[caseCount];
        String[] stdOuts = new String[caseCount];
        String[] errOuts = new String[caseCount];

        for (String line : batch.stdOutput().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("CASE ")) {
                String[] parts = line.split(" ", 4);
                int index = Integer.parseInt(parts[1]);
                exitCodes[index] = Integer.valueOf(parts[2]);
                elapsedMs[index] = Long.valueOf(parts[3]);
            } else if (line.startsWith("MEM ")) {
                String[] parts = line.split(" ", 3);
                int index = Integer.parseInt(parts[1]);
                memoryKbs[index] = parseLongOrNull(parts[2].trim());
            } else if (line.startsWith("OUT ")) {
                int index = Integer.parseInt(line.substring(4, line.indexOf(' ', 4)));
                stdOuts[index] = decodeBase64(line.substring(line.indexOf(' ', 4) + 1));
            } else if (line.startsWith("ERR ")) {
                int index = Integer.parseInt(line.substring(4, line.indexOf(' ', 4)));
                errOuts[index] = decodeBase64(line.substring(line.indexOf(' ', 4) + 1));
            }
        }

        List<ProcessResult> results = new ArrayList<>(caseCount);
        for (int i = 0; i < caseCount; i++) {
            Integer exitCode = exitCodes[i] != null ? exitCodes[i] : batch.exitCode();
            // CPU 时间到软限时内核发 SIGXCPU，shell 记为 128+24=152 —— 据此把该用例判成超时。
            // 只认 152 不认 137：137 是 SIGKILL，与容器 OOM 杀进程无法区分，认它会把内存问题误判成超时。
            boolean timedOut = (exitCodes[i] != null && exitCodes[i] == SIGXCPU_EXIT_CODE) || batch.timedOut();
            // 参数顺序对齐 ProcessResult 主构造器：…, executeTime, truncated, memoryKb
            results.add(new ProcessResult(
                    timedOut ? -1 : exitCode,
                    stdOuts[i] != null ? stdOuts[i] : "",
                    errOuts[i] != null ? errOuts[i] : "",
                    timedOut,
                    elapsedMs[i] != null ? elapsedMs[i] : 0L,
                    false,
                    memoryKbs[i]));
        }
        return results;
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decodeBase64(String text) {
        try {
            return new String(Base64.getDecoder().decode(text.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * 单引号包裹 shell 参数，内部单引号用 {@code '\''} 转义。
     */
    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    @Override
    protected void cleanupEnvironment() throws Exception {
        DockerContainerManager manager = managerHolder.get();
        try {
            if (manager != null) {
                if (poolEnabled) {
                    // 病态(超时)的容器不要赌「重置能清干净」，走 release(mgr, false) 直接删+补；
                    // 健康容器归还池，下次 acquire 时 resetWorkdir 复位 /work 再复用。
                    pool.release(manager, !Boolean.TRUE.equals(dirtyHolder.get()));
                } else {
                    if (containerIdHolder.get() != null) {
                        manager.removeContainer(containerIdHolder.get());
                    }
                    manager.close();
                }
            }
        } finally {
            managerHolder.remove();
            containerIdHolder.remove();
            dirtyHolder.remove();
        }
    }
}
