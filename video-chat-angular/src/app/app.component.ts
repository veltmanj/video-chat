import { CommonModule } from '@angular/common';
import { Component, HostListener } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
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
  profileMenuOpen = false;

  constructor(public auth: AuthService, private router: Router) { }

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

  openProfileMenu(event: MouseEvent): void {
    event.preventDefault();
    this.profileMenuOpen = true;
  }

  closeProfileMenu(): void {
    this.profileMenuOpen = false;
  }

  logout(): void {
    this.closeProfileMenu();
    this.failedProfileImageUrl = null;
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.profileMenuOpen || this.isWithinProfileActions(event.target)) {
      return;
    }

    this.closeProfileMenu();
  }

  @HostListener('document:keydown.escape')
  onEscapeKey(): void {
    this.closeProfileMenu();
  }

  private isWithinProfileActions(target: EventTarget | null): boolean {
    return target instanceof Element && !!target.closest('.profile-actions');
  }
}
