package com.xly.codeforgesandbox.security;

import java.nio.file.*;
import java.security.Permission;

/**
 * 默认安全管理器
 */
@SuppressWarnings("all")
public class MySecurityManager extends SecurityManager {

    private static final String FORBIDDEN_MARKER = "code-forge-sandbox";
    private static final PathMatcher FORBIDDEN_MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:**/code-forge-sandbox/**");

    @Override
    public void checkPermission(Permission perm) {
    }

    @Override
    public void checkRead(String file) {
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (FORBIDDEN_MATCHER.matches(path) || path.toString().contains(FORBIDDEN_MARKER)) {
            throw new SecurityException("Read access denied for: " + path);
        }
    }

    @Override
    public void checkWrite(String file) {
        throw new SecurityException("checkWrite 权限异常：" + file);
    }

    @Override
    public void checkExec(String cmd) {
        throw new SecurityException("checkExec 权限异常：" + cmd);
    }

    @Override
    public void checkConnect(String host, int port) {
        throw new SecurityException("checkConnect 权限异常：" + host + ":" + port);
    }

}

