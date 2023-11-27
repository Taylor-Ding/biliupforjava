package top.sshh.bililiverecoder.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordEventData;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;

import java.io.File;

@Slf4j
@Component
public class RecordEventFilePostService implements RecordEventService {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private BiliUserRepository biliUserRepository;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository historyPartRepository;

    @Autowired
    private UploadServiceFactory uploadServiceFactory;

    @Autowired
    private LiveMsgService liveMsgService;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replace("\\", "/");
    }


    @Override
    public void processing(RecordEventDTO event) {
        RecordEventData eventData = event.getEventData();
        String sessionId = eventData.getSessionId();
        String relativePath = eventData.getRelativePath();
        log.info("分p录制视频文件后处理事件==>{}", relativePath);
        String filePath = workPath + File.separator + relativePath;
        // 正常逻辑
        String name = filePath.substring(0, filePath.lastIndexOf('.'));
        RecordHistoryPart part = historyPartRepository.findByFilePathStartingWith(name);
        if (part == null) {
            log.info("文件分片不存在==>{}", relativePath);
            return;
        }
        File vidleFile = new File(filePath);
        long fileSize = 0;
        if (vidleFile.exists()) {
            fileSize = vidleFile.length();
        } else {
            log.error("文件{}不存在，请考虑工作目录是否设置正确，或者docker 卷是否映射正确", filePath);
            fileSize = eventData.getFileSize();
        }
        part.setRecording(false);
        part.setFilePath(filePath);
        part.setFileSize(fileSize);
        part = historyPartRepository.save(part);
        log.info("分p录制视频文件后处理成功，文件路径保存成功，路径{}", filePath);
    }
}
