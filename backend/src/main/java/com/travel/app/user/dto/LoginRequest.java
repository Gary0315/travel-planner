package com.travel.app.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/auth/google 的 request body。詳見 SPEC §19.5。 */
public record LoginRequest(
        @NotBlank
        @Size(max = 4096) // ID token 通常 1-2KB，4KB 給足保險上限避免暴力 payload
        String idToken
) {}
