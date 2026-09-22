package com.xly.codeforgesandbox.controller;

import com.xly.codeforgesandbox.config.SandboxFactory;
import com.xly.codeforgesandbox.service.Sandbox;
import com.xly.codeforgesandbox.model.ExecuteCodeRequest;
import com.xly.codeforgesandbox.model.ExecuteCodeResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.Semaphore;

@RestController("/")
public class SandboxController {

    /**
     * 具体实现由 {@link SandboxFactory}
     * 按 sandbox.type（native|docker）装配，控制器只依赖接口。
     */
    private final Sandbox sandbox;

    private final int maxConcurrent;

    /**
     * 并发闸门。
     *
     * <p>用户代码占的是宿主机 CPU 与内存，并发不设上限时几次畸形提交就能把沙箱进程
     * 连同所在的 WSL 一起拖死。到顶**立即拒绝而不是排队** —— 排队会把上游 judge 的请求
     * 一起压住，超时点反而更难诊断。</p>
     */
    private final Semaphore gate;

    public SandboxController(Sandbox sandbox,
                             @Value("${sandbox.max-concurrent:4}") int maxConcurrent) {
        this.sandbox = sandbox;
        this.maxConcurrent = maxConcurrent;
        this.gate = new Semaphore(maxConcurrent);
    }

    @GetMapping("/hello")
    public String sayHello() {
        return "Hello World";
    }

    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest request) throws Exception {
        if (request == null) {
            throw new Exception("请求参数为空");
        }
        if (!gate.tryAcquire()) {
            return busyResponse();
        }
        try {
            return sandbox.executeCode(request);
        } finally {
            gate.release();
        }
    }

    /**
     * 并发已满时的响应。
     *
     * <p>标记为 {@code systemError}（沙箱链路侧故障，与用户代码无关），由 judge 侧归约成
     * SYSTEM_ERROR，并把 message 透出到 {@code JudgeInfo.detail}，用户能看到「沙箱繁忙」而不是空结论。</p>
     */
    private ExecuteCodeResponse busyResponse() {
        return ExecuteCodeResponse.builder()
                .systemError(true)
                .message("沙箱繁忙：并发已达上限 " + maxConcurrent + "，请稍后重试")
                .build();
    }
}
