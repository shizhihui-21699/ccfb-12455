package com.consentledger.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台可调参数。默认值保证开箱即用，生产环境应通过环境变量覆盖密钥与账号。
 */
@ConfigurationProperties(prefix = "consent")
public class ConsentProperties {

    private Duration decisionCacheTtl = Duration.ofSeconds(60);
    private long decisionCacheMaxSize = 200_000;
    private Duration revisionPollInterval = Duration.ofMillis(250);
    private String receiptSigningSecret = "dev-only-change-me";
    private DemoData demoData = new DemoData();
    private Admin admin = new Admin();
    private List<ApiClientSeed> apiClients = new ArrayList<>();

    public Duration getDecisionCacheTtl() {
        return decisionCacheTtl;
    }

    public void setDecisionCacheTtl(Duration decisionCacheTtl) {
        this.decisionCacheTtl = decisionCacheTtl;
    }

    public long getDecisionCacheMaxSize() {
        return decisionCacheMaxSize;
    }

    public void setDecisionCacheMaxSize(long decisionCacheMaxSize) {
        this.decisionCacheMaxSize = decisionCacheMaxSize;
    }

    public Duration getRevisionPollInterval() {
        return revisionPollInterval;
    }

    public void setRevisionPollInterval(Duration revisionPollInterval) {
        this.revisionPollInterval = revisionPollInterval;
    }

    public String getReceiptSigningSecret() {
        return receiptSigningSecret;
    }

    public void setReceiptSigningSecret(String receiptSigningSecret) {
        this.receiptSigningSecret = receiptSigningSecret;
    }

    public DemoData getDemoData() {
        return demoData;
    }

    public void setDemoData(DemoData demoData) {
        this.demoData = demoData;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public List<ApiClientSeed> getApiClients() {
        return apiClients;
    }

    public void setApiClients(List<ApiClientSeed> apiClients) {
        this.apiClients = apiClients;
    }

    public static class DemoData {
        private boolean enabled = true;
        private int subjects = 2000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSubjects() {
            return subjects;
        }

        public void setSubjects(int subjects) {
            this.subjects = subjects;
        }
    }

    public static class Admin {
        private String bootstrapUsername = "admin";
        private String bootstrapPassword = "admin123!";

        public String getBootstrapUsername() {
            return bootstrapUsername;
        }

        public void setBootstrapUsername(String bootstrapUsername) {
            this.bootstrapUsername = bootstrapUsername;
        }

        public String getBootstrapPassword() {
            return bootstrapPassword;
        }

        public void setBootstrapPassword(String bootstrapPassword) {
            this.bootstrapPassword = bootstrapPassword;
        }
    }

    public static class ApiClientSeed {
        private String name;
        private String key;
        private String purposes = "*";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getPurposes() {
            return purposes;
        }

        public void setPurposes(String purposes) {
            this.purposes = purposes;
        }
    }
}
