package com.consentledger.seed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.consentledger.config.ConsentProperties;
import com.consentledger.domain.AdminUser;
import com.consentledger.domain.ApiClient;
import com.consentledger.domain.LegalBasis;
import com.consentledger.domain.Policy;
import com.consentledger.domain.PolicyVersion;
import com.consentledger.repo.AdminUserRepository;
import com.consentledger.repo.ApiClientRepository;
import com.consentledger.repo.ConsentEventRepository;
import com.consentledger.repo.ConsentSubjectRepository;
import com.consentledger.repo.PolicyRepository;
import com.consentledger.service.ConsentService;
import com.consentledger.service.Hashing;
import com.consentledger.service.PolicyService;
import com.consentledger.service.PolicyService.CreateVersionRequest;
import com.consentledger.service.PolicyService.CreatePolicyRequest;
import com.consentledger.service.PolicyService.PurposeDraft;

/**
 * 演示数据：三版隐私政策（含一次实质性变更）、一个敏感信息专项政策、
 * 若干用户的历史同意/撤回，以及一个演示业务系统密钥。
 *
 * <p>种子可复现（固定随机数种子），并且只在数据库为空时执行。
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final ConsentProperties properties;
    private final PolicyRepository policyRepository;
    private final PolicyService policyService;
    private final ConsentService consentService;
    private final ConsentSubjectRepository subjectRepository;
    private final ConsentEventRepository eventRepository;
    private final AdminUserRepository adminUserRepository;
    private final ApiClientRepository apiClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final Hashing hashing;

    public DemoDataSeeder(ConsentProperties properties,
                          PolicyRepository policyRepository,
                          PolicyService policyService,
                          ConsentService consentService,
                          ConsentSubjectRepository subjectRepository,
                          ConsentEventRepository eventRepository,
                          AdminUserRepository adminUserRepository,
                          ApiClientRepository apiClientRepository,
                          PasswordEncoder passwordEncoder,
                          Hashing hashing) {
        this.properties = properties;
        this.policyRepository = policyRepository;
        this.policyService = policyService;
        this.consentService = consentService;
        this.subjectRepository = subjectRepository;
        this.eventRepository = eventRepository;
        this.adminUserRepository = adminUserRepository;
        this.apiClientRepository = apiClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.hashing = hashing;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAdmin();
        bootstrapApiClients();
        if (properties.getDemoData().isEnabled() && policyRepository.count() == 0) {
            seedDemo();
        }
    }

    private void bootstrapAdmin() {
        String username = properties.getAdmin().getBootstrapUsername();
        if (adminUserRepository.findByUsername(username).isPresent()) {
            return;
        }
        AdminUser admin = new AdminUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(properties.getAdmin().getBootstrapPassword()));
        admin.setDisplayName("平台管理员");
        admin.setRole("ADMIN");
        adminUserRepository.save(admin);
        log.info("Bootstrapped admin user '{}' (please change the default password)", username);
    }

    private void bootstrapApiClients() {
        for (ConsentProperties.ApiClientSeed seed : properties.getApiClients()) {
            if (seed.getKey() == null || seed.getKey().isBlank() || apiClientRepository.existsByName(seed.getName())) {
                continue;
            }
            ApiClient client = new ApiClient();
            client.setName(seed.getName());
            client.setKeyHash(hashing.apiKeyHash(seed.getKey()));
            client.setKeyPrefix(seed.getKey().substring(0, Math.min(8, seed.getKey().length())));
            client.setPurposes(seed.getPurposes() == null ? "*" : seed.getPurposes());
            apiClientRepository.save(client);
            log.info("Bootstrapped API client '{}' (key prefix {})", seed.getName(), client.getKeyPrefix());
        }
    }

    // ---------------------------------------------------------------------

    private void seedDemo() {
        Instant now = Instant.now();
        long t0 = System.nanoTime();

        Policy privacy = policyService.createPolicy(new CreatePolicyRequest(
                "privacy-policy",
                "隐私政策",
                "示例科技有限公司面向全部产品线的通用隐私政策。",
                "示例科技有限公司"), "seed");

        Policy biometric = policyService.createPolicy(new CreatePolicyRequest(
                "biometric-policy",
                "人脸识别个人信息处理规则",
                "针对人脸特征等敏感个人信息的专项处理规则，需单独同意。",
                "示例科技有限公司"), "seed");

        // v1.0 —— 2025 年上线，已被取代
        PolicyVersion v10 = policyService.createVersion(privacy.getId(), new CreateVersionRequest(
                "v1.0", now.minus(Duration.ofDays(400)), "初始版本",
                false,
                "# 隐私政策 v1.0\n\n本政策说明我们如何收集、使用与保护您的个人信息。"
                        + "我们会在处理前取得您的同意，您可随时撤回。",
                "https://example.com/privacy/v1.0",
                List.of(
                        purpose("account_management", "账号与订单管理", "注册、登录、下单与售后所必需", LegalBasis.CONTRACT, false, false, 60, null),
                        purpose("personalized_recommendation", "个性化推荐", "基于浏览与购买记录推荐商品", LegalBasis.CONSENT, true, false, 24, "推荐算法服务商"),
                        purpose("marketing_email", "营销邮件与短信", "向您发送促销活动、优惠券信息", LegalBasis.CONSENT, true, false, 24, "短信服务商"),
                        purpose("analytics_improvement", "产品分析改进", "统计功能使用情况以改进产品", LegalBasis.CONSENT, true, false, 12, "数据分析服务商")
                )), "seed");

        // v1.1 —— 非实质性变更（仅文案），不要求重新同意
        PolicyVersion v11 = policyService.createVersion(privacy.getId(), new CreateVersionRequest(
                "v1.1", now.minus(Duration.ofDays(200)), "文案修订：补充数据安全说明，处理目的与范围不变",
                false,
                "# 隐私政策 v1.1\n\n本政策说明我们如何收集、使用与保护您的个人信息。"
                        + "我们采用加密存储与最小权限访问控制。您可随时撤回同意。",
                "https://example.com/privacy/v1.1",
                List.of(
                        purpose("account_management", "账号与订单管理", "注册、登录、下单与售后所必需", LegalBasis.CONTRACT, false, false, 60, null),
                        purpose("personalized_recommendation", "个性化推荐", "基于浏览与购买记录推荐商品", LegalBasis.CONSENT, true, false, 24, "推荐算法服务商"),
                        purpose("marketing_email", "营销邮件与短信", "向您发送促销活动、优惠券信息", LegalBasis.CONSENT, true, false, 24, "短信服务商"),
                        purpose("analytics_improvement", "产品分析改进", "统计功能使用情况以改进产品", LegalBasis.CONSENT, true, false, 12, "数据分析服务商")
                )), "seed");

        // v2.0 —— 实质性变更：新增位置服务与第三方广告，要求重新同意
        PolicyVersion v20 = policyService.createVersion(privacy.getId(), new CreateVersionRequest(
                "v2.0", now.minus(Duration.ofDays(30)),
                "新增「位置服务」与「第三方广告投放」两个处理目的，扩展第三方共享范围，构成实质性变更",
                true,
                "# 隐私政策 v2.0\n\n本次更新新增位置服务与第三方广告投放，并扩展了第三方共享范围。"
                        + "对于新增目的，我们将在取得您的单独同意后才会开始处理。",
                "https://example.com/privacy/v2.0",
                List.of(
                        purpose("account_management", "账号与订单管理", "注册、登录、下单与售后所必需", LegalBasis.CONTRACT, false, false, 60, null),
                        purpose("personalized_recommendation", "个性化推荐", "基于浏览与购买记录、位置偏好推荐商品", LegalBasis.CONSENT, true, false, 24, "推荐算法服务商"),
                        purpose("marketing_email", "营销邮件与短信", "向您发送促销活动、优惠券信息", LegalBasis.CONSENT, true, false, 24, "短信服务商"),
                        purpose("analytics_improvement", "产品分析改进", "统计功能使用情况以改进产品", LegalBasis.CONSENT, true, false, 12, "数据分析服务商"),
                        purpose("location_services", "基于位置的服务", "展示附近门店与到店自提点", LegalBasis.CONSENT, true, false, 6, null),
                        purpose("third_party_ads", "第三方广告投放", "与广告平台共享去标识化标识以投放广告", LegalBasis.CONSENT, true, true, 12, "广告平台（3 家）")
                )), "seed");

        // 按真实时间线回放：v1.0 生效（400 天前）→ 用户同意 → v1.1 取代（200 天前）
        // → 用户同意 → v2.0 实质性变更（30 天前）→ 用户在新版上同意 / 部分用户留在旧版
        int tier1 = cohortSize(1000, totalSubjects());
        int tier2 = cohortSize(700, totalSubjects());
        int tier3 = cohortSize(800, totalSubjects());
        int bioBatch = cohortSize(60, totalSubjects());

        policyService.publish(v10.getId(), "seed");
        seedCohort(v10, now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(350)), tier1, 0);

        policyService.publish(v11.getId(), "seed");
        seedCohort(v11, now.minus(Duration.ofDays(200)), now.minus(Duration.ofDays(150)), tier2, tier1);

        policyService.publish(v20.getId(), "seed");
        seedCohort(v20, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(20)), tier3, tier1 + tier2);

        PolicyVersion bioV1 = policyService.createVersion(biometric.getId(), new CreateVersionRequest(
                "v1.0", now.minus(Duration.ofDays(120)), "初始版本", false,
                "# 人脸识别个人信息处理规则 v1.0\n\n人脸特征属于敏感个人信息，"
                        + "仅在您单独同意后用于身份核验，不用于其他目的。",
                "https://example.com/biometric/v1.0",
                List.of(purpose("face_recognition", "人脸识别身份核验", "门店核销与账号找回时进行人证比对",
                        LegalBasis.CONSENT, true, true, 6, "第三方活体检测服务商"))
        ), "seed");
        policyService.publish(bioV1.getId(), "seed");
        seedBiometricCohort(bioV1, now.minus(Duration.ofDays(110)), tier1 + tier2 + tier3 + 50, bioBatch);

        seedWithdrawals(now);

        log.info("Demo data seeded: policies=2, versions=4, subjects={}, events={}",
                subjectRepository.count(), eventRepository.count());
    }

    /**
     * 在某个政策版本生效期间，为一组用户记录同意。occurredAt 回填到历史时刻，
     * 使凭证里的时间线与政策版本的真实生效区间吻合。
     */
    private void seedCohort(PolicyVersion version, Instant occurredFrom, Instant occurredTo,
                            int count, int offset) {
        Random random = new Random(20261117L + offset);
        for (int i = 0; i < count; i++) {
            String externalId = String.format("U%06d", 100000 + offset + i + 1);
            List<ConsentService.ChangeRequest> changes = new ArrayList<>();
            if (random.nextInt(100) >= 4) {
                changes.add(new ConsentService.ChangeRequest("personalized_recommendation",
                        com.consentledger.domain.ConsentAction.GRANT));
            }
            if (random.nextInt(100) >= 18) {
                changes.add(new ConsentService.ChangeRequest("analytics_improvement",
                        com.consentledger.domain.ConsentAction.GRANT));
            }
            if (random.nextInt(100) >= 22) {
                changes.add(new ConsentService.ChangeRequest("marketing_email",
                        com.consentledger.domain.ConsentAction.GRANT));
            }
            if (changes.isEmpty()) {
                continue;
            }
            Instant occurredAt = occurredFrom.plusMillis(
                    (long) (random.nextDouble() * Duration.between(occurredFrom, occurredTo).toMillis()));
            consentService.record(externalId, "用户" + externalId.substring(1), "demo-shop", changes,
                    new ConsentService.WriteContext("WEB", "user", "演示商城",
                            "seed-" + externalId, "203.0.113." + random.nextInt(250),
                            "Mozilla/5.0 (seed)", occurredAt));
        }
        log.info("Seeded cohort {} ({} users) under {}@{}", offset, count,
                version.getPolicy().getCode(), version.getVersionLabel());
    }

    private void seedBiometricCohort(PolicyVersion version, Instant occurredAt, int offset, int count) {
        for (int i = 0; i < count; i++) {
            String externalId = String.format("U%06d", 100000 + offset + i + 1);
            consentService.record(externalId, "用户" + externalId.substring(1), "demo-shop",
                    List.of(new ConsentService.ChangeRequest("face_recognition",
                            com.consentledger.domain.ConsentAction.GRANT)),
                    new ConsentService.WriteContext("APP", "user", "演示商城",
                            "seed-bio-" + externalId, "198.51.100." + (i % 200 + 1),
                            "Mozilla/5.0 (seed)", occurredAt.plusMillis(i * 37_000L)));
        }
    }

    /** 一部分用户在近一个月内撤回了营销授权，用于演示撤回留痕与实时决策。 */
    private void seedWithdrawals(Instant now) {
        Random random = new Random(77771L);
        int withdrawn = 0;
        for (int i = 1; i <= 2400; i += 3) {
            if (random.nextInt(100) >= 22) {
                continue;
            }
            String externalId = String.format("U%06d", 100000 + i);
            if (subjectRepository.findByExternalId(externalId).isEmpty()) {
                continue;
            }
            consentService.record(externalId, "用户" + externalId.substring(1), "demo-shop",
                    List.of(new ConsentService.ChangeRequest("marketing_email",
                            com.consentledger.domain.ConsentAction.WITHDRAW)),
                    new ConsentService.WriteContext("APP", "user", "演示商城",
                            "seed-withdraw-" + externalId, "203.0.113." + random.nextInt(250),
                            "Mozilla/5.0 (seed)", now.minus(Duration.ofDays(random.nextInt(20) + 1))));
            withdrawn++;
        }
        log.info("Seeded {} withdrawal events", withdrawn);
    }

    private int totalSubjects() {
        return Math.max(10, properties.getDemoData().getSubjects());
    }

    /** 按配置的主体总数等比缩放各批次，默认 2000 人对应 1000/700/800。 */
    private int cohortSize(int base, int total) {
        return Math.max(1, (int) Math.round(base * (total / 2000.0)));
    }

    private PurposeDraft purpose(String code, String name, String description, LegalBasis basis,
                                 boolean optIn, boolean sensitive, Integer retentionMonths, String thirdParties) {
        return new PurposeDraft(code, name, description, basis, optIn, sensitive, retentionMonths, thirdParties);
    }
}
