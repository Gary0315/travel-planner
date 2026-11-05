package com.travel.app.user.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.travel.app.user.dto.VerifiedGoogleIdentity;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleIdTokenVerifierServiceTest {

    @Test
    void disabled_when_verifier_is_null() {
        GoogleIdTokenVerifierService svc = new GoogleIdTokenVerifierService(null);
        assertFalse(svc.isEnabled());
        assertTrue(svc.verify("anything").isEmpty());
    }

    @Test
    void verify_returns_empty_for_blank_token() {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdTokenVerifierService svc = new GoogleIdTokenVerifierService(verifier);
        assertTrue(svc.verify("").isEmpty());
        assertTrue(svc.verify("   ").isEmpty());
        assertTrue(svc.verify(null).isEmpty());
    }

    @Test
    void verify_returns_empty_when_verifier_returns_null() throws Exception {
        // GoogleIdTokenVerifier 的 verify(String) 內部會做 signature / aud / iss / exp 檢查；
        // 任一不過就回 null（不 throw）
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        when(verifier.verify("bad-token")).thenReturn(null);

        GoogleIdTokenVerifierService svc = new GoogleIdTokenVerifierService(verifier);
        assertTrue(svc.verify("bad-token").isEmpty());
    }

    @Test
    void verify_returns_empty_when_verifier_throws() throws Exception {
        // 網路 / JSON parse / 其他例外
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        when(verifier.verify("boom")).thenThrow(new GeneralSecurityException("simulated signature failure"));

        GoogleIdTokenVerifierService svc = new GoogleIdTokenVerifierService(verifier);
        assertTrue(svc.verify("boom").isEmpty());
    }

    @Test
    void verify_returns_identity_on_success() throws Exception {
        GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
        GoogleIdToken token = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-12345");
        payload.setEmail("alice@example.com");
        payload.set("name", "Alice Chen");
        payload.set("picture", "https://lh3.googleusercontent.com/a/alice");

        when(verifier.verify("good-token")).thenReturn(token);
        when(token.getPayload()).thenReturn(payload);

        GoogleIdTokenVerifierService svc = new GoogleIdTokenVerifierService(verifier);
        Optional<VerifiedGoogleIdentity> result = svc.verify("good-token");

        assertTrue(result.isPresent());
        VerifiedGoogleIdentity identity = result.get();
        assertEquals("google-sub-12345", identity.sub());
        assertEquals("alice@example.com", identity.email());
        assertEquals("Alice Chen", identity.name());
        assertEquals("https://lh3.googleusercontent.com/a/alice", identity.pictureUrl());
    }
}
