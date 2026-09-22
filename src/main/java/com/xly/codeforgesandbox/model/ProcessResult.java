package com.xly.codeforgesandbox.model;

/**
 * 一次进程执行的观测结果 —— 宿主进程、docker CLI、容器内 exec 共用同一载体。
 *
 * <p>三个来源的观测项完全相同，差异只在「谁采内存」：本类不采集内存，
 * 需要时由调用方在拿到用户程序自报的堆峰值后，用 {@link #withMemoryKb(Long)} 补上。</p>
 *
 * @param exitCode    退出码（超时被杀时为 -1）
 * @param stdOutput   标准输出全文
 * @param errorOutput 标准错误全文
 * @param timedOut    是否超时被杀
 * @param executeTime 耗时（ms）
 * @param truncated   输出是否因超过单流字节上限被截断
 * @param memoryKb    该次执行的堆峰值（KB）。<b>拿不到时为 null，不是 0</b> —— 用例被 SIGKILL
 *                    （超时/OOM）时用户程序来不及自报，这种「未知」必须与「确实用了 0」区分开，
 *                    否则内存超限永远判不出来。
 */
public record ProcessResult(Integer exitCode, String stdOutput, String errorOutput,
                            boolean timedOut, Long executeTime, boolean truncated,
                            Long memoryKb) {

    /**
     * 不采集内存的场景（宿主编译、docker CLI 调用、容器内 exec）：{@code memoryKb} 留空。
     */
    public ProcessResult(Integer exitCode, String stdOutput, String errorOutput,
                         boolean timedOut, Long executeTime, boolean truncated) {
        this(exitCode, stdOutput, errorOutput, timedOut, executeTime, truncated, null);
    }

    /**
     * 补内存：其余字段原样透传，只换 {@code memoryKb}。
     *
     * @param memoryKb 用户程序自报的堆峰值（KB）
     * @return 新实例
     */
    public ProcessResult withMemoryKb(Long memoryKb) {
        return new ProcessResult(exitCode, stdOutput, errorOutput, timedOut, executeTime, truncated, memoryKb);
    }

    /**
     * 默认的成功判断：退出码为 0 且未超时。
     */
    public boolean isSuccess() {
        return exitCode != null && exitCode == 0 && !timedOut;
    }
}
