import { Injectable } from '@angular/core';
import { AuthConfig, OAuthService } from 'angular-oauth2-oidc';

declare global {
  interface Window {
    __VIDEOCHAT_CONFIG__?: {
      googleClientId?: string;
      appMode?: string;
    };
  }
}

function resolveGoogleClientId(): string {
  if (typeof window === 'undefined') {
    return '';
  }

  return window.__VIDEOCHAT_CONFIG__?.googleClientId?.trim() ?? '';
}

function resolveAppMode(): string {
  if (typeof window === 'undefined') {
    return 'production';
  }

  return window.__VIDEOCHAT_CONFIG__?.appMode?.trim().toLowerCase() || 'production';
}

export function createAuthConfig(): AuthConfig {
  return {
    issuer: 'https://accounts.google.com',
    clientId: resolveGoogleClientId(),
    redirectUri: typeof window !== 'undefined' ? window.location.origin : '',
    scope: 'openid profile email',
    responseType: 'code',
    strictDiscoveryDocumentValidation: false,
  };
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private initializationPromise: Promise<void> | null = null;
  private readonly config: AuthConfig;
  private readonly appMode = resolveAppMode();

  constructor(private oauthService: OAuthService) {
    this.config = createAuthConfig();
    this.oauthService.configure(this.config);
  }

  async initialize(): Promise<void> {
    if (!this.initializationPromise) {
      this.initializationPromise = Promise.resolve(this.oauthService.loadDiscoveryDocumentAndTryLogin())
        .then(() => undefined)
        .catch((error) => {
          this.initializationPromise = null;
          throw error;
        });
    }

    await this.initializationPromise;
  }

  async login(): Promise<void> {
    if (!this.hasGoogleClientId) {
      throw new Error('Google OAuth client ID is not configured.');
    }

    await this.initialize();
    this.oauthService.initLoginFlow();
  }

  logout(): void {
    this.oauthService.logOut();
  }

  get accessToken(): string | null {
    return this.oauthService.getAccessToken();
  }

  get idToken(): string | null {
    return this.oauthService.getIdToken();
  }

  get brokerToken(): string | null {
    return this.idToken;
  }

  get identityClaims(): any {
    return this.oauthService.getIdentityClaims();
  }

  get isAuthenticated(): boolean {
    return !!this.brokerToken;
  }

  get hasGoogleClientId(): boolean {
    return !!this.config.clientId;
  }

  get isDevelopmentMode(): boolean {
    return this.appMode === 'development';
  }
}
