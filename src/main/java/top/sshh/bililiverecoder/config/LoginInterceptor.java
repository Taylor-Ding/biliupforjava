package top.sshh.bililiverecoder.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Base64;


public class LoginInterceptor implements HandlerInterceptor {

    public LoginInterceptor(String userName,String password) {
        // 如果用户名和密码都不为空
        if(StringUtils.isNotBlank(userName) && StringUtils.isNotBlank(password)){
            // 获取Base64编码器
            Base64.Encoder encoder = Base64.getEncoder();
            // 将用户名和密码拼接成字符串，并转换为Base64编码的字节数组，最后与"Basic "拼接成认证字符串
            this.authString = "Basic " + encoder.encodeToString((userName+":"+password).getBytes());
        }else {
            // 如果用户名或密码为空，则认证字符串为空
            this.authString = "";
        }
    }

    // 认证字符串
    private final String authString;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 如果认证字符串为空，则不需要进行认证，直接返回true
        if(StringUtils.isBlank(authString)){
            return true;
        }

        // 从请求头中获取Authorization字段的值
        String authorization = request.getHeader("Authorization");
        // 如果请求中的认证字符串与预置的认证字符串相等，则通过认证，返回true
        if(this.authString.equals(authorization)){
            return true;
        }
        // 如果认证失败，则设置响应头WWW-Authenticate和状态码401
        response.setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"");
        response.setStatus(401);
        // 返回false表示请求被拦截
        return false;
    }

}
