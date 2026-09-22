package com.xly.codeforgesandbox.docker;

import com.xly.codeforgesandbox.util.ProcessExecutor;
import com.xly.codeforgesandbox.model.ProcessResult;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Docker 容器管理（docker CLI 通道）。
 *
 * <p>一次执行 = 一个容器：创建（全套加固）→ 灌码 → 编译/运行 exec → 用完即杀。
 * 容器池模式下复用同一批容器，不再每次创建/删除（见 {@link ContainerPool}）。</p>
 *
 * <p><b>两个拓扑相关的决定</b>：
 * ① 不走 bind mount —— 生产 daemon 是 Docker Desktop 的 desktop-linux 虚拟机，
 * 看不到本发行版 ext4 里的宿主路径，挂载会落空；
 * ② 不走 copyArchiveToContainer —— 只读根文件系统下 cp 通道被 daemon 整体拒绝。
 * 灌码与用例输入经 {@code docker exec -i ... tar -xf} 送进 tmpfs，与上述两条限制都无关。</p>
 *
 * <p>容器加固清单（缺一不可）：
 * {@code --network none}、rootfs 只读、可写层仅限 tmpfs（/work、/tmp，64MiB）、
 * {@code --cap-drop ALL}、{@code no-new-privileges}、非 root 用户 1000:1000、
 * pids-limit、nofile ulimit、内存 cgroup 硬限（禁 swap）、{@code --cpus} 硬配额。</p>
 */
@Slf4j
public class DockerContainerManager implements Closeable {

    /**
     * 容器内保活命令：容器只为承载多次 exec，自身不做任何事
     */
    private static final String KEEPALIVE_COMMAND = "tail -f /dev/null";

    /**
     * 容器内非 root 用户：与沙箱进程用户的 uid 对齐，保证 tmpfs 上的文件可读写
     */
    private static final String CONTAINER_USER = "1000:1000";

    private static final int PIDS_LIMIT = 128;
    private static final long NOFILE_SOFT = 1024L;
    private static final long NOFILE_HARD = 4096L;
    private static final String WORK_TMPFS = "/work:rw,size=64m,mode=1777";
    private static final String TMP_TMPFS = "/tmp:rw,size=64m,mode=1777";

    /**
     * 创建 / 检查 / 删除 / 读文件这类短命令的兜底超时
     */
    private static final long SHORT_CMD_TIMEOUT_SEC = 60;

    /**
     * 拉取镜像的兜底超时（本地已有镜像时不会走到）
     */
    private static final long PULL_TIMEOUT_SEC = 300;

    private final DockerCli cli;
    private final String image;
    private final boolean pullIfMissing;
    private final boolean pooled;
    private String containerId;

    /**
     * 独立(非池化)模式：自建 CLI 并立即校验 docker 与镜像，保持请求级隔离的原语义。
     */
    public DockerContainerManager(String image, boolean pullIfMissing) {
        this(new DockerCli(resolveDockerBinary()), image, pullIfMissing, false);
        checkEnvironment(cli, image, pullIfMissing);
    }

    /**
     * 池化模式：复用调用方已校验好的 CLI，不做重复校验(校验由 {@link #checkEnvironment} 统一做一次)。
     * pooled=true 时超时不再自行删容器 —— 容器的生死交给容器池。
     */
    public DockerContainerManager(DockerCli cli, String image) {
        this(cli, image, false, true);
    }

    private DockerContainerManager(DockerCli cli, String image, boolean pullIfMissing, boolean pooled) {
        this.cli = cli;
        this.image = image;
        this.pullIfMissing = pullIfMissing;
        this.pooled = pooled;
    }

    /**
     * 一次性环境校验（校验 docker CLI + 确保镜像存在），供容器池预热前调用，避免每个容器重复校验。
     */
    public static void checkEnvironment(DockerCli cli, String image, boolean pullIfMissing) {
        ProcessResult result = cli.exec(List.of("version", "--format", "{{.Server.Version}}"), SHORT_CMD_TIMEOUT_SEC);
        if (!result.isSuccess()) {
            throw new IllegalStateException("docker CLI 不可用，请检查是否在 PATH 中或设置 SANDBOX_DOCKER_BIN；"
                    + "错误输出: " + result.errorOutput().trim());
        }
        log.info("docker CLI 就绪，Server 版本 {}", result.stdOutput().trim());
        if (cli.exec(List.of("image", "inspect", image), SHORT_CMD_TIMEOUT_SEC).isSuccess()) {
            return;
        }
        if (!pullIfMissing) {
            // 本地构建的镜像没有远端可拉，直接给可操作的报错，而不是在 pull 阶段超时
            throw new IllegalStateException("Docker 镜像不存在且已禁用自动拉取，请先 pull 或 build: " + image);
        }
        log.info("拉取镜像: {}", image);
        ProcessResult pull = cli.exec(List.of("pull", image), PULL_TIMEOUT_SEC);
        if (!pull.isSuccess()) {
            throw new IllegalStateException("镜像拉取失败: " + image + "，错误输出: " + pull.errorOutput().trim());
        }
        log.info("镜像拉取完成");
    }

    /**
     * docker 可执行文件位置。默认走 PATH 查找，非默认位置用 {@code SANDBOX_DOCKER_BIN} 下发。
     */
    private static String resolveDockerBinary() {
        String fromEnv = System.getenv("SANDBOX_DOCKER_BIN");
        return (fromEnv == null || fromEnv.isBlank()) ? "docker" : fromEnv;
    }

    /**
     * 创建并启动一个加固容器（不挂载宿主路径）。
     *
     * @param memoryLimitMB 内存 cgroup 硬限（MB），同时禁 swap；&lt;=0 不限制
     * @param cpuCount      --cpus 硬配额（核）；&lt;=0 不限制
     * @return 容器 ID
     */
    public String createContainer(long memoryLimitMB, int cpuCount) {
        String name = "cf-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> args = new ArrayList<>(List.of("run", "-d", "--rm", "--name", name));
        args.addAll(securityArgs());
        if (memoryLimitMB > 0) {
            args.add("--memory");
            args.add(memoryLimitMB + "m");
            // memory-swap = memory：禁掉 swap，内存上限才是硬上限
            args.add("--memory-swap");
            args.add(memoryLimitMB + "m");
        }
        if (cpuCount > 0) {
            // --cpus 是硬配额；cpu-shares 只是相对份额，在空闲宿主机上等于没限
            args.add("--cpus");
            args.add(String.valueOf(cpuCount));
        }
        args.add(image);
        args.addAll(DockerCli.shell(KEEPALIVE_COMMAND));

        ProcessResult result = cli.exec(args, SHORT_CMD_TIMEOUT_SEC);
        String containerId = result.stdOutput().trim();
        if (!result.isSuccess() || containerId.isEmpty()) {
            throw new IllegalStateException("创建沙箱容器失败: " + result.errorOutput().trim());
        }
        this.containerId = containerId;
        log.debug("沙箱容器已启动: {} ({})", name, shorten(containerId));
        return containerId;
    }

    public String getContainerId() {
        return containerId;
    }

    /**
     * 清空容器内 /work（tmpfs 可写层），供容器池复用前复位。
     *
     * <p>以容器用户(1000:1000)身份执行，对 tmpfs 有写权限；find -mindepth 1 -delete 连隐藏文件一起删，
     * 避免上一份代码的静态文件、残留 {@code .mem} 串到下一份。失败抛异常 —— 调用方据此把容器判为病态、直接回收。</p>
     */
    public void resetWorkdir() {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        ProcessResult result = cli.exec(execArgs(containerId, DockerCli.shell("find /work -mindepth 1 -delete")),
                SHORT_CMD_TIMEOUT_SEC);
        if (!result.isSuccess()) {
            throw new IllegalStateException("重置容器工作目录失败: " + result.errorOutput().trim());
        }
    }

    /**
     * 容器加固参数。任何语言、任何调用路径都共用这一份，不在别处重复拼。
     */
    private List<String> securityArgs() {
        return List.of(
                "--network", "none",
                "--cap-drop", "ALL",
                "--read-only",
                "--user", CONTAINER_USER,
                "--security-opt", "no-new-privileges",
                "--pids-limit", String.valueOf(PIDS_LIMIT),
                "--ulimit", "nofile=" + NOFILE_SOFT + ":" + NOFILE_HARD,
                "--tmpfs", WORK_TMPFS,
                "--tmpfs", TMP_TMPFS);
    }

    /**
     * 经 exec 的标准输入把单个文本文件写进容器内目录。
     *
     * @param containerId 目标容器
     * @param fileName    目标文件名
     * @param content     文件内容（UTF-8 文本）
     * @param remoteDir   容器内目标目录（须为 tmpfs 可写路径）
     */
    public void writeFileViaExec(String containerId, String fileName, String content, String remoteDir) {
        List<String> args = execArgs(containerId, DockerCli.shell("cat > " + remoteDir + "/" + fileName));
        ProcessResult result = cli.exec(args, content.getBytes(StandardCharsets.UTF_8), SHORT_CMD_TIMEOUT_SEC);
        if (!result.isSuccess()) {
            throw new IllegalStateException("写入代码到容器失败: " + remoteDir + "/" + fileName
                    + "，错误输出: " + result.errorOutput().trim());
        }
        log.debug("文件已写入容器 {}: {}/{}", shorten(containerId), remoteDir, fileName);
    }

    /**
     * 把 tar 字节流解包到容器内目录。字节流经 {@code docker exec -i} 的标准输入送入，
     * 因此一次调用可以把「用户代码 + 全部用例输入」一起灌进去，与文件个数无关。
     *
     * <p>条目名可含子目录（GNU tar 自行创建父目录）；{@code --no-overwrite-dir} 是关键 ——
     * 否则 tar 会尝试修改目标目录自身的权限与时间戳，而 tmpfs 挂载点在非 root 下改不动，
     * 会导致整体解包失败。</p>
     *
     * @param containerId 目标容器
     * @param tarBytes    {@link TarBuilder} 产出的归档字节
     * @param remoteDir   容器内目标目录（须为 tmpfs 可写路径）
     */
    public void extractArchiveViaExec(String containerId, byte[] tarBytes, String remoteDir) {
        List<String> args = execArgs(containerId,
                DockerCli.shell("tar -xf - -C " + remoteDir + " --no-overwrite-dir"));
        ProcessResult result = cli.exec(args, tarBytes, SHORT_CMD_TIMEOUT_SEC);
        if (!result.isSuccess()) {
            throw new IllegalStateException("解包归档到容器失败: " + remoteDir
                    + "，错误输出: " + result.errorOutput().trim());
        }
        log.debug("归档已解包到容器 {}: {} ({} bytes)", shorten(containerId), remoteDir, tarBytes.length);
    }

    /**
     * 在容器内 exec 执行命令并采集结果。
     *
     * <p>超时不是只取消客户端等待 —— exec 进程在容器里继续跑会把配额烧光，
     * 因此超时直接杀容器（AutoRemove 随之清理），本容器不再复用。</p>
     *
     * <p>这里不采集内存：容器的 cgroup 峰值（{@code /sys/fs/cgroup/memory.peak}）会把编译阶段
     * 与 file cache 一并算进来，且单调不回退，不是「用户程序用了多少」。逐用例内存改由用户程序
     * 自报堆峰值（驱动写 {@code <输入文件>.mem}），见 {@code JavaSandboxTemplate}。</p>
     *
     * @param containerId 目标容器 ID
     * @param command     命令及参数
     * @param stdin       标准输入内容，null 表示不接管
     * @param timeoutSec  超时秒数，&lt;=0 无限等待（生产禁用）
     */
    public ProcessResult executeCommandInContainer(String containerId, String[] command,
                                                           String stdin, long timeoutSec) {
        return executeCommandInContainer(containerId, command, stdin, timeoutSec,
                ProcessExecutor.defaultMaxOutputBytes());
    }

    /**
     * 同上，但可指定单条流的采集上限 —— 批量回传（一次 exec 取回全部用例输出）需要按用例数放大。
     */
    public ProcessResult executeCommandInContainer(String containerId, String[] command,
                                                           String stdin, long timeoutSec,
                                                           int maxOutputBytes) {
        List<String> args = execArgs(containerId, List.of(command));
        ProcessResult result = cli.exec(args,
                stdin == null ? null : stdin.getBytes(StandardCharsets.UTF_8), timeoutSec, maxOutputBytes);
        // 池化模式下容器由池统一管理生死，超时不在此自删（避免池持有已失效引用）；
        // 非池化(独立)模式保持原行为：超时直接删容器。
        if (result.timedOut() && !pooled) {
            removeContainer(containerId);
        }
        // 6 参便捷构造器：这里不采内存（memoryKb 留空），逐用例内存由用户程序自报后另行补上
        return new ProcessResult(result.exitCode(), result.stdOutput(), result.errorOutput(),
                result.timedOut(), result.executeTime(), result.truncated());
    }

    /**
     * 强制删除容器（AutoRemove 随之清理，docker ps -a 无残留）。
     * 容器可能已被杀/正在删，失败只记 debug 日志。
     */
    public void removeContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        ProcessResult result = cli.exec(List.of("rm", "-f", containerId), SHORT_CMD_TIMEOUT_SEC);
        if (!result.isSuccess()) {
            log.debug("删除容器失败（可能已停止）: {}", result.errorOutput().trim());
        }
    }

    /**
     * 删除本管理器持有的容器（容器池模式的统一入口；空 ID 时直接返回）。
     */
    public void removeContainer() {
        removeContainer(this.containerId);
    }

    /**
     * 拼 {@code docker exec} 参数。
     *
     * <p>{@code -i} 始终带上：容器内进程只有拿到 EOF 才会结束「读到 EOF 才停」的读法。</p>
     */
    private List<String> execArgs(String containerId, List<String> command) {
        List<String> args = new ArrayList<>(command.size() + 3);
        args.add("exec");
        args.add("-i");
        args.add(containerId);
        args.addAll(command);
        return args;
    }

    private static String shorten(String containerId) {
        return containerId.length() <= 12 ? containerId : containerId.substring(0, 12);
    }

    /**
     * CLI 通道不持有长连接，无需释放。
     */
    @Override
    public void close() {
    }
}
