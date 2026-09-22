package com.xly.codeforgesandbox.util;

import cn.hutool.core.date.StopWatch;
import com.xly.codeforgesandbox.model.ProcessResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 进程执行工具类
 */
public class ProcessExecutor {

    /**
     * 单条流（stdout / stderr）最多采集的字节数。
     *
     * <p>用户代码可以无限打印，全量读进内存会把沙箱进程自己 OOM 掉。
     * 超出上限的部分**继续读取但丢弃** —— 不能停止读取，否则子进程的写会阻塞在管道上，
     * 把「输出太多」变成「程序卡死」。</p>
     */
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    /**
     * 输出被截断时追加的标记，让上游 judge 与用户能看出结果不完整。
     * 上限可调（批量回传会放大），所以标记按实际上限生成。
     */
    private static String truncatedMarker(int maxBytes) {
        return "\n[输出已截断：超过 " + maxBytes + " 字节上限]";
    }

    /**
     * 默认单流采集上限。上层需要放大时以它为基数，避免常量散落多处。
     */
    public static int defaultMaxOutputBytes() {
        return MAX_OUTPUT_BYTES;
    }

    /**
     * 执行命令（无输入，无超时）
     *
     * @param command 命令及参数，如 {"javac", "Main.java"}
     * @return 执行结果
     */
    public static ProcessResult execute(String[] command) {
        return execute(command, null, null, 0, null);
    }

    /**
     * 执行命令，支持标准输入
     *
     * @param command    命令及参数
     * @param input      要写入进程标准输入的内容，可为null
     * @param workDir    工作目录，可为null（使用当前目录）
     * @param timeoutSec 超时秒数，<=0表示不设超时
     * @param env        环境变量，可为null（继承父进程环境）
     * @return 执行结果
     */
    public static ProcessResult execute(String[] command, String input, File workDir, long timeoutSec, String[] env) {
        return executeBinary(command, input == null ? null : input.getBytes(StandardCharsets.UTF_8),
                workDir, timeoutSec, env);
    }

    /**
     * 执行命令，标准输入以<b>字节流</b>写入（tar 归档等二进制载荷走这条）。
     *
     * <p>与文本入口分开命名，避免字面 {@code null} 实参在两个重载之间产生二义。</p>
     *
     * <p>{@code input} 非 null 时<b>总是</b>写入并关闭 stdin，空数组也不例外：
     * 不关流会让子进程里任何「读到 EOF 才结束」的读法永久阻塞，最后被超时误判成超时。</p>
     *
     * @param input 写入进程标准输入的字节；{@code null} 表示不接管 stdin（此时子进程继承父进程的 stdin）
     */
    public static ProcessResult executeBinary(String[] command, byte[] input, File workDir, long timeoutSec, String[] env) {
        return executeBinary(command, input, workDir, timeoutSec, env, MAX_OUTPUT_BYTES);
    }

    /**
     * 同上，但可指定单条流的采集上限。
     *
     * <p>批量回传（一次 exec 取回全部用例的输出）会超过默认上限，需要按用例数放大。
     * 放大只影响内存占用：超限部分依旧「继续读但丢弃」，不会让子进程写阻塞在管道上。</p>
     *
     * @param maxOutputBytes 单条流最多采集的字节数，须 &gt; 0
     */
    public static ProcessResult executeBinary(String[] command, byte[] input, File workDir, long timeoutSec,
                                              String[] env, int maxOutputBytes) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // 1. 准备 ProcessBuilder
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) {
            pb.directory(workDir);
        }
        if (env != null) {
            pb.environment().clear();
            for (String envVar : env) {
                String[] split = envVar.split("=", 2);
                if (split.length == 2) {
                    pb.environment().put(split[0], split[1]);
                }
            }
        }
        pb.redirectErrorStream(false); // 分别捕获标准输出和错误输出

        Process process = null;
        // 用于保存写入标准输入时可能发生的异常
        final AtomicReference<Throwable> inputExceptionRef = new AtomicReference<>(null);
        try {
            // 2. 启动子进程
            process = pb.start();
            // lambda 只能捕获 effectively final 的局部变量；process 存在二次赋值（null → start()），
            // 故在此建立 final 别名供下面的 lambda 捕获。
            final Process finalProcess = process;

            // 3. 创建两个线程并发读取子进程的输出流（必须早于输入写入启动，避免管道阻塞）
            final StreamCapture[] outCapture = new StreamCapture[1];
            final StreamCapture[] errCapture = new StreamCapture[1];
            Thread outThread = new Thread(() -> outCapture[0] = readStream(finalProcess.getInputStream(), maxOutputBytes));
            Thread errThread = new Thread(() -> errCapture[0] = readStream(finalProcess.getErrorStream(), maxOutputBytes));
            // 设为守护线程，避免因主线程意外退出导致 JVM 挂死
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            // 4. 如果需要写入标准输入，在独立线程中完成，防止大数据量写入阻塞主线程
            if (input != null) {
                Thread inputThread = new Thread(() -> {
                    try (OutputStream os = finalProcess.getOutputStream()) {
                        os.write(input);
                        os.flush();
                    } catch (IOException e) {
                        // 捕获异常但不中断主流程，后续通过 inputException 传递
                        inputExceptionRef.compareAndSet(null, e);
                    }
                });
                inputThread.setDaemon(true);
                inputThread.start();
                // 等待输入写入结束（带超时保护，防止子进程不读取输入导致永久阻塞）
                inputThread.join(timeoutSec > 0 ? Math.min(timeoutSec, 5) * 1000 : 5000);
                if (inputExceptionRef.get() != null) {
                    // 输入写入失败，强制结束子进程并返回异常
                    killProcessTree(process);
                    outThread.join(1000);
                    errThread.join(1000);
                    stopWatch.stop();
                    return new ProcessResult(-1, captureText(outCapture), captureText(errCapture), false,
                            stopWatch.getTotalTimeMillis(), captureTruncated(outCapture, errCapture));
                }
            }

            // 5. 等待子进程结束（带超时控制）
            boolean finished;
            if (timeoutSec > 0) {
                finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
                if (!finished) {
                    // 超时：连子孙一起杀。destroyForcibly 只作用于直接子进程，
                    // 用户代码起的孙进程会被 reparent 到 init 继续烧 CPU。
                    killProcessTree(process);
                    // 等待读取线程处理残留数据
                    outThread.join(1000);
                    errThread.join(1000);
                    // 停止计时
                    stopWatch.stop();
                    return new ProcessResult(-1, captureText(outCapture), captureText(errCapture), true,
                            stopWatch.getTotalTimeMillis(), captureTruncated(outCapture, errCapture));
                }
            } else {
                process.waitFor();
            }

            // 6. 等待读取线程结束
            outThread.join();
            errThread.join();

            // 7. 获取退出码并返回结果
            int exitCode = process.exitValue();
            stopWatch.stop();
            long executeTime = stopWatch.getTotalTimeMillis();

            return new ProcessResult(exitCode, captureText(outCapture), captureText(errCapture), false,
                    executeTime, captureTruncated(outCapture, errCapture));

        } catch (IOException | InterruptedException e) {
            if (process != null) {
                killProcessTree(process);
            }
            stopWatch.stop();
            return new ProcessResult(-1, "", "执行异常: " + e.getMessage(), false,
                    stopWatch.getTotalTimeMillis(), false);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String captureText(StreamCapture[] capture) {
        return capture[0] != null ? capture[0].text() : "";
    }

    private static boolean captureTruncated(StreamCapture[] out, StreamCapture[] err) {
        return (out[0] != null && out[0].truncated()) || (err[0] != null && err[0].truncated());
    }

    /**
     * 强制终止进程及其全部子孙进程。
     *
     * <p>必须先取子孙快照再杀：父进程一死，子进程会被 reparent 到 init，
     * 那时 {@link Process#descendants()} 就再也查不到它们了。</p>
     */
    private static void killProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * 一条输出流的采集结果：文本 + 是否被截断。
     */
    private record StreamCapture(String text, boolean truncated) {
    }

    /**
     * 读取子进程的一条输出流，最多采集 {@link #MAX_OUTPUT_BYTES} 字节，超出部分继续读但丢弃。
     *
     * <p>先攒字节、最后一次性按 UTF-8 解码，而不是边读边用 Reader 解码 ——
     * 截断点可能正好落在某个多字节字符中间，那样会解出半个字符。</p>
     */
    private static StreamCapture readStream(InputStream inputStream, int maxOutputBytes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean truncated = false;
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                if (!truncated) {
                    int space = maxOutputBytes - buffer.size();
                    if (read <= space) {
                        buffer.write(chunk, 0, read);
                    } else {
                        buffer.write(chunk, 0, space);
                        truncated = true;
                    }
                }
            }
        } catch (IOException e) {
            // 读取异常写入错误输出，但加前缀以区分
            return new StreamCapture(buffer.toString(StandardCharsets.UTF_8) + "[流读取异常] " + e.getMessage(), truncated);
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        return truncated ? new StreamCapture(text + truncatedMarker(maxOutputBytes), true) : new StreamCapture(text, false);
    }

    // 下列便捷方法保持不变
    public static ProcessResult executeWithInput(String input, String... command) {
        return execute(command, input, null, 0, null);
    }

    public static ProcessResult executeWithTimeout(long timeoutSec, String... command) {
        return execute(command, null, null, timeoutSec, null);
    }

    public static ProcessResult executeInDir(File workDir, String... command) {
        return execute(command, null, workDir, 0, null);
    }
}