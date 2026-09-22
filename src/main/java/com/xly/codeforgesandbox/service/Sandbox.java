package com.xly.codeforgesandbox.service;


import com.xly.codeforgesandbox.model.ExecuteCodeRequest;
import com.xly.codeforgesandbox.model.ExecuteCodeResponse;
import org.springframework.stereotype.Service;

@Service
public interface Sandbox {
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);
}
