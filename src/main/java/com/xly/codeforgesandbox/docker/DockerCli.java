package com.xly.codeforgesandbox.docker;

import com.xly.codeforgesandbox.util.ProcessExecutor;
import com.xly.codeforgesandbox.model.ProcessResult;

import java.util.ArrayList;
import java.util.List;

/**
 * docker CLI 薄封装。
 *
 * <p>容器通道走 docker CLI，而不是 docker-java 客户端库。库侧
 * {@code ExecStartCmd.withStdIn(...) + exec(callback)} 把标准输入当作 hijack 的
 * HTTP body（无 Content-Length、无 chunked 终止块），容器内进程读不到 EOF ——
 * 凡是「读到 EOF 才结束」的读法（灌码的 {@code cat > file}、驱动的
 * {@code System.in.readAllBytes()}）都会永久阻塞，最后被超时误判成超时。
 * CLI 的 {@code -i} 在 stdin 写完后正常关闭管道，EOF 可达。</p>
 *
 * <p>所有调用复用 {@link ProcessExecutor}：并发读流防管道死锁、单流采集上限、
 * 超时连子孙进程一起杀。</p>
 */
class DockerCli {

    /**
     * docker 可执行文件。默认走 PATH 查找；部署在非默认位置时用
     * {@code sandbox.docker.bin} 覆盖。
     */
    private final String binary;

    DockerCli(String binary) {
        this.binary = (binary == null || binary.isBlank()) ? "docker" : binary;
    }

    /**
     * 执行一条 docker 子命令。
     *
     * @param args       docker 之后的参数（如 {@code ["rm", "-f", cid]}）
     * @param stdin      写入标准输入的字节；{@code null} 表示不接管 stdin，空数组表示写入并立即关闭
     * @param timeoutSec 超时秒数，{@code <= 0} 表示不限
     */
    ProcessResult exec(List<String> args, byte[] stdin, long timeoutSec) {
        return exec(args, stdin, timeoutSec, ProcessExecutor.defaultMaxOutputBytes());
    }

    /**
     * 执行一条 docker 子命令，并指定单条流的采集上限。
     *
     * <p>批量回传（一次 exec 取回全部用例的输出）会超过默认上限，由调用方按用例数放大。</p>
     */
    ProcessResult exec(List<String> args, byte[] stdin, long timeoutSec, int maxOutputBytes) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(binary);
        argv.addAll(args);
        return ProcessExecutor.executeBinary(argv.toArray(new String[0]), stdin, null, timeoutSec, null, maxOutputBytes);
    }

    /**
     * 执行一条不需要标准输入的 docker 子命令。
     */
    ProcessResult exec(List<String> args, long timeoutSec) {
        return exec(args, null, timeoutSec);
    }

    /**
     * 把一整条命令包成 {@code sh -c} 形式，供 {@code docker exec} 使用。
     */
    static List<String> shell(String script) {
        return List.of("sh", "-c", script);
    }
}
