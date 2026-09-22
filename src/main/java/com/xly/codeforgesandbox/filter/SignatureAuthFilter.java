package com.xly.codeforgesandbox.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xly.codeforgesandbox.auth.SignUtils;
import com.xly.codeforgesandbox.config.AuthClientProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SignatureAuthFilter extends OncePerRequestFilter {

    private static final long TIME_DIFF_ALLOWED = 60_000L; // 60 seconds
    /** nonce → 时间戳，附带惰性清理，防止 OOM */
    private final ConcurrentHashMap<String, Long> usedNonces = new ConcurrentHashMap<>();
    private final Map<String, String> secretKeyMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SignatureAuthFilter(AuthClientProperties properties) {
        this.secretKeyMap = new HashMap<>();
        for (AuthClientProperties.Client client : properties.getClients()) {
            secretKeyMap.put(client.getAccessKey(), client.getSecretKey());
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 仅对 /executeCode 进行签名校验，其他路径直接放行
        if (!request.getRequestURI().equals("/executeCode")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 包装请求以便重复读取 Body
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String body = new String(cachedRequest.getCachedBody(), StandardCharsets.UTF_8);

        // 1. 检查必要请求头是否存在
        String accessKey = cachedRequest.getHeader("X-Access-Key");
        String timestamp = cachedRequest.getHeader("X-Timestamp");
        String nonce = cachedRequest.getHeader("X-Nonce");
        String sign = cachedRequest.getHeader("X-Sign");

        if (accessKey == null || timestamp == null || nonce == null || sign == null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Missing authentication headers\"}");
            return;
        }

        // 2. 校验 timestamp 是否在允许的时间范围内
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Invalid timestamp\"}");
            return;
        }
        if (Math.abs(System.currentTimeMillis() - requestTime) > TIME_DIFF_ALLOWED) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Request expired\"}");
            return;
        }

        // 3. 校验 nonce 是否已使用（原子 putIfAbsent，防止重放）
        if (usedNonces.putIfAbsent(nonce, requestTime) != null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Nonce already used\"}");
            return;
        }

        // 4. 根据 accessKey 查找对应的 secretKey
        String secretKey = secretKeyMap.get(accessKey);
        if (secretKey == null) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Invalid access key\"}");
            return;
        }

        // 5. 验证签名
        if (!SignUtils.verify(body, timestamp, nonce, sign, secretKey)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("{\"error\":\"Invalid signature\"}");
            return;
        }

        // 6. 惰性清理过期 nonce（每 64 次请求触发一次，避免频繁遍历）
        if ((usedNonces.size() & 0x3F) == 0) {
            cleanupExpiredNonces();
        }

        // 放行，使用包装后的请求
        filterChain.doFilter(cachedRequest, response);
    }

    /**
     * 移除已超出时间窗口的 nonce，防止集合无限增长
     */
    private void cleanupExpiredNonces() {
        long deadline = System.currentTimeMillis() - TIME_DIFF_ALLOWED;
        usedNonces.forEach((k, v) -> {
            if (v < deadline) {
                usedNonces.remove(k, v);
            }
        });
    }
}