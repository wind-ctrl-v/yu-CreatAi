package com.yu.yuaifather.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.yu.yuaifather.model.dto.app.AppAddRequest;
import com.yu.yuaifather.model.dto.app.AppQueryRequest;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.vo.AppVO;
import com.yu.yuaifather.service.generation.CodeGenStream;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author yu
 */

public interface AppService extends IService<App> {

    /**
     * 获取应用视图。
     *
     * @param app 应用
     * @return 应用视图
     */
    AppVO getAppVO(App app);

    List<AppVO> getAppVOList(List<App> records);

    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    //调用ai大模型接口。内容流与 token 用量流分开，用量不进入代码产物。
    CodeGenStream chatToGenCode(Long appId, String message, String requestedMode, User loginUser);

    //部署应用
    String deployApp(Long appId, User loginUser);

    //创建应用
    Long createApp(AppAddRequest appAddRequest, User loginUser);
}
