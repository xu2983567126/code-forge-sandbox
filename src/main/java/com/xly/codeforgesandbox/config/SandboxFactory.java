package com.xly.codeforgesandbox.config;

import com.xly.codeforgesandbox.docker.ContainerPool;
import com.xly.codeforgesandbox.service.Sandbox;
import com.xly.codeforgesandbox.service.impl.JavaDockerSandbox;
import com.xly.codeforgesandbox.service.impl.JavaNativeSandbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱实现选择工厂。
 *
 * <p>按 {@code sandbox.type}（native | docker）决定对外暴露哪个实现，
 * 控制器只依赖 {@link Sandbox} 接口。两个实现类都必须注册为 Bean，
 * 这里在装配期一次性选定，而不是请求期切换。</p>
 */
@Configuration
public class SandboxFactory {

    @Bean
    public Sandbox sandbox(JavaNativeSandbox nativeSandbox,
                               JavaDockerSandbox dockerSandbox,
                               @Value("${sandbox.type}") String type) {
        return switch (type.toLowerCase()) {
            case "native" -> nativeSandbox;
            case "docker" -> dockerSandbox;
            default -> throw new IllegalStateException("sandbox.type 只支持 native|docker，当前值: " + type);
        };
    }

    /**
     * 容器池：容量与 SandboxController 的并发闸门(max-concurrent)对齐，预热 N 个常驻容器复用。
     *
     * <p>dockerBin 为空时由 ContainerPool 内部回退到 SANDBOX_DOCKER_BIN 环境变量再回退 "docker"。
     * DockerCli 的构造器是包级私有（docker 包内），本 config 包不能直接 new，
     * 因此这里只传字符串，由同包的 ContainerPool 自建 DockerCli。</p>
     */
    @Bean
    public ContainerPool containerPool(
            @Value("${sandbox.pool.size:8}") int poolSize,
            @Value("${sandbox.docker.image:eclipse-temurin:latest}") String image,
            @Value("${sandbox.docker.pull-if-missing:true}") boolean pullIfMissing,
            @Value("${sandbox.container.memory-mb:256}") long memoryMb,
            @Value("${sandbox.container.cpu-count:1}") int cpuCount,
            @Value("${sandbox.docker.bin:}") String dockerBin) {
        return new ContainerPool(dockerBin, image, pullIfMissing, memoryMb, cpuCount, poolSize);
    }
}
