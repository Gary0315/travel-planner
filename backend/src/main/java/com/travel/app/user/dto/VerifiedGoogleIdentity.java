package com.travel.app.user.dto;

/**
 * Google ID Token 驗證通過後抽出的 identity。內部用，不直接出 API。
 * 詳見 SPEC §19.3。
 */
public record VerifiedGoogleIdentity(
        String sub,
        String email,
        String name,
        String pictureUrl
) {}
