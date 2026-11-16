package com.consentledger.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 把当前登录管理员暴露给所有视图（Thymeleaf 3.1 不再提供 #authentication）。
 */
@ControllerAdvice(assignableTypes = AdminController.class)
public class AdminModelAdvice {

    @ModelAttribute("currentAdmin")
    public String currentAdmin(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }
}
