package com.xly.codeforgesandbox.service.impl;

import com.xly.codeforgesandbox.model.CaseResult;
import com.xly.codeforgesandbox.model.ExecuteCodeRequest;
import com.xly.codeforgesandbox.model.ExecuteCodeResponse;
import com.xly.codeforgesandbox.service.Sandbox;

import java.util.List;

/**
 * 示例沙箱（本地开发 / 演示用，默认不装配）。
 *
 * <p>按<b>事实模型</b>回传：每个输入原样回显成一条 {@link CaseResult}（exitCode=0、output=输入原文），
 * 判题侧据此走事实路径归约 —— 沙箱不产出任何 verdict 文案（AC/WA 一律由 judge 判定）。</p>
 */
public class ExampleSandbox implements Sandbox {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputs = executeCodeRequest.getInputList();
        List<CaseResult> caseResults = inputs.stream()
                .map(input -> CaseResult.builder()
                        .exitCode(0)
                        .timedOut(false)
                        .output(input) // 示例沙箱：原样回显输入，让用户看到「代码跑通了」
                        .errorOutput("")
                        .timeMs(100L)
                        .memoryKb(100L)
                        .truncated(false)
                        .build())
                .toList();

        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setCaseResults(caseResults);
        return response;
    }
}
