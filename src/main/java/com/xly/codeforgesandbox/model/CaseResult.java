package com.xly.codeforgesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单测试用例的<b>执行事实</b>。
 *
 * <p>沙箱只负责回传事实，不判 AC/WA —— verdict 判定归判题服务（judge）。
 * 因此这里的字段全是进程执行的中性观测值，不包含任何结论性文案。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseResult {

    /** 进程退出码（超时被杀时为 -1） */
    private Integer exitCode;

    /** 是否触发沙箱超时被杀 */
    private Boolean timedOut;

    /** 标准输出全文 */
    private String output;

    /** 标准错误全文 */
    private String errorOutput;

    /** 执行耗时（ms） */
    private Long timeMs;

    /**
     * 内存用量（KB）：该用例的<b>堆峰值</b>，由用户程序退出时自报（驱动写 {@code <输入文件>.mem}）。
     * 用例被 SIGKILL（超时/OOM）时来不及自报，为 null —— 判题侧据此跳过内存判定。
     */
    private Long memoryKb;

    /** 输出是否因超过单流字节上限被截断（截断后内容不完整，不可直接用于答案比对） */
    private Boolean truncated;
}
