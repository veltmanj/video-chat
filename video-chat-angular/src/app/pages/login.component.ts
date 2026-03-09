import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss'],
  standalone: true,
  imports: [CommonModule]
})
export class LoginComponent {
  errorMessage = '';
  isLoading = false;
  readonly currentOrigin = typeof window !== 'undefined' ? window.location.origin : 'https://<VIDEOCHAT_HOST>';
  readonly providerButtons = [
    { key: 'apple', label: 'Continue with Apple', hint: 'Soon', disabled: true },
    { key: 'x', label: 'Continue with X', hint: 'Soon', disabled: true }
  ];

  constructor(public auth: AuthService) { }

  async login(): Promise<void> {
    this.errorMessage = '';
    this.isLoading = true;

    try {
      await this.auth.login();
    } catch (error) {
      console.error('Login failed', error);
      this.errorMessage = error instanceof Error ? error.message : 'Login failed.';
      this.isLoading = false;
    }
  }

  get showDeveloperPanel(): boolean {
    return this.auth.isDevelopmentMode;
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
