package com.iflytek.skillhub.auth.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Dongbuddy trusted-gateway SSO provider.
 *
 * <p>The shared secret must match the one configured on the Dongbuddy
 * engine-gateway side ({@code SKILLHUB_SSO_SECRET}); tickets are HMAC-SHA256
 * signed with it. Kept disabled by default so OSS deployments never accept
 * dongbuddy tickets unless the operator opts in explicitly.
 */
@Component
@ConfigurationProperties(prefix = "skillhub.auth.dongbuddy")
public class DongbuddySsoProperties {

    public static final String PROVIDER_CODE = "dongbuddy";

    /** Master switch for the dongbuddy passive-session provider. */
    private boolean enabled = false;

    /** Shared HMAC-SHA256 secret used to sign/verify Dongbuddy tickets. */
    private String secret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    /** True only when the provider is switched on AND a secret is present. */
    public boolean isUsable() {
        return enabled && secret != null && !secret.isBlank();
    }
}
