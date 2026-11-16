package com.consentledger.domain;

/**
 * 处理个人信息的合法性基础。只有 CONSENT 需要用户勾选同意；
 * 其余基础由平台登记并作为决策依据返回，监管核查时可解释"为何无需同意"。
 */
public enum LegalBasis {
    /** 用户同意（可撤回） */
    CONSENT,
    /** 履行合同所必需 */
    CONTRACT,
    /** 履行法定义务 */
    LEGAL_OBLIGATION,
    /** 维护公共利益/紧急情况等法定情形 */
    PUBLIC_INTEREST,
    /** 已公开信息或法律法规规定的其他情形 */
    OTHER_LEGAL
}
