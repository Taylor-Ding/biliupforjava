package top.sshh.bililiverecoder.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JdbcService {

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public <T> void saveLiveMsgList(List<T> list) {
        // 拼接SQL语句，注意VALUES要用实体的变量，而不是字段的Column值
        // 这里注意VALUES要用实体的变量，而不是字段的Column值
        String sql = "INSERT INTO live_msg(bvid, cid, code, context, is_send, part_id, send_time, color, fontsize, mode, pool) " +
                "VALUES (:bvid, :cid,  :code, :context, false, :partId, :sendTime, :color, :fontsize, :mode, :pool)";
        // 调用updateBatchCore方法执行批量更新操作
        updateBatchCore(sql, list);
    }


    /**
     * 一定要在jdbc url 加&rewriteBatchedStatements=true才能生效
     *
     * @param sql  自定义sql语句，类似于 "INSERT INTO chen_user(name,age) VALUES (:name,:age)"
     * @param list
     * @param <T>
     */
    public <T> void updateBatchCore(String sql, List<T> list) {
        // 将列表转换为数组，并创建SqlParameterSource数组
        SqlParameterSource[] beanSources = SqlParameterSourceUtils.createBatch(list.toArray());
        // 使用命名参数JdbcTemplate执行批量更新操作
        namedParameterJdbcTemplate.batchUpdate(sql, beanSources);
    }


}
