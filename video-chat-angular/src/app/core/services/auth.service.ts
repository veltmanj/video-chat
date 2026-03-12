import { Injectable, NgZone } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';

declare global {
  interface Window {
    __VIDEOCHAT_CONFIG__?: {
      googleClientId?: string;
      appMode?: string;
    };
    google?: GoogleNamespace;
  }
}

interface GoogleCredentialResponse {
  credential?: string;
  select_by?: string;
}

interface GoogleIdConfiguration {
  client_id: string;
  callback: (response: GoogleCredentialResponse) => void;
  auto_select?: boolean;
  cancel_on_tap_outside?: boolean;
  use_fedcm_for_prompt?: boolean;
}

interface GoogleButtonConfiguration {
  type?: 'standard' | 'icon';
  theme?: 'outline' | 'filled_blue' | 'filled_black';
  size?: 'large' | 'medium' | 'small';
  text?: 'signin_with' | 'signup_with' | 'continue_with' | 'signin';
  shape?: 'pill' | 'rectangular' | 'circle' | 'square';
  logo_alignment?: 'left' | 'center';
  width?: number;
}

interface GoogleNamespace {
  accounts?: {
    id?: {
      initialize: (configuration: GoogleIdConfiguration) => void;
      renderButton: (parent: HTMLElement, options: GoogleButtonConfiguration) => void;
      prompt: () => void;
      disableAutoSelect: () => void;
    };
  };
}

type IdentityClaims = Record<string, unknown> | null;

const GOOGLE_ID_TOKEN_STORAGE_KEY = 'pulse-room:google-id-token';
const GOOGLE_IDENTITY_SCRIPT_ID = 'google-identity-services';
const GOOGLE_IDENTITY_SCRIPT_URL = 'https://accounts.google.com/gsi/client';

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

@Injectable({ providedIn: 'root' })
export class AuthService {
  private initializationPromise: Promise<void> | null = null;
  private readonly clientId = resolveGoogleClientId();
  private readonly appMode = resolveAppMode();
  private readonly debugEvents: string[] = [];
  private readonly debugEventSubject = new Subject<string>();
  private readonly authStateSubject = new BehaviorSubject<boolean>(false);
  private idTokenValue: string | null = null;
  private identityClaimsValue: IdentityClaims = null;
  private gisInitialized = false;
  private gisLoaded = false;
  private buttonRendered = false;

  readonly authState$ = this.authStateSubject.asObservable();
  readonly debugEvents$ = this.debugEventSubject.asObservable();

  constructor(private ngZone: NgZone) {
    this.restoreStoredSession();
  }

  async initialize(): Promise<void> {
    if (!this.hasGoogleClientId) {
      this.pushDebugEvent('missing-client-id');
      return;
    }

    if (!this.initializationPromise) {
      this.initializationPromise = this.loadGoogleIdentityScript()
        .then(() => this.initializeGoogleIdentity())
        .catch((error) => {
          this.initializationPromise = null;
          throw error;
        });
    }

    await this.initializationPromise;
  }

  async renderGoogleButton(container: HTMLElement): Promise<void> {
    if (!this.hasGoogleClientId) {
      throw new Error('Google OAuth client ID is not configured.');
    }

    await this.initialize();

    const googleId = window.google?.accounts?.id;
    if (!googleId) {
      throw new Error('Google Identity Services did not finish loading.');
    }

    container.innerHTML = '';
    googleId.renderButton(container, {
      type: 'standard',
      theme: 'outline',
      size: 'large',
      text: 'continue_with',
      shape: 'pill',
      logo_alignment: 'left',
      width: Math.max(240, Math.round(container.getBoundingClientRect().width || 320))
    });

    this.buttonRendered = true;
    this.pushDebugEvent('button-rendered');
  }

  async login(): Promise<void> {
    await this.initialize();

    const googleId = window.google?.accounts?.id;
    if (!googleId) {
      throw new Error('Google Identity Services did not finish loading.');
    }

    googleId.prompt();
    this.pushDebugEvent('prompt-requested');
  }

  logout(): void {
    this.pushDebugEvent('logout');
    this.clearSession();
    window.google?.accounts?.id?.disableAutoSelect();
  }

  get accessToken(): string | null {
    return null;
  }

  get idToken(): string | null {
    return this.idTokenValue;
  }

  get brokerToken(): string | null {
    return this.idToken;
  }

  get identityClaims(): IdentityClaims {
    return this.identityClaimsValue;
  }

  get profileName(): string | null {
    return this.readStringClaim('name', 'given_name', 'email');
  }

  get profileImageUrl(): string | null {
    return this.readStringClaim('picture');
  }

  get isAuthenticated(): boolean {
    return !!this.brokerToken;
  }

  get hasGoogleClientId(): boolean {
    return !!this.clientId;
  }

  get isDevelopmentMode(): boolean {
    return this.appMode === 'development';
  }

  get oauthDebugEvents(): string[] {
    return [...this.debugEvents];
  }

  get hasStoredNonce(): boolean {
    return false;
  }

  get hasStoredPkceVerifier(): boolean {
    return false;
  }

  get isGoogleIdentityLoaded(): boolean {
    return this.gisLoaded;
  }

  get isGoogleButtonRendered(): boolean {
    return this.buttonRendered;
  }

  private async loadGoogleIdentityScript(): Promise<void> {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return;
    }

    if (window.google?.accounts?.id) {
      this.gisLoaded = true;
      this.pushDebugEvent('gis-ready');
      return;
    }

    const existing = document.getElementById(GOOGLE_IDENTITY_SCRIPT_ID) as HTMLScriptElement | null;
    if (existing) {
      await this.waitForGoogleIdentity();
      this.gisLoaded = true;
      this.pushDebugEvent('gis-ready');
      return;
    }

    await new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.id = GOOGLE_IDENTITY_SCRIPT_ID;
      script.src = GOOGLE_IDENTITY_SCRIPT_URL;
      script.async = true;
      script.defer = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Google Identity Services failed to load.'));
      document.head.appendChild(script);
    });

    await this.waitForGoogleIdentity();
    this.gisLoaded = true;
    this.pushDebugEvent('gis-ready');
  }

  private async waitForGoogleIdentity(): Promise<void> {
    if (typeof window === 'undefined') {
      return;
    }

    for (let attempt = 0; attempt < 40; attempt += 1) {
      if (window.google?.accounts?.id) {
        return;
      }

      await new Promise((resolve) => window.setTimeout(resolve, 50));
    }

    throw new Error('Google Identity Services did not become available.');
  }

  private initializeGoogleIdentity(): void {
    if (this.gisInitialized) {
      return;
    }

    const googleId = window.google?.accounts?.id;
    if (!googleId) {
      throw new Error('Google Identity Services is not available.');
    }

    googleId.initialize({
      client_id: this.clientId,
      callback: (response) => {
        this.ngZone.run(() => {
          this.handleCredentialResponse(response);
        });
      },
      auto_select: false,
      cancel_on_tap_outside: true,
      use_fedcm_for_prompt: true
    });

    this.gisInitialized = true;
    this.pushDebugEvent('gis-initialized');
  }

  private handleCredentialResponse(response: GoogleCredentialResponse): void {
    if (!response.credential) {
      this.pushDebugEvent('credential-missing');
      return;
    }

    const claims = this.decodeJwtClaims(response.credential);
    if (!claims) {
      this.clearSession();
      this.pushDebugEvent('credential-invalid');
      return;
    }

    const expiresAt = this.readJwtExpiry(claims);
    if (expiresAt !== null && expiresAt <= Date.now()) {
      this.clearSession();
      this.pushDebugEvent('credential-expired');
      return;
    }

    this.idTokenValue = response.credential;
    this.identityClaimsValue = claims;
    window.sessionStorage.setItem(GOOGLE_ID_TOKEN_STORAGE_KEY, response.credential);
    this.authStateSubject.next(true);
    this.pushDebugEvent(`credential-received${response.select_by ? `:${response.select_by}` : ''}`);
  }

  private restoreStoredSession(): void {
    if (typeof window === 'undefined') {
      return;
    }

    const storedToken = window.sessionStorage.getItem(GOOGLE_ID_TOKEN_STORAGE_KEY)?.trim() ?? '';
    if (!storedToken) {
      this.clearSession(false);
      return;
    }

    const claims = this.decodeJwtClaims(storedToken);
    if (!claims) {
      this.clearSession();
      this.pushDebugEvent('stored-token-invalid');
      return;
    }

    const expiresAt = this.readJwtExpiry(claims);
    if (expiresAt !== null && expiresAt <= Date.now()) {
      this.clearSession();
      this.pushDebugEvent('stored-token-expired');
      return;
    }

    this.idTokenValue = storedToken;
    this.identityClaimsValue = claims;
    this.authStateSubject.next(true);
    this.pushDebugEvent('stored-token-restored');
  }

  private clearSession(removeStoredToken = true): void {
    this.idTokenValue = null;
    this.identityClaimsValue = null;
    this.authStateSubject.next(false);

    if (removeStoredToken && typeof window !== 'undefined') {
      window.sessionStorage.removeItem(GOOGLE_ID_TOKEN_STORAGE_KEY);
    }
  }

  private decodeJwtClaims(token: string): IdentityClaims {
    const parts = token.split('.');
    if (parts.length !== 3) {
      return null;
    }

    try {
      const decoded = this.decodeBase64Url(parts[1]);
      const parsed = JSON.parse(decoded) as Record<string, unknown>;
      return parsed;
    } catch {
      return null;
    }
  }

  private readJwtExpiry(claims: Record<string, unknown>): number | null {
    const exp = claims['exp'];
    if (typeof exp !== 'number') {
      return null;
    }

    return exp * 1000;
  }

  private decodeBase64Url(value: string): string {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
    return window.atob(padded);
  }

  private pushDebugEvent(event: string): void {
    this.debugEvents.unshift(event);
    this.debugEvents.splice(8);
    this.debugEventSubject.next(event);
  }

  private readStringClaim(...keys: string[]): string | null {
    if (!this.identityClaimsValue) {
      return null;
    }

    for (const key of keys) {
      const value = this.identityClaimsValue[key];
      if (typeof value === 'string' && value.trim()) {
        return value.trim();
      }
    }

    return null;
  }
}
