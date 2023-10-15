package top.sshh.bililiverecoder.util.bili.upload;

import com.alibaba.fastjson.JSONObject;
import org.apache.http.HttpException;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.HttpClientResult;
import top.sshh.bililiverecoder.util.bili.HttpClientUtils;
import top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean;
import top.sshh.bililiverecoder.util.bili.upload.pojo.EditorPreUploadBean;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author mwxmmy
 */
public class EdtiorCompleteUploadRequest {

    private final String URL = "https://api.bilibili.com/studio/video-editor-interface/video-editor/resource/create/complete";

    private Cookie cookie;
    private HashMap<String, String> headers = new HashMap<String, String>();
    private EditorPreUploadBean preUploadBean;

    private Map<String, String> params;

    public EdtiorCompleteUploadRequest(Cookie cookie, EditorPreUploadBean preUploadBean, Map<String, String> params) {
        this.preUploadBean = preUploadBean;
        this.cookie = cookie;
        cookie.toHeaderCookie(headers);
        this.params = params;
        this.params.put("resource_id", preUploadBean.getData().getResource_id());
        this.params.put("upload_id", preUploadBean.getData().getUpload_id());
        this.params.put("csrf", cookie.getCsrf());
    }


    public CompleteUploadBean getPojo() throws HttpException {
        String page = null;
        try {
            page = getPage();
        } catch (Exception e) {
            throw new HttpException("访问URL失败", e);
        }
        if (page == null) {
            return null;
        }
        CompleteUploadBean bean = JSONObject.parseObject(page, CompleteUploadBean.class);
        return bean;
    }

    /**
     * 获取预上传的信息
     *
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws URISyntaxException
     * @throws KeyManagementException
     */
    public String getPage() throws IOException, NoSuchAlgorithmException, KeyStoreException, URISyntaxException, KeyManagementException {
        HttpClientResult result = HttpClientUtils.doPost(URL, headers, params, null, 300 * 1000);
        return result.getContent();
    }

}
