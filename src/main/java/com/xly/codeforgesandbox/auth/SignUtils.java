package com.xly.codeforgesandbox.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class SignUtils {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 计算签名
     * @param body      请求体 JSON 字符串
     * @param timestamp 时间戳
     * @param nonce     随机数
     * @param secretKey 密钥
     * @return Base64 签名
     */
    public static String sign(String body, String timestamp, String nonce, String secretKey) {
        String signContent = body + "\n" + timestamp + "\n" + nonce;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] signBytes = mac.doFinal(signContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new RuntimeException("签名计算失败", e);
        }
    }

    /**
     * 验证签名
     */
    public static boolean verify(String body, String timestamp, String nonce, String sign, String secretKey) {
        String expectedSign = sign(body, timestamp, nonce, secretKey);
        return expectedSign.equals(sign);
    }
}
