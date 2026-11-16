package com.consentledger.domain;

public enum PolicyVersionStatus {
    /** 草稿，仅管理端可见，不可作为同意依据 */
    DRAFT,
    /** 当前生效版本 */
    ACTIVE,
    /** 已被新版本取代，历史同意记录仍指向它 */
    SUPERSEDED,
    /** 已退役，不再作为依据 */
    RETIRED
}
