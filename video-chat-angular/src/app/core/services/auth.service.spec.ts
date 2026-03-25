import { HttpClient } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { NgZone } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { AuthService } from './auth.service';

function createIdToken(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = btoa(JSON.stringify(payload));
  return `${header}.${claims}.signature`;
}

describe('AuthService', () => {
  let service: AuthService;
  let initializeSpy: ReturnType<typeof vi.fn>;
  let renderButtonSpy: ReturnType<typeof vi.fn>;
  let promptSpy: ReturnType<typeof vi.fn>;
  let disableAutoSelectSpy: ReturnType<typeof vi.fn>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    window.__VIDEOCHAT_CONFIG__ = {
      googleClientId: 'google-client-id',
      appMode: 'development'
    };

    sessionStorage.clear();

    initializeSpy = vi.fn();
    renderButtonSpy = vi.fn();
    promptSpy = vi.fn();
    disableAutoSelectSpy = vi.fn();

    window.google = {
      accounts: {
        id: {
          initialize: initializeSpy,
          renderButton: renderButtonSpy,
          prompt: promptSpy,
          disableAutoSelect: disableAutoSelectSpy
        }
      }
    } as any;

    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(), provideHttpClientTesting()]
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    delete window.__VIDEOCHAT_CONFIG__;
    delete window.google;
    sessionStorage.clear();
    httpMock.verify();
  });

  it('initializes Google Identity Services once', async () => {
    await Promise.all([service.initialize(), service.initialize()]);

    expect(initializeSpy).toHaveBeenCalledTimes(1);
    expect(initializeSpy).toHaveBeenCalledWith(expect.objectContaining({
      client_id: 'google-client-id'
    }));
    expect(service.isGoogleIdentityLoaded).toBe(true);
  });

  it('renders the Google button after initialization', async () => {
    const container = document.createElement('div');
    Object.defineProperty(container, 'getBoundingClientRect', {
      value: () => ({ width: 320 })
    });

    await service.renderGoogleButton(container);

    expect(renderButtonSpy).toHaveBeenCalledTimes(1);
    expect(renderButtonSpy).toHaveBeenCalledWith(container, expect.objectContaining({
      text: 'continue_with',
      width: 320
    }));
    expect(service.isGoogleButtonRendered).toBe(true);
  });

  it('stores a valid Google credential as the broker token', async () => {
    await service.initialize();

    const initializeArgs = initializeSpy.mock.calls[0]?.[0] as { callback: (response: { credential?: string; select_by?: string }) => void; };
    const token = createIdToken({
      sub: 'google-user-id',
      email: 'operator@example.com',
      exp: Math.floor(Date.now() / 1000) + 3600
    });

    initializeArgs.callback({ credential: token, select_by: 'btn' });

    expect(service.brokerToken).toBe(token);
    expect(service.idToken).toBe(token);
    expect(service.isAuthenticated).toBe(true);
    expect(service.identityClaims).toEqual(expect.objectContaining({
      sub: 'google-user-id',
      email: 'operator@example.com'
    }));
  });

  it('restores a stored token when it is still valid', () => {
    const storedToken = createIdToken({
      sub: 'stored-user',
      email: 'stored@example.com',
      exp: Math.floor(Date.now() / 1000) + 3600
    });
    sessionStorage.setItem('pulse-room:identity-token', storedToken);

    const restoredService = new AuthService(TestBed.inject(NgZone), TestBed.inject(HttpClient));

    expect(restoredService.brokerToken).toBe(storedToken);
    expect(restoredService.isAuthenticated).toBe(true);
  });

  it('clears the session and disables auto-select on logout', async () => {
    await service.initialize();

    const initializeArgs = initializeSpy.mock.calls[0]?.[0] as { callback: (response: { credential?: string; select_by?: string }) => void; };
    const token = createIdToken({
      sub: 'google-user-id',
      exp: Math.floor(Date.now() / 1000) + 3600
    });
    initializeArgs.callback({ credential: token });

    service.logout();

    expect(service.isAuthenticated).toBe(false);
    expect(service.brokerToken).toBeNull();
    expect(sessionStorage.getItem('pulse-room:identity-token')).toBeNull();
    expect(disableAutoSelectSpy).toHaveBeenCalledTimes(1);
  });

  it('fails fast when the Google client ID is missing', async () => {
    delete window.__VIDEOCHAT_CONFIG__;
    const unconfiguredService = new AuthService(TestBed.inject(NgZone), TestBed.inject(HttpClient));

    await expect(unconfiguredService.renderGoogleButton(document.createElement('div'))).rejects.toThrow(
      'Google OAuth client ID is not configured.'
    );
  });

  it('exposes development mode from runtime config', () => {
    expect(service.isDevelopmentMode).toBe(true);
  });

  it('stores a backend-issued email token when the callback completes', () => {
    const token = createIdToken({
      iss: 'https://localhost/social-api/social/v1/auth',
      sub: 'email:account-1',
      email: 'operator@example.com',
      name: 'Operator Example',
      exp: Math.floor(Date.now() / 1000) + 3600
    });

    service.completeEmailAuthentication(token);

    expect(service.brokerToken).toBe(token);
    expect(service.profileName).toBe('Operator Example');
    expect(sessionStorage.getItem('pulse-room:identity-token')).toBe(token);
  });

  it('posts email login requests to the backoffice auth endpoint', async () => {
    const pending = service.loginWithEmail('operator@example.com');
    const request = httpMock.expectOne('/social-api/social/v1/auth/email/login');

    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'operator@example.com' });

    request.flush({ message: 'Check your email for a secure sign-in link.' });
    await expect(pending).resolves.toBe('Check your email for a secure sign-in link.');
  });
});
