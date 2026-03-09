import { TestBed } from '@angular/core/testing';
import { type MockedObject, vi } from 'vitest';
import { OAuthService } from 'angular-oauth2-oidc';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let oauthService: MockedObject<OAuthService>;
  let service: AuthService;

  beforeEach(() => {
    window.__VIDEOCHAT_CONFIG__ = {
      googleClientId: 'google-client-id',
      appMode: 'development'
    };

    oauthService = {
      configure: vi.fn(),
      loadDiscoveryDocumentAndTryLogin: vi.fn().mockResolvedValue(true),
      initLoginFlow: vi.fn(),
      logOut: vi.fn(),
      getAccessToken: vi.fn(),
      getIdToken: vi.fn(),
      getIdentityClaims: vi.fn()
    } as unknown as MockedObject<OAuthService>;

    TestBed.configureTestingModule({
      providers: [
        AuthService,
        { provide: OAuthService, useValue: oauthService }
      ]
    });

    service = TestBed.inject(AuthService);
  });

  afterEach(() => {
    delete window.__VIDEOCHAT_CONFIG__;
  });

  it('configures oauth without loading the discovery document in the constructor', () => {
    expect(oauthService.configure).toHaveBeenCalledTimes(1);
    expect(oauthService.configure).toHaveBeenCalledWith(expect.objectContaining({
      clientId: 'google-client-id',
      responseType: 'code'
    }));
    expect(oauthService.loadDiscoveryDocumentAndTryLogin).not.toHaveBeenCalled();
  });

  it('loads the discovery document only once when initialized repeatedly', async () => {
    await Promise.all([service.initialize(), service.initialize()]);

    expect(oauthService.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalledTimes(1);
  });

  it('resets initialization state after a failed discovery load', async () => {
    oauthService.loadDiscoveryDocumentAndTryLogin
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(true);

    await expect(service.initialize()).rejects.toThrow('boom');
    await expect(service.initialize()).resolves.toBeUndefined();

    expect(oauthService.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalledTimes(2);
  });

  it('initializes before starting the code flow login', async () => {
    await service.login();

    expect(oauthService.loadDiscoveryDocumentAndTryLogin).toHaveBeenCalledTimes(1);
    expect(oauthService.initLoginFlow).toHaveBeenCalledTimes(1);
  });

  it('fails fast when the Google OAuth client ID is missing', async () => {
    delete window.__VIDEOCHAT_CONFIG__;
    const unconfiguredService = new AuthService(oauthService);

    await expect(unconfiguredService.login()).rejects.toThrow('Google OAuth client ID is not configured.');
    expect(oauthService.loadDiscoveryDocumentAndTryLogin).not.toHaveBeenCalled();
    expect(oauthService.initLoginFlow).not.toHaveBeenCalled();
  });

  it('uses the id token as the broker token', () => {
    oauthService.getIdToken.mockReturnValue('broker-id-token');
    oauthService.getAccessToken.mockReturnValue('oauth-access-token');

    expect(service.brokerToken).toBe('broker-id-token');
    expect(service.isAuthenticated).toBe(true);
  });

  it('exposes development mode from runtime config', () => {
    expect(service.isDevelopmentMode).toBe(true);
  });
});
