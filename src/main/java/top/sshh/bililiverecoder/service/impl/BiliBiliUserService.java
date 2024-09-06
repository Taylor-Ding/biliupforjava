package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliSessionDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.util.BiliApi;

import java.time.LocalDateTime;

@Slf4j
@Component
public class BiliBiliUserService {


    @Autowired
    private BiliUserRepository userRepository;

    public boolean refreshToken(BiliBiliUser user) {
        // 调用BiliApi的refreshToken方法获取响应结果
        String response = BiliApi.refreshToken(user);

        // 从响应结果中读取code字段
        Integer code = JsonPath.read(response, "code");
        if (code == 0){
            // 解析token_info字段为BiliSessionDto对象
            BiliSessionDto dto = JSON.parseObject(JSON.toJSONString(JsonPath.read(response, "data.token_info")),BiliSessionDto.class);
            // 解析cookie_info字段中的cookies数组
            JSONArray cookies = JSON.parseArray(JsonPath.read(response, "data.cookie_info.cookies").toString());
            StringBuilder cookieString = new StringBuilder();
            for (Object object : cookies) {
                JSONObject cookie = (JSONObject)object;
                // 拼接cookie字符串
                cookieString.append(cookie.get("name").toString());
                cookieString.append(":");
                cookieString.append(cookie.get("value").toString());
                cookieString.append("; ");
            }

            // 设置用户的cookies字段
            user.setCookies(cookieString.toString());
            log.info("{} 刷新token成功!!!", user.getUname());
            // 设置用户的uid、accessToken和refreshToken字段
            user.setUid(dto.getMid());
            user.setAccessToken(dto.getAccessToken());
            user.setRefreshToken(dto.getRefreshToken());
            try{
                // 调用BiliApi的appMyInfo方法获取用户信息
                String userInfo = BiliApi.appMyInfo(user);
                // 设置用户的uname字段
                user.setUname(JsonPath.read(userInfo, "data.uname"));
            }catch (Exception e){
                log.error("刷新token 获取用户名称失败==>{}",user.getUname());
            }
            // 设置用户的login字段为true，表示登录成功
            user.setLogin(true);
            // 设置用户的updateTime字段为当前时间
            user.setUpdateTime(LocalDateTime.now());
            // 将更新后的用户信息保存到数据库
            userRepository.save(user);
            // 返回true表示刷新token成功
            return true;
        }else {
            try {
                // 调用BiliApi的appMyInfo方法获取用户信息
                String userInfo = BiliApi.appMyInfo(user);
                // 设置用户的uname字段
                user.setUname(JsonPath.read(userInfo, "data.uname"));
                // 设置用户的login字段为true，表示账号仍然可用
                user.setLogin(true);
                // 设置用户的updateTime字段为当前时间
                user.setUpdateTime(LocalDateTime.now());
                // 将更新后的用户信息保存到数据库
                userRepository.save(user);
                log.error("{} 刷新token失败!!!，账号仍然可用==>{}", user.getUname(), response);
            } catch (Exception e) {
                log.error("刷新token失败 获取用户名称失败==>{}", user.getUname());
            }
            // 设置用户的login字段为false，表示登录失败
            user.setLogin(false);
            // 设置用户的enable字段为false，表示账号被禁用
            user.setEnable(false);
            // 设置用户的updateTime字段为当前时间
            user.setUpdateTime(LocalDateTime.now());
            // 将更新后的用户信息保存到数据库
            userRepository.save(user);
            log.error("{} 刷新token失败!!!==>{}", user.getUname(),response);
            // 返回false表示刷新token失败
            return false;
        }
    }

}
