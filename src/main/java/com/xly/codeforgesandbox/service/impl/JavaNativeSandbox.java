package com.xly.codeforgesandbox.service.impl;

import com.xly.codeforgesandbox.service.JavaSandboxTemplate;
import com.xly.codeforgesandbox.util.ProcessExecutor;
import com.xly.codeforgesandbox.model.ProcessResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

@Component
public class JavaNativeSandbox extends JavaSandboxTemplate {

    private static final String JAVA_HOME = System.getProperty("java.home");
    private static final String JAVA_BIN = JAVA_HOME + SEPARATOR + "bin";
    private static final String JAVAC_PATH = JAVA_BIN + SEPARATOR + "javac";
    private static final String JAVA_PATH = JAVA_BIN + SEPARATOR + "java";
    private static final long RUN_TIMEOUT_SEC = 5;

    @Override
    protected long getRunTimeoutSeconds() {
        return RUN_TIMEOUT_SEC;
    }

    @Override
    protected String javacPath() {
        return JAVAC_PATH;
    }

    @Override
    protected String sourceFilePath(String codeDir) {
        return new File(codeDir, MAIN_CLASS_FILE).getAbsolutePath();
    }

    /**
     * 文件模式下用户程序按此路径读输入：输入文件与代码同在工作目录里。
     */
    @Override
    protected String inputFilePathForRun(String codeDir, int index) {
        return new File(new File(codeDir, INPUT_DIR_NAME), inputFileName(index)).getAbsolutePath();
    }

    /**
     * 原生路径逐用例执行后补上内存。
     *
     * <p>驱动退出时把堆峰值写在 {@code <输入文件>.mem}，进程结束后宿主进程直接读该文件即可 ——
     * 容器路径做不到这一点（容器 tmpfs 宿主不可见），那边由批量脚本在容器内读。</p>
     */
    @Override
    protected List<ProcessResult> executeAllCases(String codeDir, List<String> inputs,
                                                  List<String> fileArgPaths, boolean fileInput) {
        List<ProcessResult> results = super.executeAllCases(codeDir, inputs, fileArgPaths, fileInput);
        if (!fileInput) {
            return results;
        }
        for (int i = 0; i < results.size(); i++) {
            Long memoryKb = readMemoryKb(new File(memoryFilePathForRun(codeDir, i)));
            if (memoryKb != null) {
                results.set(i, results.get(i).withMemoryKb(memoryKb));
            }
        }
        return results;
    }

    @Override
    protected ProcessResult executeCompileCmd(String[] compileCmd, String codeDir) {
        return ProcessExecutor.execute(compileCmd, null, new File(codeDir),
                getCompileTimeoutSeconds(), null);
    }


    @Override
    protected ProcessResult executeRunCmd(String codeDir, List<String> cmd, String stdin) {
        return ProcessExecutor.execute(
                cmd.toArray(new String[0]),
                stdin,
                new File(codeDir),
                RUN_TIMEOUT_SEC,
                null
        );
    }

    @Override
    protected String javaPath() {
        return JAVA_PATH;
    }

    @Override
    protected String runDir(String hostCodeDir) {
        return hostCodeDir;
    }
}