package com.travel.app.shared.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 啟動時阻擋「prod profile + H2 in-memory」的危險組合。
 *
 * application.yml 的 datasource 預設值是 H2 in-memory + create-drop（方便本機開發），
 * 但若部署到正式環境忘記設 DB_URL，會用 in-memory DB 跑 → 每次容器重啟所有 share 連結全死。
 * 這個 check 在 SPRING_PROFILES_ACTIVE=prod 時偵測到 h2:mem 就拋例外阻止啟動，
 * 強迫運維提供持久化資料庫連線設定。
 */
@Component
public class DatabaseSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSafetyCheck.class);
    private static final String PROD_PROFILE = "prod";

    private final Environment env;
    private final String datasourceUrl;

    public DatabaseSafetyCheck(Environment env,
                                @Value("${spring.datasource.url}") String datasourceUrl) {
        this.env = env;
        this.datasourceUrl = datasourceUrl;
    }

    @PostConstruct
    void verify() {
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains(PROD_PROFILE);
        boolean isInMemoryH2 = datasourceUrl != null && datasourceUrl.contains("h2:mem");

        if (isProd && isInMemoryH2) {
            throw new IllegalStateException(
                    "Refusing to start: SPRING_PROFILES_ACTIVE=prod 但 datasource 是 H2 in-memory。"
                            + " 請設定 DB_URL 環境變數指向持久化資料庫（PostgreSQL / MySQL），"
                            + " 並視需要調整 DB_DRIVER / DB_USERNAME / DB_PASSWORD / DB_DDL_AUTO"
                            + "（dialect 不必設，Hibernate 會自己從 JDBC metadata 判）。"
                            + " 目前的 datasource.url = " + datasourceUrl);
        }
        if (isInMemoryH2) {
            log.warn("Using H2 in-memory database (datasource.url={}). Data will be lost on restart. "
                    + "Set SPRING_PROFILES_ACTIVE=prod and DB_URL for persistent storage.", datasourceUrl);
        }
    }
}
