package top.sshh.bililiverecoder.controller;


import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/history")
public class HistoryController {


    @Value("${record.work-path}")
    private String workPath;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordBiliPublishService publishService;
    @Autowired
    private LiveMsgRepository msgRepository;
    @Autowired
    private LiveMsgService msgService;
    @PersistenceContext
    private EntityManager entityManager;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody RecordHistoryDTO request) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        // 指定结果视图
        CriteriaQuery<RecordHistory> criteriaQuery = criteriaBuilder.createQuery(RecordHistory.class);
        // 查询基础表
        Root<RecordHistory> root = criteriaQuery.from(RecordHistory.class);
        criteriaQuery.select(root);

        // 构建查询条件
        //Predicate 过滤条件 构建where字句可能的各种条件
        //这里用List存放多种查询条件,实现动态查询
        List<Predicate> predicatesList = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getRoomId())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("roomId"), request.getRoomId())));
        }
        if (StringUtils.isNotBlank(request.getBvId())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.like(root.get("bvId"), "%" + request.getBvId() + "%")));
        }
        if (StringUtils.isNotBlank(request.getTitle())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.like(root.get("title"), "%" + request.getTitle() + "%")));
        }

        if (request.getRecording() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("recording"), request.getRecording())));
        }
        if (request.getUpload() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("upload"), request.getUpload())));
        }
        if (request.getPublish() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("publish"), request.getPublish())));
        }

        if (request.getFrom() != null && request.getTo() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.between(root.get("endTime"), request.getFrom(), request.getTo())));
        }

        // 如果有查询条件，则添加where子句
        //where()拼接查询条件
        if (predicatesList.size() > 0) {
            criteriaQuery.where(predicatesList.toArray(new Predicate[predicatesList.size()]));
        }

        // 设置排序方式
        criteriaQuery.orderBy(criteriaBuilder.desc(root.get("endTime")));

        // 创建TypedQuery并执行查询
        TypedQuery<RecordHistory> typedQuery = entityManager.createQuery(criteriaQuery);
        int total = typedQuery.getResultList().size();

        // 设置分页参数
        typedQuery.setFirstResult((request.getCurrent() - 1) * request.getPageSize());
        typedQuery.setMaxResults(request.getPageSize());

        // 获取查询结果
        List<RecordHistory> list = typedQuery.getResultList();

        // 创建房间缓存
        Map<String, String> roomCache = new HashMap<>();

        // 初始化Runnable列表
        List<Runnable> runnables = new ArrayList<>();

        // 查询所有房间信息
        Iterable<RecordRoom> iterable = roomRepository.findAll();
        for (RecordRoom recordRoom : iterable) {
            roomCache.put(recordRoom.getRoomId(), recordRoom.getUname());
        }
        for (RecordHistory history : list) {
            // 设置房间名称
            history.setRoomName(roomCache.get(history.getRoomId()));

            // 创建一个Runnable对象，用于设置部件数量
            Runnable run;
            run = () -> history.setPartCount(partRepository.countByHistoryId(history.getId()));
            runnables.add(run);

            // 创建一个Runnable对象，用于设置部件时长总和
            run = () -> history.setPartDuration(partRepository.sumHistoryDurationByHistoryId(history.getId()));
            runnables.add(run);

            // 创建一个Runnable对象，用于设置已上传部件数量
            run = () -> history.setUploadPartCount(partRepository.countByHistoryIdAndFileNameNotNull(history.getId()));
            runnables.add(run);

            // 创建一个Runnable对象，用于设置已录制部件数量
            run = () -> history.setRecordPartCount(partRepository.countByHistoryIdAndRecordingIsTrue(history.getId()));
            runnables.add(run);

            // 重复设置部件数量，可能是代码冗余或逻辑错误
            run = () -> history.setPartCount(partRepository.countByHistoryId(history.getId()));
            runnables.add(run);

            // 如果history的bvId不为空，则继续执行以下逻辑
            if (StringUtils.isNotBlank(history.getBvId())) {
                // 创建一个Runnable对象，用于设置消息数量
                run = () -> history.setMsgCount(msgRepository.countByBvid(history.getBvId()));
                runnables.add(run);

                // 创建一个Runnable对象，用于设置成功消息数量
                run = () -> history.setSuccessMsgCount(msgRepository.countByBvidAndCode(history.getBvId(), 0));
                runnables.add(run);
            }
        }

        // 并行执行所有Runnable对象
        runnables.stream().parallel().forEach(Runnable::run);

        // 创建一个结果Map
        Map<String,Object> result = new HashMap<>();

        // 将list放入结果Map的"data"键中
        result.put("data",list);

        // 将total放入结果Map的"total"键中
        result.put("total",total);

        // 返回结果Map
        return result;
    }


    @PostMapping("/update")
    public Map<String, String> update(@RequestBody RecordHistory history) {
        Optional<RecordHistory> historyOptional = historyRepository.findById(history.getId());
        Map<String, String> result = new HashMap<>();
        if (historyOptional.isPresent()) {
            RecordHistory dbHistory = historyOptional.get();
            dbHistory.setRecording(history.isRecording());
            dbHistory.setUpload(history.isUpload());
            dbHistory.setUpdateTime(LocalDateTime.now());
            historyRepository.save(dbHistory);
            result.put("type", "info");
            result.put("msg", "更新成功");
        }
        return result;
    }

    @GetMapping("/delete/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();

        // 判断id是否为空
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }

        // 根据id查询录制历史
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);

        // 如果找到了录制历史
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();

            // 查询与录制历史相关的消息
            List<LiveMsg> liveMsgs = msgRepository.queryByBvid(history.getBvId());

            // 删除消息
            msgRepository.deleteAll(liveMsgs);

            // 查询与录制历史相关的录制部分
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());

            // 遍历录制部分
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();

                // 判断文件路径是否以工作路径开头
                if(! filePath.startsWith(workPath)){
                    part.setFileDelete(true);
                    part = partRepository.save(part);
                    continue;
                }

                // 截取文件路径中的目录部分
                String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);

                // 截取文件名
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));

                // 创建目录对象
                File startDir = new File(startDirPath);

                // 遍历目录中的文件
                File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));

                // 如果找到了文件
                if (files != null) {
                    for (File file : files) {
                        // 删除文件
                        file.delete();
                    }
                }

                // 标记文件已删除
                part.setFileDelete(true);

                // 保存录制部分
                partRepository.save(part);
            }

            // 删除录制部分
            partRepository.deleteAll(partList);

            // 删除录制历史
            historyRepository.delete(history);

            // 设置返回结果
            result.put("type", "success");
            result.put("msg", "录制历史删除成功");

            return result;
        } else {
            // 如果没有找到录制历史
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/deleteMsg/{id}")
    public Map<String, String> deleteMsg(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        // 判断id是否为空
        if (id == null) {
            // 如果id为空，则返回提示信息
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        // 根据id查询录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        // 如果录制历史记录存在
        if (historyOptional.isPresent()) {
            // 获取录制历史记录对象
            RecordHistory history = historyOptional.get();
            // 根据录制历史记录的bvId查询弹幕列表
            List<LiveMsg> liveMsgs = msgRepository.queryByBvid(history.getBvId());
            // 删除所有弹幕
            msgRepository.deleteAll(liveMsgs);
            // 返回成功信息
            result.put("type", "success");
            result.put("msg", "弹幕删除成功");
            return result;
        } else {
            // 如果录制历史记录不存在，则返回警告信息
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


    @GetMapping("/reloadMsg/{id}")
    public Map<String, String> reloadMsg(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        // 根据id查询录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            // 根据录制历史记录的id查询对应的分段列表，并按开始时间升序排序
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : parts) {
                // 获取文件路径
                String filePath = part.getFilePath();
                // 修改文件后缀为.xml
                filePath = filePath.substring(0, filePath.lastIndexOf(".")) + ".xml";
                // 创建文件对象
                File file = new File(filePath);
                if (file.exists()) {
                    // 根据分段对应的cid查询弹幕列表
                    List<LiveMsg> liveMsgs = msgRepository.queryByCid(part.getCid());
                    // 删除所有弹幕
                    msgRepository.deleteAll(liveMsgs);
                    // 处理分段
                    msgService.processing(part);
                }
            }
            // 返回成功信息
            result.put("type", "success");
            result.put("msg", "弹幕重新加载成功");
            return result;
        } else {
            // 返回警告信息
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


    @GetMapping("/updatePartStatus/{id}")
    public Map<String, String> updatePartStatus(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }

        // 查找录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();

            // 查找该录制历史记录下的所有分段
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());

            // 遍历所有分段，设置recording为false，并保存更新
            for (RecordHistoryPart part : partList) {
                part.setRecording(false);
                partRepository.save(part);
            }

            // 设置录制历史记录的recording为false，并保存更新
            history.setRecording(false);
            historyRepository.save(history);

            // 设置返回结果为成功，并添加成功消息
            result.put("type", "success");
            result.put("msg", "状态更新成功");
            return result;
        } else {
            // 录制历史记录不存在，设置返回结果为警告，并添加警告消息
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


    @GetMapping("/updatePublishStatus/{id}")
    public Map<String, String> updatePublishStatus(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        // 根据id查询录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            // 更新录制历史记录的起始时间，增加1分钟
            history.setStartTime(history.getStartTime().plusMinutes(1L));
            // 更新录制历史记录的发布状态为未发布
            history.setPublish(false);
            // 更新录制历史记录的bvId为null
            history.setBvId(null);
            // 更新录制历史记录的code为-1
            history.setCode(-1);
            // 保存更新后的录制历史记录
            historyRepository.save(history);
            // 根据录制历史记录的id查询对应的分段列表，并按起始时间升序排序
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                // 更新分段的上传状态为未上传
                part.setUpload(false);
                // 保存更新后的分段
                partRepository.save(part);
            }
            result.put("type", "success");
            result.put("msg", "状态更新成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


    @GetMapping("/touchPublish/{id}")
    public Map<String, String> touchPublish(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();

        // 如果id为空
        if (id == null) {
            // 设置返回结果类型为info，消息为“请输入id”
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }

        // 根据id查询录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);

        // 如果录制历史记录存在
        if (historyOptional.isPresent()) {
            // 获取录制历史记录对象
            RecordHistory history = historyOptional.get();

            // 将上传重试次数设置为0
            history.setUploadRetryCount(0);

            // 保存更新后的录制历史记录
            history = historyRepository.save(history);

            // 异步触发发布事件
            publishService.asyncPublishRecordHistory(history);

            // 设置返回结果类型为success，消息为“触发发布事件成功”
            result.put("type", "success");
            result.put("msg", "触发发布事件成功");
            return result;
        } else {
            // 设置返回结果类型为warning，消息为“录制历史不存在”
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


    @GetMapping("/rePublish/{id}")
    public Map<String, String> rePublish(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();

        // 如果id为空
        if (id == null) {
            // 设置返回结果为“请输入id”的提示信息
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }

        // 根据id查找录制历史记录
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);

        // 如果录制历史记录存在
        if (historyOptional.isPresent()) {
            // 获取录制历史记录对象
            RecordHistory history = historyOptional.get();

            // 异步触发转码修复事件
            publishService.asyncRepublishRecordHistory(history);

            // 设置返回结果为“触发转码修复事件成功”的提示信息
            result.put("type", "success");
            result.put("msg", "触发转码修复事件成功");
            return result;
        } else {
            // 设置返回结果为“录制历史不存在”的警告信息
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

}
