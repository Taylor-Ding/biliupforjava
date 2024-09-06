package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliSessionDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.util.BiliApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/biliUser")
public class BiliUserController {

    @Autowired
    BiliUserRepository biliUserRepository;

    private final Map<String, Future<String>> futureMap = new HashMap<>();
    ExecutorService service = Executors.newFixedThreadPool(10);

    @GetMapping("/login")
    public String loginUser() throws Exception {
        // 生成二维码URL
        BiliApi.BiliResponseDto<BiliApi.GenerateQRDto> s = BiliApi.generateQRUrlTV();
        if (s.getCode() != 0) {
            throw new RuntimeException("生成二维码异常，请检查日志");
        }

        // 将二维码URL转换为二维码图片
        BitMatrix bm = new QRCodeWriter().encode(s.getData().getUrl(),
                BarcodeFormat.QR_CODE, 256, 256);
        BufferedImage bi = MatrixToImageWriter.toBufferedImage(bm);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpg", stream);
        byte[] bytes = Base64.encodeBase64(stream.toByteArray());

        // 偷懒直接new一个Thread
        // 创建线程检查登录状态
        // new thread to check login status
        Callable<String> callable = () -> {
            try {
                Thread.sleep(5000);
                String loginResp = "";
                for (int i = 0; i < 60; i++) {
                    // 调用登录接口检查登录状态
                    loginResp = BiliApi.loginOnTV(s.getData().getAuth_code());

                    // 解析登录响应
                    Integer code = JsonPath.read(loginResp, "code");

                    // 如果登录成功
                    if (code == 0) {
                        // 解析登录成功的响应数据
                        BiliSessionDto dto = JSON.parseObject(loginResp).getObject("data", BiliSessionDto.class);

                        // 根据用户ID查询用户信息
                        BiliBiliUser biliUser = biliUserRepository.findByUid(dto.getMid());

                        // 如果用户不存在，则创建新用户
                        if (biliUser == null) {
                            biliUser = new BiliBiliUser();
                        }

                        // 解析cookie信息
                        JSONArray cookies = JSON.parseArray(JsonPath.read(loginResp, "data.cookie_info.cookies").toString());
                        StringBuilder cookieString = new StringBuilder();
                        for (Object object : cookies) {
                            JSONObject cookie = (JSONObject) object;
                            cookieString.append(cookie.get("name").toString());
                            cookieString.append(":");
                            cookieString.append(cookie.get("value").toString());
                            cookieString.append("; ");
                        }

                        // 设置用户信息
                        biliUser.setCookies(cookieString.toString());
                        biliUser.setUid(dto.getMid());
                        biliUser.setAccessToken(dto.getAccessToken());
                        biliUser.setRefreshToken(dto.getRefreshToken());
                        biliUser.setLogin(true);
                        biliUser.setUpdateTime(LocalDateTime.now());

                        // 调用接口获取用户信息
                        String userInfo = BiliApi.appMyInfo(biliUser);

                        // 解析用户信息并设置到用户对象中
                        biliUser.setUname(JsonPath.read(userInfo, "data.uname"));

                        // 打印登录成功信息
                        log.info("{} 登录成功！！！", biliUser.getUname());

                        // 保存用户信息到数据库
                        biliUserRepository.save(biliUser);

                        // 返回登录成功信息
                        return "登录成功";

                        // 如果登录超时
                    } else if (code == 86038) {
                        // 打印登录超时信息
                        log.info("扫码超时");

                        // 返回登录超时信息
                        return JsonPath.read(loginResp, "message");
                    }

                    // 等待5秒后再次检查登录状态
                    Thread.sleep(5000);
                }

                // 登录失败，返回失败信息
                return "登录失败，" + JsonPath.read(loginResp, "message");
            } catch (InterruptedException e) {
                // 如果线程被中断，返回登录失败信息
                return "登录失败";
            }
        };

        // 提交任务并获取Future对象
        Future<String> submit = service.submit(callable);

        // 将图片转换为Base64编码字符串
        String imagesBase64 = new String(bytes);
        // 将截取后的图片Base64编码字符串作为键，Future对象作为值存入futureMap中
        futureMap.put(imagesBase64.substring(imagesBase64.length() - 100), submit);
        // 返回图片Base64编码字符串
        return imagesBase64;

    }

    @GetMapping("loginReturn")
    public Map<String, String> loginReturn(@RequestParam String key) throws ExecutionException, InterruptedException, TimeoutException {
        // 从futureMap中获取Future对象
        Future<String> stringFuture = futureMap.get(key);
        // 创建一个结果Map
        Map<String, String> result = new HashMap<>();
        // 如果Future对象为null
        if (stringFuture == null) {
            // 在结果Map中添加类型字段，值为"warning"
            result.put("type", "warning");
            // 在结果Map中添加消息字段，值为"登录失败"
            result.put("msg", "登录失败");
            // 返回结果Map
            return result;
        } else {
            // 从futureMap中移除key对应的Future对象
            futureMap.remove(key);
            // 在结果Map中添加类型字段，值为"warning"
            result.put("type", "warning");
            // 获取Future对象的结果，并设置超时时间为5分钟
            // 将结果添加到结果Map的消息字段中
            result.put("msg", stringFuture.get(5, TimeUnit.MINUTES));
            // 返回结果Map
            return result;
        }
    }

    @GetMapping("/list")
    public List<BiliBiliUser> listBillUser() {
        // 创建一个BiliBiliUser列表
        List<BiliBiliUser> list = new ArrayList<>();
        // 遍历biliUserRepository中的所有BiliBiliUser对象
        for (BiliBiliUser biliBiliUser : biliUserRepository.findAll()) {
            // 将BiliBiliUser对象的accessToken字段设置为null
            biliBiliUser.setAccessToken(null);
            // 将BiliBiliUser对象的refreshToken字段设置为null
            biliBiliUser.setRefreshToken(null);
            // 将BiliBiliUser对象添加到列表中
            list.add(biliBiliUser);
        }
        // 返回列表
        return list;
    }

    @PostMapping("/update")
    public boolean updateBillUser(@RequestBody BiliBiliUser user) {
        // 根据用户ID查询数据库中的用户
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(user.getId());
        if (userOptional.isPresent()) {
            // 获取数据库中的用户对象
            BiliBiliUser dbUser = userOptional.get();
            // 更新用户的启用状态
            dbUser.setEnable(user.isEnable());
            // 更新用户的更新时间
            dbUser.setUpdateTime(LocalDateTime.now());
            // 保存更新后的用户对象到数据库
            biliUserRepository.save(dbUser);
        }
        // 返回false表示更新失败，但实际上应该返回true或false来表示更新是否成功
        return false;
    }

    @GetMapping("/delete/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            // 用户ID为空时，返回提示信息
            result.put("type", "info");
            result.put("msg", "请输入用户id");
            return result;
        }

        // 根据用户ID查询数据库中的用户
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(id);
        if (userOptional.isPresent()) {
            // 如果用户存在，则删除用户
            biliUserRepository.delete(userOptional.get());
            // 返回删除成功的信息
            result.put("type", "success");
            result.put("msg", "用户删除成功");
            return result;
        } else {
            // 如果用户不存在，则返回用户不存在的信息
            result.put("type", "warning");
            result.put("msg", "用户不存在");
            return result;
        }
    }
}
