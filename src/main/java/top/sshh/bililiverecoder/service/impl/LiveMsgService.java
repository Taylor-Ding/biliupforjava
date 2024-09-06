package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.dom4j.tree.DefaultElement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliDmResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Transactional
@Component
public class LiveMsgService {


    @Autowired
    private JdbcService jdbcService;

    @Autowired
    private LiveMsgRepository liveMsgRepository;
    @Autowired
    private RecordHistoryRepository recordHistoryRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    public int sendMsg(BiliBiliUser user, LiveMsg liveMsg) {
        // 调用BiliApi的sendVideoDm方法发送弹幕，并获取返回结果
        BiliDmResponse response = BiliApi.sendVideoDm(user, liveMsg);
        // 获取返回结果中的状态码
        int code = response.getCode();
        if (code != 0) {
            // 如果状态码不为0，则表示发送弹幕出错
            // 记录错误日志，包含用户名和状态码
            log.error("{}发送弹幕错误，code==>{}", user.getUname(), code);

            // 如果状态码为36701、36702或36714
            if (code == 36701 || code == 36702 || code == 36714) {
                // 从数据库中删除该条弹幕
                liveMsgRepository.delete(liveMsg);
            }

            // 如果状态码为36704
            if(code == 36704){
                // 获取弹幕中的bvid
                String bvid = liveMsg.getBvid();
                // 调用syncVideoState方法同步视频状态
                this.syncVideoState(bvid);
                // 返回状态码
                return code;
            }
        }

        // 将状态码设置到弹幕对象中
        liveMsg.setCode(code);
        // 保存更新后的弹幕到数据库中
        liveMsgRepository.save(liveMsg);
        // 返回状态码
        return code;
    }

    public static boolean checkUtf8Size(String testStr) {
        // 遍历字符串中的每个字符
        for (int i = 0; i < testStr.length(); i++) {
            // 获取当前字符的Unicode编码值
            int c = testStr.codePointAt(i);
            // 如果字符的Unicode编码值小于0x0000或大于0xffff，则不符合UTF-8编码规范
            if (c < 0x0000 || c > 0xffff) {
                // 返回true，表示字符串中存在不符合UTF-8编码规范的字符
                return true;
            }
        }
        // 如果遍历完所有字符都没有发现不符合UTF-8编码规范的字符，则返回false
        return false;
    }


    public void processing(RecordHistoryPart part) {
        // 根据历史ID查询记录历史
        Optional<RecordHistory> historyOptional = recordHistoryRepository.findById(part.getHistoryId());
        String bvid = "";
        if (historyOptional.isPresent()) {
            // 如果记录历史存在
            RecordHistory history = historyOptional.get();
            // 获取记录历史的BVID
            bvid = history.getBvId();
        }
        // 如果BVID为空或CID为空或CID小于1，则直接返回
        if (StringUtils.isBlank(bvid) || part.getCid() == null || part.getCid() < 1) {
            return;
        }
        // 根据房间ID查询记录房间
        RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
        // 获取弹幕关键词黑名单
        String dmKeywordBlacklist = room.getDmKeywordBlacklist();
        String[] EXCLUSION_DM;
        // 如果弹幕关键词黑名单不为空
        if (StringUtils.isNotBlank(dmKeywordBlacklist)) {
            // 将黑名单按换行符分割为数组
            EXCLUSION_DM = dmKeywordBlacklist.split("\n");
        } else {
            // 如果黑名单为空，则数组为空数组
            EXCLUSION_DM = new String[0];
        }
        // 获取文件路径
        String filePath = part.getFilePath();
        // 修改文件路径为XML格式
        filePath = filePath.substring(0, filePath.lastIndexOf(".")) + ".xml";
        // 创建文件对象
        File file = new File(filePath);
        // 判断文件是否存在
        boolean exists = file.exists();

        if (exists) {
            FileInputStream stream = null;
            try {
                stream = new FileInputStream(file);

                // 2. 创建org.dom4j.io包中的SAXReader对象
                //2.创建org.dom4j.io包中的SAXReader对象
                SAXReader saxReader = new SAXReader();

                Document document = null;

                document = saxReader.read(stream);

                // 4. 拿到根元素
                //4.拿到根元素
                Element rootElement = document.getRootElement();

                List<LiveMsg> liveMsgs = new ArrayList<>();

                // sc弹幕处理
                List<Node> scNodes = rootElement.selectNodes("/i/sc");
                for (Node node : scNodes) {
                    DefaultElement element = (DefaultElement) node;
                    String time = element.attribute("ts").getValue();
                    long sendTime = (long) (Float.parseFloat(time) * 1000);
                    String userName = element.attribute("user").getValue();
                    String price = element.attribute("price").getValue();

                    // 如果会话ID为"blrec"，则弹幕的金额除以1000
                    //  blrec 弹幕的金额/1000
                    if ("blrec".equals(part.getSessionId())) {
                        price = String.valueOf(Integer.parseInt(price) / 1000);
                    }

                    String text = element.getText();
                    LiveMsg msg = new LiveMsg();
                    msg.setPartId(part.getId());
                    msg.setBvid(bvid);
                    msg.setCid(part.getCid());
                    msg.setSendTime(sendTime);
                    msg.setMode(5);
                    msg.setPool(1);
                    msg.setFontsize(64);
                    msg.setColor(16776960);

                    StringBuilder builder = new StringBuilder();
                    builder.append(userName).append("发送了").append(price).append("元留言：").append(text);

                    // 如果拼接后的文本长度超过100，则截取前99个字符
                    if (builder.length() > 100) {
                        text = builder.substring(0, 99);
                    } else {
                        text = builder.toString();
                    }

                    msg.setContext(text);
                    liveMsgs.add(msg);
                }


                // sc弹幕处理
                // 获取所有守卫节点
                List<Node> guardNodes = rootElement.selectNodes("/i/guard");
                for (Node node : guardNodes) {
                    // 转换节点为DefaultElement对象
                    DefaultElement element = (DefaultElement) node;

                    // 获取时间属性
                    String time = element.attribute("ts").getValue();

                    // 将时间转换为毫秒
                    long sendTime = (long) (Float.parseFloat(time) * 1000);

                    // 获取用户名
                    String userName = element.attribute("user").getValue();

                    // 获取等级
                    String level = element.attribute("level").getValue();

                    // 获取开通时长
                    String count = element.attribute("count").getValue();

                    // 创建LiveMsg对象
                    LiveMsg msg = new LiveMsg();

                    // 设置消息的属性
                    msg.setPartId(part.getId());
                    msg.setBvid(bvid);
                    msg.setCid(part.getCid());
                    msg.setSendTime(sendTime);
                    msg.setMode(5);
                    msg.setPool(1);
                    msg.setColor(16776960);

                    // 创建一个字符串构建器，用于构建文本消息
                    StringBuilder builder = new StringBuilder();
                    builder.append(userName).append("开通了");

                    // 根据开通时长构建文本消息
                    if (Integer.parseInt(count) > 1) {
                        builder.append(count).append("个月");
                    }

                    // 根据等级设置字体大小并构建文本消息
                    if ("1".equals(level)) {
                        msg.setFontsize(64);
                        builder.append("19998/月的总督");
                    } else if ("2".equals(level)) {
                        msg.setFontsize(64);
                        builder.append("1998/月的提督");
                    } else if ("3".equals(level)) {
                        msg.setFontsize(64);
                        builder.append("舰长");
                    } else {
                        builder.append("舰长");
                    }

                    // 截取或获取文本消息
                    String text;
                    if (builder.length() > 100) {
                        text = builder.substring(0, 99);
                    } else {
                        text = builder.toString();
                    }

                    // 设置文本消息
                    msg.setContext(text);

                    // 将消息添加到消息列表中
                    liveMsgs.add(msg);
                }

                // 普通弹幕处理
                // 获取所有节点
                List<Node> nodes = rootElement.selectNodes("/i/d");
                // 创建布隆过滤器
                BloomFilter<CharSequence> bloomFilter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1000000, 0.01);
                for (Node node : nodes) {
                    DefaultElement element = (DefaultElement) node;

                    // 获取文本内容并处理
                    String text = element.getText().trim().replace("\n", ",").replace("\r", ",").toLowerCase();
                    text = StringUtils.deleteWhitespace(text);

                    // 过滤UTF-8字符大小为4的文本
                    //过滤utf8字符大小为4的
                    if (checkUtf8Size(text)) {
                        continue;
                    }

                    // 排除垃圾弹幕
                    //排除垃圾弹幕
                    boolean isContinue = false;
                    for (String s : EXCLUSION_DM) {
                        if (text.contains(s.toLowerCase())) {
                            isContinue = true;
                            break;
                        }
                    }
                    if (isContinue) {
                        continue;
                    }

                    if (element.attribute("raw") != null) {
                        String raw = element.attribute("raw").getValue();
                        JSONArray array = JSON.parseArray(raw);

                        // 判断是否抽奖弹幕
                        // 判断是否抽奖弹幕
                        boolean lottery = (Integer)((JSONArray)array.get(0)).get(9) != 0;
                        if(lottery){
                            continue;
                        }

                        JSONArray dmFanMedalObjects = (JSONArray) array.get(3);

                        // 根据房间设置处理粉丝勋章
                        // 0-不做处理，1-必须佩戴粉丝勋章。2-必须佩戴主播的粉丝勋章
                        // 0-不做处理，1-必须佩戴粉丝勋章。2-必须佩戴主播的粉丝勋章
                        if (room.getDmFanMedal() == 1) {
                            if (dmFanMedalObjects.size() == 0) {
                                continue;
                            }
                        } else if (room.getDmFanMedal() == 2) {
                            if (dmFanMedalObjects.size() == 0) {
                                continue;
                            }
                            String roomId = dmFanMedalObjects.get(3).toString();
                            if (!part.getRoomId().equals(roomId)) {
                                continue;
                            }
                        }

                        Integer ulLive = (Integer) ((JSONArray) array.get(4)).get(0);

                        // 排除低级用户
                        //排除低级用户
                        if (ulLive < room.getDmUlLevel()) {
                            if (dmFanMedalObjects.size() == 0) {
                                continue;
                            }
                        }
                    }

                    String value = element.attribute("p").getValue();
                    String[] values = value.split(",");
                    long sendTime = (long) (Float.parseFloat(values[0]) * 1000) - 10000L;

                    // 过滤发送时间小于0的弹幕
                    if (sendTime < 0) {
                        continue;
                    }

                    int fontsize = Integer.parseInt(values[2]);
                    int color = Integer.parseInt(values[3]);

                    // 弹幕重复过滤
                    //弹幕重复过滤
                    if (room.getDmDistinct() != null && room.getDmDistinct()) {
                        if (!bloomFilter.put(text)) {
                            continue;
                        }
                    }

                    LiveMsg msg = new LiveMsg();
                    msg.setPartId(part.getId());
                    msg.setBvid(bvid);
                    msg.setCid(part.getCid());
                    msg.setSendTime(sendTime);
                    msg.setFontsize(fontsize);
                    msg.setMode(1);
                    msg.setPool(0);
                    msg.setColor(color);
                    msg.setContext(text);
                    liveMsgs.add(msg);
                    // 如果弹幕列表的大小超过500条
                    if (liveMsgs.size() > 500) {
                        // 调用jdbcService的saveLiveMsgList方法，将弹幕列表保存到数据库中
                        jdbcService.saveLiveMsgList(liveMsgs);
                        // 输出日志，显示弹幕解析入库成功，并打印入库的弹幕条数
                        log.info("{} 弹幕解析入库成功，一共入库{}条。", filePath, liveMsgs.size());
                        // 清空弹幕列表
                        liveMsgs.clear();
                    }
                }
                // 将剩余的弹幕列表保存到数据库中
                jdbcService.saveLiveMsgList(liveMsgs);
                // 输出日志，显示弹幕解析入库成功，并打印入库的弹幕条数
                log.info("{} 弹幕解析入库成功，一共入库{}条。", filePath, liveMsgs.size());
            } catch (Exception e) {
                // 输出日志，显示弹幕解析入库失败，并打印异常信息
                log.info("{} 弹幕解析入库失败", filePath, e);
                e.printStackTrace();
            } finally {
                try {
                    // 关闭文件输入流
                    stream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    
    public void syncVideoState(String bvid) {
        // 根据bvid查询记录历史
        RecordHistory history = recordHistoryRepository.findByBvId(bvid);
        // 调用BiliApi获取视频信息
        BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(history.getBvId());
        // 获取返回结果的code
        int code = videoInfoResponse.getCode();
        // 获取返回结果中的视频信息数据
        BiliVideoInfoResponse.BiliVideoInfo videoInfoResponseData = videoInfoResponse.getData();

        // 如果code不为0或者视频状态不为0
        if (code != 0 || videoInfoResponseData.getState() != 0) {
            // 设置记录历史的code为-1
            history.setCode(-1);
            // 保存更新后的记录历史
            history = recordHistoryRepository.save(history);
        }
    }

}
