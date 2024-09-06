package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.user.UserMy;
import top.sshh.bililiverecoder.util.bili.user.UserMyRootBean;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service("appRecordPartBilibiliUploadService")
public class AppRecordPartBilibiliUploadService implements RecordPartUploadService {

    public static final String OS = "app";

    @Value("${record.work-path}")
    private String workPath;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    private static final String WX_MSG_FORMAT = """
            上传结果: %s
            收到主播%s分P上传%s事件
            房间名: %s
            时间: %s
            文件路径: %s
            文件录制开始时间: %s
            文件录制时长: %s 分钟
            文件录制大小: %.3f GB
            原因: %s
            """;
    @Value("${record.wx-push-token}")
    private String wxToken;
    @Autowired
    private BiliUserRepository biliUserRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Lazy
    @Autowired
    private UploadServiceFactory uploadServiceFactory;


    /**
     * 异步上传记录的历史部分文件
     *
     * @param part 需要上传的历史部分对象
     *            该方法首先从数据库通过ID查找指定的历史部分对象，
     *            然后开始异步上传任务，上传文件路径由该对象的filePath属性指定
     */
    @Override
    public void asyncUpload(RecordHistoryPart part) {
        // 从数据库获取指定ID的历史部分对象
        part = partRepository.findById(part.getId()).get();

        // 记录异步上传任务开始的信息，包括部分ID和文件路径
        log.info("partId={},异步上传任务开始==>{}", part.getId(), part.getFilePath());

        // 执行上传任务
        this.upload(part);
    }

    @Override
    public void upload(RecordHistoryPart part) {
        // 根据分片ID从数据库中获取分片信息
        part = partRepository.findById(part.getId()).get();

        // 获取当前分片ID对应的上传线程
        Thread thread = TaskUtil.partUploadTask.get(part.getId());

        // 如果存在上传线程且不是当前线程
        if (thread != null && thread != Thread.currentThread()) {
            // 打印日志信息，表示当前线程和分片ID以及正在上传的线程信息
            log.info("当前线程为{} ,partId={}该文件正在被{}线程上传", Thread.currentThread(), part.getId(), thread.getName());
            // 返回，不再继续执行上传操作
            return;
        }

        // 将当前线程放入分片ID对应的上传线程中
        TaskUtil.partUploadTask.put(part.getId(), Thread.currentThread());

        try {

            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());


            if (room != null) {
                String wxuid = room.getWxuid();
                String pushMsgTags = room.getPushMsgTags();
                if (room.getTid() == null) {
                    // 如果没有设置分区，则直接取消上传
                    //没有设置分区，直接取消上传
                    return;
                }
                // 将上传任务加入队列
                // 上传任务入队列
                String filePath = part.getFilePath().intern();

                synchronized (filePath) {
                    // 查找记录历史
                    Optional<RecordHistory> historyOptional = historyRepository.findById(part.getHistoryId());
                    if (!historyOptional.isPresent()) {
                        // 如果记录历史不存在
                        log.error("分片上传失败，历史记录不存在==>{}", JSON.toJSONString(part));
                        // 从上传任务中移除当前分片
                        TaskUtil.partUploadTask.remove(part.getId());
                        return;
                    }
                    // 获取记录历史
                    RecordHistory history = historyOptional.get();
                    // 创建上传文件对象
                    File uploadFile = new File(filePath);
                    if (!uploadFile.exists()) {
                        // 如果上传文件不存在
                        log.error("分片上传失败，文件不存在==>{}", filePath);
                        if (history.getUploadRetryCount() < 2) {
                            // 如果重试次数小于2次
                            // 更新记录历史中的分片数量
                            history.setRecordPartCount(history.getRecordPartCount());
                            // 保存更新后的记录历史
                            history = historyRepository.save(history);
                            // 异步上传分片
                            uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                        }
                        return;
                    }

                    if (history.isUpload()) {
                        if (room.getUploadUserId() == null) {
                            log.info("分片上传事件，没有设置上传用户，无法上传 ==>{}", JSON.toJSONString(room));
                            TaskUtil.partUploadTask.remove(part.getId());
                            return;
                        } else {
                            // 查询上传用户
                            Optional<BiliBiliUser> userOptional = biliUserRepository.findById(room.getUploadUserId());
                            if (!userOptional.isPresent()) {
                                // 如果上传用户不存在
                                log.error("分片上传事件，上传用户不存在，无法上传 ==>{}", JSON.toJSONString(room));
                                // 从上传任务中移除当前分片
                                TaskUtil.partUploadTask.remove(part.getId());
                                return;
                            }
                            // 获取上传用户
                            BiliBiliUser biliBiliUser = userOptional.get();
                            if (!biliBiliUser.isLogin()) {
                                // 如果用户登录状态失效
                                log.error("分片上传事件，用户登录状态失效，无法上传，请重新登录 ==>{}", JSON.toJSONString(room));
                                // 从上传任务中移除当前分片
                                TaskUtil.partUploadTask.remove(part.getId());
                                return;
                            }

                            // 检查是否已经过期，调用用户信息接口
                            // 登录验证结束
                            // 解析WebCookie
                            WebCookie webCookie = Cookie.parse(biliBiliUser.getCookies());

                            // 创建UserMy对象
                            UserMy userMy = new UserMy(webCookie);

                            // 获取UserMyRootBean对象
                            UserMyRootBean myInfo = userMy.getPojo();

                            // 如果myInfo的code为-101，表示登录状态失效
                            if (myInfo.getCode() == -101) {
                                // 设置biliBiliUser的登录状态为false
                                biliBiliUser.setLogin(false);

                                // 保存biliBiliUser到数据库
                                biliBiliUser = biliUserRepository.save(biliBiliUser);

                                // 从上传任务中移除当前分片
                                TaskUtil.partUploadTask.remove(part.getId());

                                // 如果wxuid不为空，pushMsgTags不为空，且pushMsgTags包含"分P上传"
                                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("分P上传")) {
                                    // 创建Message对象
                                    Message message = new Message();

                                    // 设置Message的appToken
                                    message.setAppToken(wxToken);

                                    // 设置Message的内容类型为文本
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);

                                    // 设置Message的内容
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "登录已过期，请重新登录"));

                                    // 设置Message的uid
                                    message.setUid(wxuid);

                                    // 发送消息
                                    WxPusher.send(message);
                                }

                                // 抛出异常，提示用户登录已过期
                                throw new RuntimeException("{}登录已过期，请重新登录! " + biliBiliUser.getUname());
                            }

                            // 登录验证结束
                            // 调用BiliApi的preUpload方法进行预上传
                            String preRes = BiliApi.preUpload(biliBiliUser, "ugcfr/pc3");
                            log.error("预上传请求==>" + preRes);

                            // 将预上传的响应结果解析为JSONObject对象
                            JSONObject preResObj = JSON.parseObject(preRes);

                            // 获取预上传结果中的url
                            String url = preResObj.getString("url");

                            // 获取预上传结果中的complete
                            String complete = preResObj.getString("complete");

                            // 获取预上传结果中的filename
                            String filename = preResObj.getString("filename");

                            // 以下是分段上传的逻辑
                            // 分段上传
                            // 获取上传文件的大小
                            long fileSize = uploadFile.length();

                            // 设置每个分段的大小为5MB
                            long chunkSize = 1024 * 1024 * 5;

                            // 计算分段的数量
                            long chunkNum = (long)Math.ceil((double)fileSize / chunkSize);

                            // 初始化一个AtomicInteger，用于记录上传的计数
                            AtomicInteger upCount = new AtomicInteger(0);

                            // 初始化一个AtomicInteger，用于记录尝试上传的计数
                            AtomicInteger tryCount = new AtomicInteger(0);

                            // 创建一个Runnable对象的列表，用于存储每个分段的上传任务
                            List<Runnable> runnableList = new ArrayList<>();

                            for (int i = 0; i < chunkNum; i++) {
                                int finalI = i;
                                Runnable runnable = () -> {
                                    try (RandomAccessFile r = new RandomAccessFile(filePath, "r")) {
                                        while (tryCount.get() < 200) {
                                            try {
                                                // 上传每个分段
                                                // 上传
                                                String s = BiliApi.uploadChunk(url, filename, r, chunkSize,
                                                        finalI + 1, (int)chunkNum);
                                                if (!s.contains("OK")) {
                                                    throw new RuntimeException("上传返回异常");
                                                }
                                                int count = upCount.incrementAndGet();
                                                log.info("{}==>[{}] 上传视频part {} 进度{}/{}, resp={}", Thread.currentThread().getName(), room.getTitle(),
                                                        filePath, count, chunkNum, s);
                                                break;
                                            } catch (Exception e) {
                                                tryCount.incrementAndGet();
                                                int count = upCount.get();
                                                log.info("{}==>[{}] 上传视频part {} 进度{}/{}, exception={}", Thread.currentThread().getName(), room.getTitle(),
                                                        filePath, count, chunkNum, ExceptionUtils.getStackTrace(e));
                                            }
                                        }
                                    } catch (FileNotFoundException fileNotFoundException) {
                                        tryCount.set(200);
                                        log.error("上传失败，{}文件不存在", filePath);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                };
                                runnableList.add(runnable);
                            }


                            //并发上传
                            // 创建Message对象
                            Message message = new Message();
                            // 设置AppToken
                            message.setAppToken(wxToken);
                            // 设置消息类型为文本
                            message.setContentType(Message.CONTENT_TYPE_TEXT);
                            // 设置消息内容
                            message.setContent(WX_MSG_FORMAT.formatted("开始上传", room.getUname(), "开始", room.getTitle(),
                                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                    part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname()));
                            // 设置消息接收者的UID
                            message.setUid(wxuid);
                            // 发送消息
                            WxPusher.send(message);

                            // 并发执行Runnable列表中的任务
                            runnableList.stream().parallel().forEach(Runnable::run);

                            // 如果尝试次数达到200次
                            if (tryCount.get() >= 200) {
                                // 根据partId获取part对象
                                part = partRepository.findById(part.getId()).get();
                                // 设置上传状态为false
                                part.setUpload(false);
                                // 上传重试次数加1
                                part.setUploadRetryCount(part.getUploadRetryCount() + 1);
                                // 保存part对象
                                part = partRepository.save(part);

                                // 如果上传重试次数小于2次
                                if (part.getUploadRetryCount() < 2) {
                                    // 等待5秒
                                    Thread.sleep(5000);
                                    // 异步上传part
                                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                                    // 输出日志，尝试重新上传
                                    log.info("尝试重新上传{}", filePath);
                                }

                                // 从任务中移除partId对应的上传任务
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());

                                // 如果wxuid不为空，pushMsgTags不为空且包含"分P上传"
                                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("分P上传")) {
                                    // 设置AppToken
                                    message.setAppToken(wxToken);
                                    // 设置消息类型为文本
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    // 设置消息内容，表示上传失败
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "开始", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), biliBiliUser.getUname() + "并发上传失败，存在异常"));
                                    // 设置消息接收者的UID
                                    message.setUid(wxuid);
                                    // 发送消息
                                    WxPusher.send(message);
                                }

                                // 抛出运行时异常，表示并发上传失败，存在异常
                                throw new RuntimeException("partId=" + part.getId() + "===并发上传失败，存在异常");
                            }


                            try {
                                    // 输出日志，显示上传完毕并等待十秒
                                    log.info("上传完毕等待十秒==>{}", JSON.toJSONString(part));
                                    // 线程休眠十秒
                                    Thread.sleep(10000L);
                                } catch (InterruptedException e) {
                                    // 捕获线程中断异常，并打印异常堆栈
                                    e.printStackTrace();
                                }


                            try {
                                // 打开上传文件的输入流
                                FileInputStream stream = new FileInputStream(uploadFile);

                                // 计算上传文件的MD5值，并转为小写
                                String md5 = DigestUtils.md5Hex(stream).toLowerCase();

                                // 关闭输入流
                                stream.close();

                                // 调用BiliApi的completeUpload方法完成上传，传入相关参数
                                BiliApi.completeUpload(complete, (int)chunkNum, fileSize, md5,
                                        uploadFile.getName(), "2.3.0.1088");

                                // 根据part的Id从数据库中获取part对象
                                part = partRepository.findById(part.getId()).get();

                                // 设置part的文件名
                                part.setFileName(filename);

                                // 设置part的上传状态为已上传
                                part.setUpload(true);

                                // 设置part的更新时间为当前时间
                                part.setUpdateTime(LocalDateTime.now());

                                // 保存更新后的part对象到数据库
                                part = partRepository.save(part);

                                //如果配置上传完成删除，则删除文件
                                if (room.getDeleteType() == 1) {
                                    // 删除文件
                                    boolean delete = uploadFile.delete();
                                    if (delete) {
                                        log.error("{}=>文件删除成功！！！", filePath);
                                    } else {
                                        log.error("{}=>文件删除失败！！！", filePath);
                                    }
                                } else if (StringUtils.isNotBlank(room.getMoveDir()) && room.getDeleteType() == 4) {
                                    // 获取文件名（不包含扩展名）
                                    String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                                    // 获取原文件所在目录路径
                                    String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                                    // 获取目标目录路径
                                    String toDirPath = room.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                                    // 创建目标目录
                                    File toDir = new File(toDirPath);
                                    if (!toDir.exists()) {
                                        toDir.mkdirs();
                                    }
                                    // 获取原文件所在目录的File对象
                                    File startDir = new File(startDirPath);
                                    // 查找原文件所在目录下所有以fileName开头的文件
                                    File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                                    if (files != null) {
                                        for (File file : files) {
                                            // 如果文件路径不是以workPath开头，则标记为已删除，并跳过后续操作
                                            if (!filePath.startsWith(workPath)) {
                                                part = partRepository.findById(part.getId()).get();
                                                part.setFileDelete(true);
                                                part = partRepository.save(part);
                                                continue;
                                            }
                                            try {
                                                // 将文件移动到目标目录
                                                Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                                        StandardCopyOption.REPLACE_EXISTING);
                                                log.error("{}=>文件移动成功！！！", file.getName());
                                                } catch (Exception e) {
                                                    log.error("{}=>文件移动失败！！！", file.getName());
                                                }
                                        }
                                    }
                                    // 更新part对象的文件路径和删除状态
                                    part = partRepository.findById(part.getId()).get();
                                    part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                                    part.setFileDelete(true);
                                    part = partRepository.save(part);
                                }

                                // 从任务中移除对应的partId
                                TaskUtil.partUploadTask.remove(part.getId());
                                // 输出日志，表示文件上传成功
                                log.info("partId={},文件上传成功==>{}", part.getId(), filePath);

                                // 如果wxuid不为空，pushMsgTags不为空且包含"分P上传"
                                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("分P上传")) {
                                    // 设置消息的AppToken
                                    message.setAppToken(wxToken);
                                    // 设置消息的内容类型为文本
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    // 设置消息的内容
                                    message.setContent(WX_MSG_FORMAT.formatted("上传成功", room.getUname(), "结束", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), "服务器文件名称\n" + part.getFileName()));
                                    // 设置消息的接收者UID
                                    message.setUid(wxuid);
                                    // 发送消息
                                    WxPusher.send(message);
                                }

                            } catch (Exception e) {

                                if (history.getUploadRetryCount() < 2) {
                                    // 如果上传重试次数小于2次
                                    Thread.sleep(5000);
                                    // 等待5秒
                                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                                    // 异步上传part
                                    log.info("尝试重新上传{}", filePath);
                                // 输出日志，表示尝试重新上传文件
                                }
                                //存在异常
                                TaskUtil.partUploadTask.remove(part.getId());
                                // 从任务中移除对应的partId
                                log.error("partId={},文件上传失败==>{}", part.getId(), filePath, e);
                                // 输出日志，表示文件上传失败，并显示partId和文件路径
                                if (StringUtils.isNotBlank(wxuid) && StringUtils.isNotBlank(pushMsgTags) && pushMsgTags.contains("分P上传")) {
                                    // 如果wxuid不为空，pushMsgTags不为空且包含"分P上传"
                                    message.setAppToken(wxToken);
                                    // 设置消息的AppToken
                                    message.setContentType(Message.CONTENT_TYPE_TEXT);
                                    // 设置消息的内容类型为文本
                                    message.setContent(WX_MSG_FORMAT.formatted("上传失败", room.getUname(), "结束", room.getTitle(),
                                            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")),
                                            part.getFilePath(), part.getStartTime().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分ss秒")), (int)part.getDuration() / 60, ((float)part.getFileSize() / 1024 / 1024 / 1024), e.getMessage()));
                                    // 设置消息的内容
                                    message.setUid(wxuid);
                                    // 设置消息的接收者UID
                                    WxPusher.send(message);
                                    // 发送消息
                                }
                            }
                        }
                    } else {
                        // 分片上传事件，文件不需要上传
                        log.info("分片上传事件，文件不需要上传 ==>{}", JSON.toJSONString(part));

                        // 从任务列表中移除对应的分片上传任务
                        TaskUtil.partUploadTask.remove(part.getId());

                        // 返回，结束当前执行流程
                        return;

                    }
                }

            }
        } catch (Exception e) {
            log.error("app上传发生错误", e);
        } finally {
            // 从任务列表中移除对应的分片上传任务
            TaskUtil.partUploadTask.remove(part.getId());
        }

    }
}
