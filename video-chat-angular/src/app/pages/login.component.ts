import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { LoginDeveloperPanelComponent } from '../components/login-developer-panel/login-developer-panel.component';
import { LoginEmailAuthComponent } from '../components/login-email-auth/login-email-auth.component';
import { LoginHeroComponent } from '../components/login-hero/login-hero.component';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss'],
  standalone: true,
  imports: [CommonModule, RouterLink, LoginHeroComponent, LoginEmailAuthComponent, LoginDeveloperPanelComponent]
})
export class LoginComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('googleButtonHost') private googleButtonHost?: ElementRef<HTMLDivElement>;

  errorMessage = '';
  successMessage = '';
  authDebugMessage = '';
  emailRegistrationAddress = '';
  emailRegistrationDisplayName = '';
  emailLoginAddress = '';
  emailRequestInFlight = false;
  readonly currentOrigin = typeof window !== 'undefined' ? window.location.origin : 'https://<VIDEOCHAT_HOST>';
  readonly providerButtons = [
    { key: 'apple', label: 'Continue with Apple', hint: 'Soon', disabled: true },
    { key: 'x', label: 'Continue with X', hint: 'Soon', disabled: true }
  ];
  private authStateSubscription?: Subscription;

  constructor(public auth: AuthService, private router: Router, private route: ActivatedRoute) { }

  async ngOnInit(): Promise<void> {
    this.authStateSubscription = this.auth.authState$.subscribe((isAuthenticated) => {
      if (isAuthenticated) {
        void this.router.navigateByUrl('/');
      }
    });

    const emailStatus = this.route.snapshot.queryParamMap.get('email_status')?.trim() ?? '';
    const emailToken = this.route.snapshot.queryParamMap.get('email_token')?.trim() ?? '';

    if (emailToken) {
      this.auth.completeEmailAuthentication(emailToken);
      if (typeof window !== 'undefined') {
        window.history.replaceState({}, document.title, window.location.pathname);
      }
      return;
    }

    if (emailStatus) {
      this.applyEmailStatus(emailStatus);
    }
  }

  async ngAfterViewInit(): Promise<void> {
    if (!this.auth.hasGoogleClientId || !this.googleButtonHost) {
      return;
    }

    this.errorMessage = '';
    this.authDebugMessage = '';

    try {
      await this.auth.renderGoogleButton(this.googleButtonHost.nativeElement);
    } catch (error) {
      console.error('Google sign-in setup failed', error);
      this.errorMessage = error instanceof Error ? error.message : 'Could not load Google sign-in.';
      this.authDebugMessage = error instanceof Error ? error.stack || error.message : String(error);
    }
  }

  ngOnDestroy(): void {
    this.authStateSubscription?.unsubscribe();
  }

  async registerWithEmail(): Promise<void> {
    if (!this.emailRegistrationAddress.trim() || !this.emailRegistrationDisplayName.trim()) {
      this.errorMessage = 'Enter a display name and email address first.';
      this.successMessage = '';
      return;
    }

    this.emailRequestInFlight = true;
    this.errorMessage = '';
    this.successMessage = '';
    try {
      this.successMessage = await this.auth.registerWithEmail(
        this.emailRegistrationAddress.trim(),
        this.emailRegistrationDisplayName.trim()
      );
      this.emailLoginAddress = this.emailRegistrationAddress.trim();
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Could not start email registration.';
    } finally {
      this.emailRequestInFlight = false;
    }
  }

  async loginWithEmail(): Promise<void> {
    if (!this.emailLoginAddress.trim()) {
      this.errorMessage = 'Enter your email address first.';
      this.successMessage = '';
      return;
    }

    this.emailRequestInFlight = true;
    this.errorMessage = '';
    this.successMessage = '';
    try {
      this.successMessage = await this.auth.loginWithEmail(this.emailLoginAddress.trim());
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Could not send the sign-in link.';
    } finally {
      this.emailRequestInFlight = false;
    }
  }

  get showDeveloperPanel(): boolean {
    return this.auth.isDevelopmentMode;
  }

  get brokerTokenPresent(): boolean {
    return !!this.auth.brokerToken;
  }

  get accessTokenPresent(): boolean {
    return !!this.auth.accessToken;
  }

  get identitySummary(): string {
    const claims = this.auth.identityClaims as Record<string, unknown> | null;
    if (!claims) {
      return 'none';
    }

    const subject = typeof claims['sub'] === 'string' ? claims['sub'] : '';
    const email = typeof claims['email'] === 'string' ? claims['email'] : '';
    return [subject, email].filter(Boolean).join(' | ') || 'claims loaded';
  }

  get loginUnavailableMessage(): string {
    if (this.auth.hasGoogleClientId) {
      return '';
    }

    return this.showDeveloperPanel
      ? 'Google OAuth is not configured for this stack yet. Email access remains available below.'
      : 'Google sign-in is temporarily unavailable on this environment.';
  }

  private applyEmailStatus(status: string): void {
    switch (status) {
      case 'verified':
        this.successMessage = 'Your email address has been verified. Finishing sign-in now.';
        this.errorMessage = '';
        break;
      case 'signed-in':
        this.successMessage = 'Your secure sign-in link was accepted. Finishing sign-in now.';
        this.errorMessage = '';
        break;
      case 'expired':
        this.errorMessage = 'That email link has expired. Request a fresh one below.';
        this.successMessage = '';
        break;
      case 'invalid':
        this.errorMessage = 'That email link is no longer valid. Request a fresh one below.';
        this.successMessage = '';
        break;
      case 'disabled':
        this.errorMessage = 'Email authentication is not available on this environment.';
        this.successMessage = '';
        break;
      default:
        break;
    }
  }
}
