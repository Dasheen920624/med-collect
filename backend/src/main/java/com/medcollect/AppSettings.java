package com.medcollect;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("medcollect")
public record AppSettings(
        String adminUser, String adminPassword, String reviewerUser, String reviewerPassword,
        String modelBaseUrl, String modelName, String modelKey, String settingsKey,
        String cdssUrl, String cdssUser, String cdssPassword, String cdssCertSha256, boolean cdssSyncEnabled,
        String searxUrl, List<String> privateSourceHosts, boolean schedulingEnabled) {
}
