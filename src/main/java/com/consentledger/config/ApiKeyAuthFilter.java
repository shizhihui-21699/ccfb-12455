package com.consentledger.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.consentledger.domain.ApiClient;
import com.consentledger.repo.ApiClientRepository;
import com.consentledger.service.Hashing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 业务系统调用 /api/** 时的接入认证。
 * 密钥通过 X-API-Key 头传递；校验通过后把客户端名与授权用途放进请求上下文。
 * 认证失败不下发 WWW-Authenticate 挑战，直接 401 + JSON 错误体。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "consent.apiClient";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final ApiClientRepository clientRepository;
    private final Hashing hashing;

    public ApiKeyAuthFilter(ApiClientRepository clientRepository, Hashing hashing) {
        this.clientRepository = clientRepository;
        this.hashing = hashing;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            reject(response, "缺少 X-API-Key 请求头");
            return;
        }
        Optional<ApiClient> client = clientRepository.findByKeyHash(hashing.apiKeyHash(apiKey));
        if (client.isEmpty() || !client.get().isEnabled()) {
            log.debug("Rejected API key with prefix {}", apiKey.length() > 8 ? apiKey.substring(0, 8) : "***");
            reject(response, "API 密钥无效或已停用");
            return;
        }
        ApiClient authenticated = client.get();
        request.setAttribute(CLIENT_ATTRIBUTE, authenticated);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authenticated.getName(), null, authorities));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static List<String> allowedPurposes(ApiClient client) {
        if (client.getPurposes() == null || client.getPurposes().isBlank() || "*".equals(client.getPurposes().trim())) {
            return List.of("*");
        }
        return Arrays.stream(client.getPurposes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static boolean mayAccess(ApiClient client, String purposeCode) {
        List<String> allowed = allowedPurposes(client);
        return allowed.contains("*") || allowed.contains(purposeCode);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }
}
