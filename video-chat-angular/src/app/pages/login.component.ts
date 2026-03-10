import { CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss'],
  standalone: true,
  imports: [CommonModule, RouterLink]
})
export class LoginComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('googleButtonHost') private googleButtonHost?: ElementRef<HTMLDivElement>;

  errorMessage = '';
  authDebugMessage = '';
  readonly currentOrigin = typeof window !== 'undefined' ? window.location.origin : 'https://<VIDEOCHAT_HOST>';
  readonly providerButtons = [
    { key: 'apple', label: 'Continue with Apple', hint: 'Soon', disabled: true },
    { key: 'x', label: 'Continue with X', hint: 'Soon', disabled: true }
  ];
  private authStateSubscription?: Subscription;

  constructor(public auth: AuthService, private router: Router) { }

  async ngOnInit(): Promise<void> {
    this.authStateSubscription = this.auth.authState$.subscribe((isAuthenticated) => {
      if (isAuthenticated) {
        void this.router.navigateByUrl('/');
      }
    });
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
      ? 'Google OAuth is not configured for this stack yet.'
      : 'Login is temporarily unavailable. Contact the administrator.';
  }
}
