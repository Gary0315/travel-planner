package com.travel.app.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.travel.app.user.entity.UserEntity;

/**
 * GET /api/auth/me / POST /api/auth/google 共用的對外 user 表示。
 * 詳見 SPEC §19.5。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CurrentUserResponse(
        Long id,
        String email,
        String name,
        String pictureUrl
) {
    public static CurrentUserResponse from(UserEntity user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPictureUrl()
        );
    }
}
