package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.UploadEnums;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/room")
public class RoomController {
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;


    @PostMapping
    public List<RecordRoom> list() {
        Iterator<RecordRoom> roomIterator = roomRepository.findAll().iterator();
        List<RecordRoom> list = new ArrayList<>();
        roomIterator.forEachRemaining(list::add);
        return list;
    }


    @PostMapping("/exportConfig")
    public void exportConfig(@RequestBody ExportConfigParams params, HttpServletResponse response) throws IOException {
        Map<String,Object> map = new HashMap<>();

        // 如果需要导出房间信息
        if(params.isExportRoom()){
            // 获取房间列表
            List<RecordRoom> roomList = this.list();
            // 将房间列表放入map中
            map.put("roomList",roomList);
        }

        // 如果需要导出用户信息
        if(params.isExportUser()){
            List<BiliBiliUser> userList = new ArrayList<>();
            // 查询所有用户
            Iterator<BiliBiliUser> userIterator = userRepository.findAll().iterator();
            // 将用户添加到用户列表中
            userIterator.forEachRemaining(userList::add);
            // 将用户列表放入map中
            map.put("userList",userList);
        }

        // 如果需要导出历史记录
        if(params.isExportHistory()){
            List<RecordHistory> historyList = new ArrayList<>();
            // 查询所有历史记录
            Iterator<RecordHistory> historyIterator = historyRepository.findAll().iterator();
            // 将历史记录添加到历史记录列表中
            historyIterator.forEachRemaining(historyList::add);
            // 将历史记录列表放入map中
            map.put("historyList",historyList);

            List<RecordHistoryPart> partList = new ArrayList<>();
            // 查询所有历史记录部分
            Iterator<RecordHistoryPart> partIterator = partRepository.findAll().iterator();
            // 将历史记录部分添加到部分列表中
            partIterator.forEachRemaining(partList::add);
            // 将部分列表放入map中
            map.put("partList",partList);
        }

        // 将map转换成JSON字符串
        String jsonString = JSON.toJSONString(map);

        // 获取当前时间并格式化
        String timeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分"));

        // 构造响应头，指定文件名，并将文件名进行URL编码
        String encodedFilename = URLEncoder.encode("biliupForJavaConfig_"+timeString+".json", StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename="+encodedFilename);

        // 将JSON字符串写入到响应输出流中
        OutputStream out = response.getOutputStream();
        out.write(jsonString.getBytes());
        out.flush();
        out.close();
    }


    @PostMapping("/uploadConfig")
    public void uploadConfig(@RequestParam("file") MultipartFile file) throws IOException {
        // 获取上传的文件内容
        byte[] bytes = file.getBytes();
        // 将文件内容转换为JSON字符串
        String json = new String(bytes);

        // 将JSON字符串转换为Map对象
        Map<String,Object> configMap = JSON.parseObject(json, new TypeReference<>() {
        });
        // 解析房间列表
        List<RecordRoom> roomList = JSON.parseObject(JSON.toJSONString(configMap.get("roomList")), new TypeReference<>() {});
        // 解析用户列表
        List<BiliBiliUser> userList = JSON.parseObject(JSON.toJSONString(configMap.get("userList")), new TypeReference<>() {});
        // 解析录制历史列表
        List<RecordHistory> historyList = JSON.parseObject(JSON.toJSONString(configMap.get("historyList")), new TypeReference<>() {});
        // 解析分P数据列表
        List<RecordHistoryPart> partList = JSON.parseObject(JSON.toJSONString(configMap.get("partList")), new TypeReference<>() {});

        // 创建一个Map用于存储用户ID的转换关系
        Map<Long,Long> userIdConverMap = new HashMap<>();

        // 处理用户列表
        if(userList != null && userList.size()>0){
            for (BiliBiliUser user : userList) {
                Long id = user.getId();
                user.setId(null);
                BiliBiliUser dbUser = userRepository.findByUid(user.getUid());
                if(dbUser != null){
                    user.setId(dbUser.getId());
                }
                userRepository.save(user);
                // 将原始ID与转换后的ID存入Map中
                userIdConverMap.put(id,user.getId());
            }
            System.out.println("导入用户配置成功，一共"+userList.size()+"条");
        }

        // 处理房间列表
        if(roomList != null && roomList.size()>0){
            for (RecordRoom room : roomList) {
                room.setId(null);
                // 将上传用户ID替换为转换后的ID
                room.setUploadUserId(userIdConverMap.get(room.getUploadUserId()));
                RecordRoom dbRoom = roomRepository.findByRoomId(room.getRoomId());
                if(dbRoom != null){
                    room.setId(dbRoom.getId());
                }
                roomRepository.save(room);
            }
            System.out.println("导入房间配置成功，一共"+roomList.size()+"条");
        }

        // 创建一个Map用于存储录制历史ID的转换关系
        Map<Long,Long> historyIdConverMap = new HashMap<>();

        // 处理录制历史列表
        if(historyList != null && historyList.size()>0){
            for (RecordHistory history : historyList) {
                Long oldId = history.getId();
                history.setId(null);
                RecordHistory dbHistory = historyRepository.findBySessionId(history.getSessionId());
                if(dbHistory != null){
                    history.setId(dbHistory.getId());
                }
                historyRepository.save(history);
                // 将原始ID与转换后的ID存入Map中
                historyIdConverMap.put(oldId,history.getId());
            }
            System.out.println("导入录制历史信息成功，一共"+historyList.size()+"条");
        }

        // 处理分P数据列表
        if(partList != null && partList.size()>0){
            for (RecordHistoryPart part : partList) {
                part.setId(null);
                RecordHistoryPart dbPart = partRepository.findByFilePath(part.getFilePath());
                if(dbPart != null){
                    part.setId(dbPart.getId());
                }
                // 将录制历史ID替换为转换后的ID
                part.setHistoryId(historyIdConverMap.get(part.getHistoryId()));
                partRepository.save(part);
            }
            System.out.println("导入分P数据成功，一共"+partList.size()+"条");
        }

        // 在控制台输出提示信息
        // 在控制台输出转换后的Map对象
        System.out.println("导入全部配置文件成功!");
    }


    @PostMapping("/update")
    public boolean update(@RequestBody RecordRoom room) {
        // 根据房间ID查询数据库中的房间记录
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            // 获取数据库中的房间记录
            RecordRoom dbRoom = roomOptional.get();
            // 更新房间记录的属性
            dbRoom.setTid(room.getTid());
            dbRoom.setTags(room.getTags());
            dbRoom.setUpload(room.isUpload());
            dbRoom.setUploadUserId(room.getUploadUserId());
            dbRoom.setTitleTemplate(room.getTitleTemplate());
            dbRoom.setPartTitleTemplate(room.getPartTitleTemplate());
            dbRoom.setDescTemplate(room.getDescTemplate());
            dbRoom.setDynamicTemplate(room.getDynamicTemplate());
            dbRoom.setCopyright(room.getCopyright());
            dbRoom.setLine(room.getLine());
            dbRoom.setCoverUrl(room.getCoverUrl());
            dbRoom.setWxuid(room.getWxuid());
            dbRoom.setPushMsgTags(room.getPushMsgTags());
            dbRoom.setFileSizeLimit(room.getFileSizeLimit());
            dbRoom.setDurationLimit(room.getDurationLimit());
            dbRoom.setDeleteType(room.getDeleteType());
            dbRoom.setDeleteDay(room.getDeleteDay());
            dbRoom.setMoveDir(room.getMoveDir());
            dbRoom.setSendDm(room.getSendDm());
            // 保存更新后的房间记录到数据库
            roomRepository.save(dbRoom);
            // 更新成功，返回true
            return true;
        }
        // 更新失败，返回false
        return false;
    }


    @PostMapping("/editLiveMsgSetting")
    public boolean editLiveMsgSetting(@RequestBody RecordRoom room) {
        // 根据房间ID查询数据库中的房间记录
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            // 获取数据库中的房间记录
            RecordRoom dbRoom = roomOptional.get();
            // 更新弹幕设置
            dbRoom.setDmDistinct(room.getDmDistinct());
            dbRoom.setDmFanMedal(room.getDmFanMedal());
            dbRoom.setDmUlLevel(room.getDmUlLevel());
            dbRoom.setDmKeywordBlacklist(room.getDmKeywordBlacklist());
            // 保存更新后的房间记录到数据库
            roomRepository.save(dbRoom);
            // 更新成功，返回true
            return true;
        }
        // 更新失败，返回false
        return false;
    }

    @PostMapping("/add")
    public Map<String, String> add(@RequestBody RecordRoom add) {
        Map<String, String> result = new HashMap<>();
        // 检查房间号是否为空
        if (StringUtils.isBlank(add.getRoomId())) {
            // 房间号为空，返回提示信息
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        // 根据房间号查询数据库中的房间记录
        RecordRoom room = roomRepository.findByRoomId(add.getRoomId());
        if (room != null) {
            // 房间号已存在，返回提示信息
            result.put("type", "warning");
            result.put("msg", "房间号已存在");
            return result;
        } else {
            // 房间号不存在，创建新的房间记录
            room = new RecordRoom();
            room.setRoomId(add.getRoomId());
            // 保存新的房间记录到数据库
            roomRepository.save(room);
            // 添加成功，返回提示信息
            result.put("type", "success");
            result.put("msg", "添加成功");
            return result;
        }
    }


    @GetMapping("/delete/{roomId}")
    public Map<String, String> add(@PathVariable("roomId") Long roomId) {
        Map<String, String> result = new HashMap<>();
        if (roomId == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        try {
            // 查询房间记录
            Optional<RecordRoom> roomOptional = roomRepository.findById(roomId);
            if (roomOptional.isPresent()) {
                // 删除房间记录
                roomRepository.delete(roomOptional.get());
                // 返回成功信息
                result.put("type", "success");
                result.put("msg", "房间删除成功");
                return result;
            } else {
                // 返回房间不存在的警告信息
                result.put("type", "warning");
                result.put("msg", "房间不存在");
                return result;
            }
        } catch (Exception e) {
            // 返回房间删除失败的错误信息
            result.put("type", "error");
            result.put("msg", "房间删除失败==>" + e.getMessage());
            return result;
        }
    }


    @PostMapping("/uploadCover")
    public Map<String, String> uploadCover(@RequestParam Long id, @RequestParam("file") MultipartFile file) {
        Map<String, String> result = new HashMap<>();

        // 检查房间号是否为空
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        try {
            // 读取上传的图片文件
            BufferedImage image = ImageIO.read(file.getInputStream());

            // 检查图片是否为空
            if (image == null) {
                result.put("type", "warning");
                result.put("msg", "请上传图片文件");
                return result;
            }

            // 检查图片分辨率是否满足要求
            if (image.getWidth() < 1146 || image.getHeight() < 717) {
                result.put("type", "warning");
                result.put("msg", "上传图片分辨率不低于1146*717,当前分辨率为"+image.getWidth()+"*"+image.getHeight());
                return result;
            }
        } catch (IOException e) {
            result.put("type", "warning");
            result.put("msg", "封面上传失败：" + e.getMessage());
            return result;
        }

        // 根据房间号查询房间记录
        Optional<RecordRoom> roomOptional = roomRepository.findById(id);

        if (roomOptional.isPresent()) {
            try {
                RecordRoom room = roomOptional.get();

                // 获取房间上传用户ID
                Long userId = room.getUploadUserId();

                // 检查上传用户ID是否为空
                if (userId == null) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }

                // 根据上传用户ID查询用户记录
                Optional<BiliBiliUser> userOptional = userRepository.findById(userId);

                // 检查用户记录是否存在
                if (!userOptional.isPresent()) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }

                BiliBiliUser user = userOptional.get();

                // 获取图片文件内容
                byte[] bytes = file.getBytes();

                // 调用BiliApi上传封面接口
                String response = BiliApi.uploadCover(user, file.getName(), bytes);

                // 解析返回结果中的封面URL
                String url = JsonPath.read(response, "data.url");

                // 检查封面URL是否为空
                if (StringUtils.isNotBlank(url)) {
                    // 设置房间封面URL并保存
                    room.setCoverUrl(url);
                    roomRepository.save(room);

                    // 返回上传成功的结果
                    result.put("type", "success");
                    result.put("coverUrl", url);
                    result.put("msg", "封面上传成功");
                    return result;
                }

            } catch (IOException e) {
                result.put("type", "warning");
                result.put("msg", "封面上传失败：" + e.getMessage());
                return result;
            }

            // 封面上传失败
            result.put("type", "warning");
            result.put("msg", "封面上传失败");
            return result;
        } else {
            // 房间不存在
            result.put("type", "warning");
            result.put("msg", "房间不存在");
            return result;
        }
    }


    @GetMapping("/lines")
    public UploadEnums[] lines() {
        // 返回UploadEnums枚举的所有值
        return UploadEnums.values();
    }

    @GetMapping("/verification")
    public String verification(String template) {
        // 将模板中的占位符替换为对应的文字
        template = template.replace("${uname}", "主播名称")
                .replace("${title}", "直播标题")
                .replace("${roomId}", "房间号");

        // 检查模板中是否还有未替换的占位符
        if (template.contains("${")) {
            // 提取占位符中的日期格式
            String date = template.substring(template.indexOf("${"), template.indexOf("}") + 1);
            // 获取当前时间并按照占位符中的格式进行格式化
            String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
            // 将模板中的占位符替换为格式化后的日期
            template = template.replace(date, format);
        }

        // 返回替换后的模板
        return template;
    }

}
