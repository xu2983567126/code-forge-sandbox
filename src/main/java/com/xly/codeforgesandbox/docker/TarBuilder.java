package com.xly.codeforgesandbox.docker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 最小 tar（ustar）归档构造器，把「用户代码 + 全部用例输入」打成一个字节流灌进容器。
 *
 * <p>为什么不走 bind mount：生产 daemon 是 Docker Desktop 的 desktop-linux 虚拟机，
 * 它看不到本发行版 ext4 里的宿主路径，bind mount 会挂空。也不走
 * {@code copyArchiveToContainer}：只读根文件系统下 cp 通道被 daemon 整体拒绝。
 * 于是把 tar 字节流经 {@code docker exec -i ... tar -xf} 送进 tmpfs —— 手写 ustar
 * 只有 512 字节头 + 内容 + 补零，比引入 commons-compress 依赖更可控。</p>
 */
public final class TarBuilder {

    private TarBuilder() {
    }

    /**
     * 生成包含单个常规文件的 tar 字节流（含两块 512 字节结束符）。
     *
     * @param entryName 归档内路径（如 {@code Main.java}）
     * @param content   文件内容
     */
    public static byte[] singleFile(String entryName, String content) {
        return multiFile(Map.of(entryName, content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 生成包含多个常规文件的 tar 字节流（含两块 512 字节结束符）。
     *
     * <p>条目名可含子目录（如 {@code in/0.json}），解包方（GNU tar）会自行创建父目录。
     * 条目顺序按传入 Map 的迭代顺序写入 —— 传 {@code LinkedHashMap} 可得到稳定归档，
     * 便于单测逐字节比对。</p>
     *
     * @param entries 归档内路径 → 文件内容（文本或任意字节）
     */
    public static byte[] multiFile(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            appendEntry(out, entry.getKey(), entry.getValue());
        }
        // 两块全零结束符
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    /**
     * 写入单个常规文件条目：512 字节头 + 内容 + 补零到 512 的整数倍。
     */
    private static void appendEntry(ByteArrayOutputStream out, String entryName, byte[] data) {
        byte[] name = entryName.getBytes(StandardCharsets.UTF_8);
        if (name.length >= 100) {
            throw new IllegalArgumentException("tar entry name 过长: " + entryName);
        }
        byte[] header = new byte[512];

        // name[100] / mode[8] / uid[8] / gid[8] / size[12] / mtime[12] / chksum[8]
        System.arraycopy(name, 0, header, 0, name.length);
        System.arraycopy(octal(0644, 7), 0, header, 100, 7);
        System.arraycopy(octal(0, 7), 0, header, 108, 7);
        System.arraycopy(octal(0, 7), 0, header, 116, 7);
        System.arraycopy(octal(data.length, 11), 0, header, 124, 11);
        System.arraycopy(octal(System.currentTimeMillis() / 1000, 11), 0, header, 136, 11);
        // chksum[8] 先填空格参与计算
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        // typeflag '0'（常规文件）
        header[156] = '0';
        // magic "ustar\0" + version "00"
        System.arraycopy("ustar\u0000".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);

        int checksum = 0;
        for (byte b : header) {
            checksum += (b & 0xFF);
        }
        System.arraycopy(octal(checksum, 6), 0, header, 148, 6);
        header[154] = 0;
        header[155] = ' ';

        out.writeBytes(header);
        out.writeBytes(data);
        // 数据补齐到 512 块
        int padding = (512 - data.length % 512) % 512;
        out.writeBytes(new byte[padding]);
    }

    /**
     * 八进制 ASCII 串，不含结尾 NUL（调用方负责补）。
     */
    private static byte[] octal(long value, int width) {
        String oct = Long.toOctalString(value);
        if (oct.length() > width) {
            throw new IllegalArgumentException("八进制值超出字段宽度: " + oct);
        }
        String padded = "0".repeat(width - oct.length()) + oct;
        return padded.getBytes(StandardCharsets.US_ASCII);
    }
}
