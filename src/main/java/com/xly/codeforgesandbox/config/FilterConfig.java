package com.xly.codeforgesandbox.config;

import com.xly.codeforgesandbox.filter.SignatureAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<SignatureAuthFilter> signatureAuthFilterFilterRegistrationBean(
            AuthClientProperties authClientProperties) {
        FilterRegistrationBean<SignatureAuthFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new SignatureAuthFilter(authClientProperties));
        registrationBean.addUrlPatterns("/*");  // 只拦截特定路径，内部判断
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registrationBean;
    }
}