package com.xly.codeforgesandbox.docker;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Docker 容器池：预热 N 个加固容器常驻，请求取一个、用完归还（复用前重置 /work），
 * 避免每次 {@code docker run} / {@code docker rm} 的冷启动开销。
 *
 * <p>与 {@code SandboxController} 的并发闸门(max-concurrent)对齐：池容量 = 闸门上限，
 * 因此稳态下取容器几乎总能命中热容器；瞬时池空时 {@link #acquire()} 现建一个兜底。</p>
 *
 * <p>隔离边界：rootfs 只读 + cap-drop + 非 root + 网络 none 已在容器层兜住宿主；
 * 复用只新增一步 —— 每次取用时 {@link DockerContainerManager#resetWorkdir()} 清空 /work tmpfs，
 * 防止上一份代码的静态变量、残留文件或 {@code .mem} 串台。病态请求(超时等)会触发
 * {@code release(mgr, false)} 直接删容器并补一个干净的，不靠「重置」赌它干净。</p>
 */
@Slf4j
public class ContainerPool {

    private final DockerCli cli;
    private final String image;
    private final boolean pullIfMissing;
    private final long memoryLimitMb;
    private final int cpuCount;
    private final int size;

    private final BlockingQueue<DockerContainerManager> pool;
    private volatile boolean started = false;
    private final Object startLock = new Object();

    /**
     * @param dockerBin      docker 可执行文件位置；空时回退到 {@code SANDBOX_DOCKER_BIN} 环境变量，再回退 "docker"
     * @param image          容器内执行用的 JDK 镜像
     * @param pullIfMissing  镜像缺失时是否自动 pull
     * @param memoryLimitMb  每容器内存 cgroup 硬限(MB)
     * @param cpuCount       每容器 --cpus 硬配额(核)
     * @param size           池容量，应与 max-concurrent 对齐
     */
    public ContainerPool(String dockerBin, String image, boolean pullIfMissing,
                         long memoryLimitMb, int cpuCount, int size) {
        this.cli = new DockerCli(resolveBin(dockerBin));
        this.image = image;
        this.pullIfMissing = pullIfMissing;
        this.memoryLimitMb = memoryLimitMb;
        this.cpuCount = cpuCount;
        this.size = Math.max(1, size);
        this.pool = new ArrayBlockingQueue<>(this.size);
    }

    private static String resolveBin(String dockerBin) {
        String fromEnv = System.getenv("SANDBOX_DOCKER_BIN");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return (dockerBin == null || dockerBin.isBlank()) ? "docker" : dockerBin;
    }

    /**
     * 首次取容器时惰性预热：docker 就绪检查与镜像确保只做一次（避免每请求两次 CLI 调用），
     * 随后创建 size 个容器入池。保持与原始「首次请求才碰 docker」的语义一致 —— 启动不依赖 docker。
     */
    private void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (startLock) {
            if (started) {
                return;
            }
            DockerContainerManager.checkEnvironment(cli, image, pullIfMissing);
            for (int i = 0; i < size; i++) {
                DockerContainerManager mgr = newContainer();
                if (mgr != null) {
                    pool.offer(mgr);
                }
            }
            started = true;
            log.info("容器池预热完成: size={}", pool.size());
        }
    }

    private DockerContainerManager newContainer() {
        try {
            DockerContainerManager mgr = new DockerContainerManager(cli, image);
            mgr.createContainer(memoryLimitMb, cpuCount);
            return mgr;
        } catch (Exception e) {
            log.error("预热容器失败", e);
            return null;
        }
    }

    /**
     * 取一个热容器；池空时现建一个兜底（受并发闸门约束，稳态不会发生）。
     */
    public DockerContainerManager acquire() {
        ensureStarted();
        DockerContainerManager mgr = pool.poll();
        if (mgr != null) {
            return mgr;
        }
        log.debug("容器池暂空，临时新建");
        return newContainer();
    }

    /**
     * 归还容器。
     *
     * @param healthy true=正常用完，可直接复用；false=病态(超时等)，删掉并补一个干净的保持容量
     */
    public void release(DockerContainerManager mgr, boolean healthy) {
        if (mgr == null) {
            return;
        }
        if (!healthy) {
            mgr.removeContainer();
            if (pool.remainingCapacity() > 0) {
                DockerContainerManager fresh = newContainer();
                if (fresh != null) {
                    pool.offer(fresh);
                }
            }
            return;
        }
        if (pool.remainingCapacity() > 0) {
            pool.offer(mgr);
        } else {
            // 理论不会触发：并发受闸门约束，热容器数恒等于容量减在途数
            mgr.removeContainer();
        }
    }

    @PreDestroy
    public void destroy() {
        DockerContainerManager mgr;
        while ((mgr = pool.poll()) != null) {
            try {
                mgr.removeContainer();
            } catch (Exception e) {
                log.debug("关闭池容器失败", e);
            }
        }
        log.info("容器池已销毁");
    }
}
