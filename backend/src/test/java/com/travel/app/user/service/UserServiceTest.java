package com.travel.app.user.service;

import com.travel.app.user.dto.VerifiedGoogleIdentity;
import com.travel.app.user.entity.UserEntity;
import com.travel.app.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Test
    void creates_new_user_when_google_sub_not_seen_before() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByGoogleSub("new-sub")).thenReturn(Optional.empty());
        when(repo.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserService service = new UserService(repo);
        UserEntity result = service.findOrCreate(new VerifiedGoogleIdentity(
                "new-sub", "newby@example.com", "Newby", "https://pic"));

        assertEquals("new-sub", result.getGoogleSub());
        assertEquals("newby@example.com", result.getEmail());
        assertEquals("Newby", result.getName());
        assertEquals("https://pic", result.getPictureUrl());
        verify(repo).save(any(UserEntity.class));
    }

    @Test
    void updates_existing_user_profile_and_last_login_when_returning() throws InterruptedException {
        UserRepository repo = mock(UserRepository.class);
        UserEntity existing = new UserEntity("existing-sub", "old@example.com", "Old Name", "https://old-pic");
        LocalDateTime originalLastLogin = existing.getLastLoginAt();
        // 等一個非零毫秒，避免 LocalDateTime.now() 同 tick 撞題
        Thread.sleep(5);
        when(repo.findByGoogleSub("existing-sub")).thenReturn(Optional.of(existing));

        UserService service = new UserService(repo);
        UserEntity result = service.findOrCreate(new VerifiedGoogleIdentity(
                "existing-sub", "new@example.com", "New Name", "https://new-pic"));

        // 同一個 entity instance，被 mutate（@Transactional 內 Hibernate 會自動 flush）
        assertEquals(existing, result);
        assertEquals("new@example.com", result.getEmail());
        assertEquals("New Name", result.getName());
        assertEquals("https://new-pic", result.getPictureUrl());
        assertTrue(result.getLastLoginAt().isAfter(originalLastLogin),
                "lastLoginAt should be bumped on returning login");
        // 沒呼叫 save —— 走 dirty checking
        verify(repo, never()).save(any());
    }

    @Test
    void identifies_user_by_google_sub_not_email() {
        // 同一個 sub 不同 email（Google 允許使用者改 email）
        UserRepository repo = mock(UserRepository.class);
        UserEntity existing = new UserEntity("stable-sub", "old@example.com", "Same", "https://pic");
        when(repo.findByGoogleSub("stable-sub")).thenReturn(Optional.of(existing));

        UserService service = new UserService(repo);
        UserEntity result = service.findOrCreate(new VerifiedGoogleIdentity(
                "stable-sub", "renamed@example.com", "Same", "https://pic"));

        assertEquals("renamed@example.com", result.getEmail());
        verify(repo, never()).save(any());
    }
}
