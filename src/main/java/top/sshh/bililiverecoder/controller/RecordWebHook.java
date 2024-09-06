package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BlrecData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.RecordEventFactory;

@Slf4j
@RestController
@RequestMapping("/recordWebHook")
public class RecordWebHook {

    @Autowired
    private RecordEventFactory recordEventFactory;

    @PostMapping
    public void processing(@RequestBody RecordEventDTO recordEvent) {
        String lock = "";
        if (recordEvent.getData() != null) {
            BlrecData data = recordEvent.getData();
            if (data.getRoomInfo() != null) {
                // 如果数据中包含房间信息，则使用房间ID生成锁
                lock = "blrec:" + data.getRoomInfo().getRoomId();
            } else {
                // 如果数据中没有房间信息，则使用房间ID生成锁
                lock = "blrec:" + data.getRoomId();
            }
        } else if (recordEvent.getEventData() != null) {
            try {
                if ("SessionEnded".equals(recordEvent.getEventType())) {
                    // 如果是录制结束事件，则延迟处理以防止直播结束先于录制结束事件处理
                    // 录制结束事件要单独线程进行延迟处理，防止直播结束先于录制结束事件处理
                    Thread.sleep(10000L);
                    lock = "brec:" + recordEvent.getEventType();
                } else if ("FileClosed".equals(recordEvent.getEventType())) {
                    // 如果是文件关闭事件，则延迟处理以防止下一p文件打开先于文件关闭事件处理
                    // 录制结束事件要单独线程进行延迟处理，防止下一p文件打开先于文件关闭事件处理
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else if ("FileOpening".equals(recordEvent.getEventType())) {
                    // 如果是文件打开事件，则延迟处理以防止下一p文件打开先于文件关闭事件处理
                    // 录制结束事件要单独线程进行延迟处理，防止下一p文件打开先于文件关闭事件处理
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else {
                    // 其他事件类型，则使用会话ID生成锁
                    lock = "brec:" + recordEvent.getEventData().getSessionId();
                }
            } catch (Exception e) {
                // 捕获异常，但不处理
            }
        }
        // 使用锁进行同步处理
        synchronized (lock.intern()) {
            // 打印日志信息
            log.info("收到录播姬的推送信息==> {}", JSON.toJSONString(recordEvent));
            // 处理推送的记录事件
            recordEventFactory.processing(recordEvent);
        }
    }


    @GetMapping
    public String processing() {
        return "这里是录播姬推送的接口地址，把当前地址复制到录播姬WebHookV2里即可(前提是录播姬网络环境也可访问)";
    }
}
