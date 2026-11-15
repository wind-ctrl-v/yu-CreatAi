package com.yu.yuaifather.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 实体类。
 *
 * @author yu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;



    /**
     * 账号
     */
    @Column("userAccount")
    private String userAccount;

    /**
     * 密码哈希。只允许从请求写入；任何 JSON 响应都不得输出。
     */
    @Column("userPassword")
    private String userPassword;

    /**
     * 序列化时忽略口令哈希。反序列化仍接受该字段，管理端保存密码不受影响。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }

    /**
     * 从即将返回给客户端的对象上移除口令哈希。登录校验必须使用尚未调用本方法的实体。
     */
    public User eraseCredentials() {
        this.userPassword = null;
        return this;
    }

    /**
     * 用户昵称
     */
    @Column("userName")
    private String userName;

    /**
     * 用户头像
     */
    @Column("userAvatar")
    private String userAvatar;

    /**
     * 用户简介
     */
    @Column("userProfile")
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    @Column("userRole")
    private String userRole;

    /**
     * 套餐类型（FREE/PRO/PREMIUM/ENTERPRISE/ADMIN），默认 FREE。
     * 由付费/充值流程更新，管理员后台也可手动调整。
     */
    @Column("planType")
    private String planType;

    /**
     * 编辑时间
     */
    @Column("editTime")
    private LocalDateTime editTime;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
