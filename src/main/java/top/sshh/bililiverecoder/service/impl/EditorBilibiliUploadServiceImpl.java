package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadEnums;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.EditorChunkUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.EditorPreUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.EdtiorCompleteUploadRequest;
import top.sshh.bililiverecoder.util.bili.upload.EdtiorTranscodeRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.EditorPreUploadBean;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;

import java.io.File;
import java.io.RandomAccessFile;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service("editorBilibiliUploadService")
public class EditorBilibiliUploadServiceImpl implements RecordPartUploadService {

    public static final String OS = "editor";

    @Value("${record.wx-push-token}")
    private String wxToken;
    private static final String WX_MSG_FORMAT = """
            上传结果: %s
            收到主播%s云剪辑上传%s事件
            房间名: %s
            时间: %s
            文件路径: %s
            文件录制开始时间: %s
            文件录制时长: %s 分钟
            文件录制大小: %.3f GB
            原因: %s
            """;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Override
    public void asyncUpload(RecordHistoryPart part) {
        log.info("partId={},异步上传任务开始==>{}", part.getId(), part.getFilePath());
        this.upload(part);
    }

    @Override
    public void upload(RecordHistoryPart part) {
        // 获取指定分片ID对应的线程
        Thread thread = TaskUtil.partUploadTask.get(part.getId());

        // 如果线程不为空且不是当前线程
        if (thread != null && thread != Thread.currentThread()) {
            // 输出日志，显示当前线程、分片ID以及正在上传该分片的线程名称
            log.info("当前线程为{} ,partId={}该文件正在被{}线程上传", Thread.currentThread(), part.getId(), thread.getName());
            // 返回，不执行后续代码
            return;
        }

        // 将当前线程与分片ID关联起来，表示当前线程正在上传该分片
        TaskUtil.partUploadTask.put(part.getId(), Thread.currentThread());

        try {
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());

            if (room != null) {
                // 根据房间线路查找上传枚举类型
                UploadEnums uploadEnums = UploadEnums.find(room.getLine());
                // 获取房间对应的微信用户ID
                String wxuid = room.getWxuid();
                // 获取房间对应的推送消息标签
                String pushMsgTags = room.getPushMsgTags();

                // 获取分片文件的路径，并进行字符串常量池优化
                // 上传任务入队列
                String filePath = part.getFilePath().intern();
                // 创建文件对象
                File uploadFile = new File(filePath);
                // 判断文件是否存在
                if (!uploadFile.exists()) {
                    // 如果文件不存在，则记录错误日志
                    log.error("分片上传失败，文件不存在==>{}", filePath);
                    // 退出方法
                    return;
                }

                synchronized (filePath) {
                    // 查找历史记录
                    Optional<RecordHistory> historyOptional = historyRepository.findById(part.getHistoryId());
                    if (!historyOptional.isPresent()) {
                        // 如果历史记录不存在
                        log.error("分片上传失败，history不存在==>{}", JSON.toJSONString(part));
                        // 从任务中移除分片
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    }
                    RecordHistory history = historyOptional.get();
                    if (room.getUploadUserId() == null) {
                        // 如果上传用户ID为空
                        log.info("分片上传事件，没有设置上传用户，无法上传 ==>{}", JSON.toJSONString(room));
                        // 从任务中移除分片
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    } else {
                        // 查找上传用户
                        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
                        if (!userOptional.isPresent()) {
                            log.error("分片上传事件，上传用户不存在，无法上传 ==>{}", JSON.toJSONString(room));
                            TaskUtil.partUploadTask.remove(part.getId());
                            return;
                        }
                        // 获取上传用户对象
                        BiliBiliUser biliBiliUser = userOptional.get();
                        // 检查用户是否登录
                        if (!biliBiliUser.isLogin()) {
                            log.error("分片上传事件，用户登录状态失效，无法上传，请重新登录 ==>{}", JSON.toJSONString(room));
                            TaskUtil.partUploadTask.remove(part.getId());
                            return;
                        }

                        // 检查是否已经过期，调用用户信息接口
                        // 解析用户cookie
                        // 登录验证结束
                        WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());
                        // 创建UserMy对象
                        UserMy userMy = new UserMy(webCookie);
                        // 获取用户信息
                        UserMyRootBean myInfo = userMy.getPojo();
                        if (myInfo.getCode() == -101) {
                            // 登录已过期，设置登录状态为false
                            biliBiliUser.setLogin(false);
                            // 保存更新后的用户信息
                            biliBiliUser = biliUserRepository.save(biliBiliUser);
                            // 从任务列表中移除该分片任务
                            TaskUtil.partUploadTask.remove(part.getId());

                            // 如果微信用户ID不为空、推送消息标签不为空且包含"云剪辑"
                            if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("云剪辑")) {
                                // 创建消息对象
                                Message message = new Message();
                                // 设置应用令牌
                                message.setAppToken(wxToken);
                                // 设置消息类型为文本
                                message.setContentType(Message.CONTENT_TYPE_TEXT);
                                // 设置消息内容，包含上传失败信息、用户名、开始时间、标题、当前时间、文件路径、开始时间、时长、文件大小以及登录过期提示和线路信息
                                message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "登录已过期，请重新登录\n" + "线路：" + uploadEnums.getLine()));
                                // 设置接收消息的用户ID
                                message.setUid(wxuid);
                                // 发送消息
                                WxPusher.send(message);
                            }

                            // 抛出运行时异常，提示用户登录已过期
                            throw new RuntimeException("{}登录已过期，请重新登录! " + biliBiliUser.getUname());
                        }

                        // 获取上传文件的大小
                        // 登录验证结束
                        long fileSize = uploadFile.length();
                        Map<String, String> preParams = new HashMap<>();

                        // 如果分片标题不为空
                        if(StringUtils.isNotBlank(part.getTitle())){
                            // 将用户名和分片标题拼接作为新的文件名
                            preParams.put("name", room.getUname()+part.getTitle());
                        }else {
                            // 否则使用上传文件的原始名称作为文件名
                            preParams.put("name", room.getUname()+uploadFile.getName());
                        }

                        preParams.put("resource_file_type", "flv");
                        preParams.put("size", String.valueOf(fileSize));
                        EditorPreUploadRequest preuploadRequest = new EditorPreUploadRequest(webCookie, preParams);
                        EditorPreUploadBean preUploadBean = preuploadRequest.getPojo();

                        // 分段上传开始
                        // 分段上传\
                        Long chunkSize = preUploadBean.getData().getPer_size();
                        int chunkNum = (int)Math.ceil((double)fileSize / chunkSize);

                        // 已上传的分段计数
                        AtomicInteger upCount = new AtomicInteger(0);
                        // 尝试上传的次数计数
                        AtomicInteger tryCount = new AtomicInteger(0);

                        // 存储每个分段的ETag的数组
                        String[] etagArray = new String[chunkNum];

                        // 存储每个分段上传任务的列表
                        List<Runnable> runnableList = new ArrayList<>();

                        for (int i = 0; i < chunkNum; i++) {
                            int finalI = i;
                            Runnable runnable = () -> {
                                try {
                                    while (tryCount.get() < 200) {
                                        try {
                                            // 上传视频分块
                                            // 上传
                                            long endSize = (finalI + 1) * chunkSize;
                                            long finalChunkSize = chunkSize;
                                            Map<String, String> chunkParams = new HashMap<>();
                                            chunkParams.put("index", String.valueOf(finalI));
                                            chunkParams.put("size", String.valueOf(finalChunkSize));
                                            chunkParams.put("start", String.valueOf(finalI * finalChunkSize));
                                            chunkParams.put("end", String.valueOf(endSize));
                                            if (endSize > fileSize) {
                                                endSize = fileSize;
                                                finalChunkSize = fileSize - (finalI * finalChunkSize);
                                                chunkParams.put("size", String.valueOf(finalChunkSize));
                                                    chunkParams.put("end", String.valueOf(endSize));
                                                }
                                            EditorChunkUploadRequest chunkUploadRequest = new EditorChunkUploadRequest(preUploadBean, chunkParams, new RandomAccessFile(filePath, "r"));
                                            String etag = chunkUploadRequest.getPage();
                                            etagArray[finalI]=etag;
                                            int count = upCount.incrementAndGet();
                                            log.info("{}==>[{}] 上传视频part {} 进度{}/{}", Thread.currentThread().getName(), room.getTitle(),
                                                    filePath, count, chunkNum);
                                            break;
                                            // 上传失败处理
                                        } catch (Exception e) {
                                            tryCount.incrementAndGet();
                                            log.info("{}==>[{}] 上传视频part {}, index {}, size {}, start {}, end {}, exception={}", Thread.currentThread().getName(), room.getTitle(),
                                                    filePath, finalI, chunkSize, finalI * chunkSize, (finalI + 1) * chunkSize, ExceptionUtils.getStackTrace(e));
                                            try {
                                                // 等待十秒后重试上传
                                                // log.info("上传失败等待十秒==>{}", uploadFile.getName());
                                                Thread.sleep(10000L);
                                            } catch (InterruptedException ex) {
                                                ex.printStackTrace();
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            };
                            runnableList.add(runnable);
                        }

                        //并发上传
                        Message message = new Message();
                        // 判断wxuid和pushMsgTags是否不为空，并且pushMsgTags包含"云剪辑"
                        if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("云剪辑")) {
                            // 设置message的AppToken为wxToken
                            message.setAppToken(wxToken);
                            // 设置message的ContentType为文本类型
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            // 设置message的Content为格式化后的字符串
                            message.setContent(WX_MSG_FORMAT.formatted("开始上传", room.getUname(), "开始", room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "\n线路：无"));
                            // 设置message的Uid为wxuid
                            message.setUid(wxuid);
                            // 发送message
                            WxPusher.send(message);
                        }

                        runnableList.stream().parallel().forEach(Runnable::run);

                        if (tryCount.get() >= 200) {
                            // 设置上传状态为false
                            part.setUpload(false);
                            // 保存part到数据库
                            part = partRepository.save(part);

                            // 查询history记录
                            historyOptional = historyRepository.findById(history.getId());
                            if (historyOptional.isPresent()) {
                                history = historyOptional.get();
                                // 上传重试次数加1
                                history.setUploadRetryCount(history.getUploadRetryCount() + 1);
                                // 保存history到数据库
                                history = historyRepository.save(history);
                            }

                            // 从任务队列中移除该part的上传任务
                            //存在异常
                            TaskUtil.partUploadTask.remove(part.getId());

                            if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("云剪辑")) {
                                // 设置消息的应用令牌
                                message.setAppToken(wxToken);
                                // 设置消息的内容类型为文本
                                message.setContentType(Message.CONTENT_TYPE_TEXT);
                                // 设置消息的内容
                                message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "并发上传失败，存在异常\n" + "线路：" + uploadEnums.getLine()));
                                // 设置消息的接收者ID
                                message.setUid(wxuid);
                                // 发送消息
                                WxPusher.send(message);
                            }

                            // 抛出运行时异常，表示并发上传失败
                            throw new RuntimeException(part.getFileName() + "===并发上传失败，存在异常");
                        }

                        //通知服务器上传完成
                        // 查找用户
                        userOptional = biliUserRepository.findById(room.getUploadUserId());
                        // 获取用户信息
                        biliBiliUser = userOptional.get();
                        // 解析用户的cookies
                        webCookie = Cookie.parse(biliBiliUser.getCookies());

                        // 创建存储完整参数的Map对象
                        Map<String, String> completeParams = new HashMap<>();
                        // 将etagArray数组转换为以逗号分隔的字符串，并放入completeParams中
                        completeParams.put("etags", String.join(",", etagArray));

                        // 创建EdtiorCompleteUploadRequest对象，并传入webCookie、preUploadBean和completeParams
                        EdtiorCompleteUploadRequest completeUploadRequest = new EdtiorCompleteUploadRequest(webCookie, preUploadBean, completeParams);
                        // 获取CompleteUploadBean对象
                        CompleteUploadBean completeUploadBean = completeUploadRequest.getPojo();

                        // 输出日志，表示云剪辑上传完成，并打印completeUploadBean的JSON字符串
                        log.info("{}，云剪辑上传完成，==>{}",part.getTitle(),JSON.toJSONString(completeUploadBean));

                        try {
                            // 等待五秒
                            //等待五秒在开始转码
                            Thread.sleep(5000L);
                        }catch (Exception ignored){
                        // 忽略异常
                        }

                        // 创建EdtiorTranscodeRequest对象，并传入webCookie和preUploadBean
                        EdtiorTranscodeRequest transcodeRequest = new EdtiorTranscodeRequest(webCookie, preUploadBean);
                        // 获取转码请求的页面内容
                        String page = transcodeRequest.getPage();

                        // 输出日志，表示云剪辑转码请求完成，并打印页面内容
                        log.info("{}，云剪辑转码请求完成，==>{}",part.getTitle(),page);

                        if (Integer.valueOf(0).equals(completeUploadBean.getCode())) {
                            // 如果上传成功
                            if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("云剪辑")) {
                                // 设置消息的应用令牌
                                message.setAppToken(wxToken);
                                // 设置消息的内容类型为文本
                                message.setContentType(Message.CONTENT_TYPE_TEXT);
                                // 设置消息的内容，包含上传成功的信息
                                message.setContent(WX_MSG_FORMAT.formatted("上传成功", room.getUname(), "结束", room.getTitle(),
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), ""));
                                // 设置消息的接收者ID
                                message.setUid(wxuid);
                                // 发送消息
                                WxPusher.send(message);
                            }
                        } else {
                            // 如果上传失败
                            if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("云剪辑")) {
                                // 设置消息的应用令牌
                                message.setAppToken(wxToken);
                                // 设置消息的内容类型为文本
                                message.setContentType(Message.CONTENT_TYPE_TEXT);
                                // 设置消息的内容，包含上传失败的信息
                                message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "结束", room.getTitle(),
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                        part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), ""));
                                // 设置消息的接收者ID
                                message.setUid(wxuid);
                                // 发送消息
                                WxPusher.send(message);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("云剪辑上传发生错误", e);
        } finally {
            TaskUtil.partUploadTask.remove(part.getId());
        }


    }
}
