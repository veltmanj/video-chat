package nl.nextend.videobackoffice.social.controller;

import nl.nextend.videobackoffice.social.api.SocialApi.EmailAuthStartResponse;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailLoginRequest;
import nl.nextend.videobackoffice.social.api.SocialApi.EmailRegistrationRequest;
import nl.nextend.videobackoffice.social.auth.EmailAuthService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/social/v1/auth")
public class SocialAuthController {

    private final EmailAuthService emailAuthService;

    public SocialAuthController(EmailAuthService emailAuthService) {
        this.emailAuthService = emailAuthService;
    }

    @GetMapping(path = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> jwks() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(emailAuthService.jwksJson());
    }

    @PostMapping("/email/register")
    EmailAuthStartResponse register(@Valid @RequestBody EmailRegistrationRequest request) {
        return emailAuthService.register(request);
    }

    @PostMapping("/email/login")
    EmailAuthStartResponse login(@Valid @RequestBody EmailLoginRequest request) {
        return emailAuthService.login(request);
    }

    @GetMapping("/email/verify")
    ResponseEntity<Void> verify(@RequestParam String token) {
        return ResponseEntity.status(302)
            .header(HttpHeaders.LOCATION, emailAuthService.completeRegistration(token).toString())
            .build();
    }

    @GetMapping("/email/authenticate")
    ResponseEntity<Void> authenticate(@RequestParam String token) {
        return ResponseEntity.status(302)
            .header(HttpHeaders.LOCATION, emailAuthService.completeLogin(token).toString())
            .build();
    }
}
