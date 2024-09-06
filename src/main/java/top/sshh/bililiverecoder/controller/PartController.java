package top.sshh.bililiverecoder.controller;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.EdtiorSpaceRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.EditorSpaceBean;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/part")
public class PartController {

    @Autowired
    private BiliUserRepository userRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Resource(name = "editorBilibiliUploadService")
    private RecordPartUploadService editPartUploadService;

    @PostMapping("/list/{id}")
    public List<RecordHistoryPart> list(@PathVariable("id") Long id) {
        return partRepository.findByHistoryIdOrderByStartTimeAsc(id);
    }



    @GetMapping("/uploadEditor/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            // 如果id为空
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        // 查找指定id的RecordHistoryPart对象
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isPresent()) {
            // 如果RecordHistoryPart对象存在
            RecordHistoryPart part = partOptional.get();
            String filePath = part.getFilePath();
            File file = new File(filePath);
            if(file.exists()){
                // 查找指定roomId的RecordRoom对象
                RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
                if(room.getUploadUserId() == null){
                    // 如果Room对象没有配置上传用户
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                // 查找指定uploadUserId的BiliBiliUser对象
                Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
                if(userOptional.isEmpty()){
                    // 如果BiliBiliUser对象不存在
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                BiliBiliUser user = userOptional.get();
                WebCookie cookie = Cookie.parse(user.getCookies());
                EdtiorSpaceRequest edtiorSpaceRequest = new EdtiorSpaceRequest(cookie);
                try {
                    // 获取云剪辑空间信息
                    EditorSpaceBean spaceBean = edtiorSpaceRequest.getPojo();
                    EditorSpaceBean.Data data = spaceBean.getData();
                    long freeSize = data.getTotal() - data.getUsed();
                    if(freeSize<file.length()){
                        // 如果云剪辑剩余空间不足
                        result.put("type", "warning");
                        result.put("msg", "云剪辑剩余空间不足，剩余"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                        return result;
                    }
                    // 异步上传文件
                    editPartUploadService.asyncUpload(part);
                    // 上传成功
                    result.put("type", "success");
                    result.put("msg", "云剪辑上传成功，剩余空间"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                    return result;

                } catch (HttpException e) {
                    // 查询云剪辑剩余空间发生异常
                    result.put("type", "warning");
                    result.put("msg", "查询云剪辑剩余空间发生错误");
                    return result;
                }

            } else {
                // 分p文件不存在
                result.put("type", "warning");
                result.put("msg", "分p文件不存在");
                return result;
            }
        } else {
            // 分p不存在
            result.put("type", "warning");
            result.put("msg", "分p不存在");
            return result;
        }
    }

}
