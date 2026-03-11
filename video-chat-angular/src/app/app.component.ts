import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive]
})
export class AppComponent {
  private failedProfileImageUrl: string | null = null;

  constructor(public auth: AuthService) { }

  get profileImageUrl(): string | null {
    const currentUrl = this.auth.profileImageUrl;
    if (!currentUrl || currentUrl === this.failedProfileImageUrl) {
      return null;
    }

    return currentUrl;
  }

  get profileName(): string {
    return this.auth.profileName || 'My social profile';
  }

  get profileInitials(): string {
    const parts = this.profileName
      .split(/\s+/)
      .map((part) => part.trim())
      .filter((part) => !!part);

    if (!parts.length) {
      return 'PR';
    }

    return parts
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? '')
      .join('');
  }

  onProfileImageError(): void {
    this.failedProfileImageUrl = this.auth.profileImageUrl;
  }
}
