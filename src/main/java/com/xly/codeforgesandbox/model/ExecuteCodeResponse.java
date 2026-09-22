package com.xly.codeforgesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    /**
     * 诊断文案：编译失败的 stderr、系统异常栈，或并发闸门拒绝的原因。
     *
     * <p>沙箱不做 verdict 判定，故正常路径不回 message；判题侧只在
     * compileError / systemError 为真时把它写进 JudgeInfo.detail 供用户看「为什么错」。</p>
     */
    private String message;

    /**
     * 逐用例执行事实（进程退出码 / 是否超时 / 输出 / 耗时等中性观测值）。
     * 判题侧的唯一判据 —— verdict 由 judge 归约，沙箱只回事实。
     */
    private List<CaseResult> caseResults;

    /** 编译是否失败（true 时 judge 直接判 COMPILE_ERROR，不再解析输出） */
    private Boolean compileError;

    /** 沙箱链路自身是否发生系统错误（含并发闸门拒绝；true 时 judge 判 SYSTEM_ERROR） */
    private Boolean systemError;
}
