package com.xly.codeforgesandbox.filter;

import org.springframework.util.StreamUtils;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;

/**
 * Spring 的 @RequestBody 默认只能读取一次请求流。
 * <br>
 * 但过滤器需要先读取 body 来计算签名，然后 Controller 还要再读一次。
 * <br>
 * 因此使用了 CachedBodyHttpServletRequest 包装类，
 * <br>
 * 将 body 缓存到字节数组中，允许多次读取。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        InputStream requestInputStream = request.getInputStream();
        this.cachedBody = StreamUtils.copyToByteArray(requestInputStream);
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(this.cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
        return new BufferedReader(new InputStreamReader(byteArrayInputStream));
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }
}

class CachedBodyServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream byteArrayInputStream;

    public CachedBodyServletInputStream(byte[] cachedBody) {
        this.byteArrayInputStream = new ByteArrayInputStream(cachedBody);
    }

    @Override
    public boolean isFinished() {
        return byteArrayInputStream.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        try {
            if (isFinished()) {
                readListener.onAllDataRead();
            } else {
                readListener.onDataAvailable();
            }
        } catch (IOException e) {
            readListener.onError(e);
        }
    }

    @Override
    public int read() {
        return byteArrayInputStream.read();
    }
}