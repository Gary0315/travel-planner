package com.travel.app.user.service;

import com.travel.app.user.dto.VerifiedGoogleIdentity;
import com.travel.app.user.entity.UserEntity;
import com.travel.app.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 用 Google identity 找對應 user；不存在則建檔；存在則更新 profile 與 last_login_at。
     *
     * 以 google_sub 為 lookup key（不是 email；email 可變）。詳見 SPEC §19.2。
     */
    @Transactional
    public UserEntity findOrCreate(VerifiedGoogleIdentity identity) {
        return userRepository.findByGoogleSub(identity.sub())
                .map(existing -> {
                    existing.refreshFromGoogleProfile(identity.email(), identity.name(), identity.pictureUrl());
                    return existing;
                })
                .orElseGet(() -> userRepository.save(new UserEntity(
                        identity.sub(),
                        identity.email(),
                        identity.name(),
                        identity.pictureUrl()
                )));
    }
}
