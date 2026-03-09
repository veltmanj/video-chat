import { Injectable } from '@angular/core';
import { CanActivate, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';

@Injectable({ providedIn: 'root' })
export class AuthGuard implements CanActivate {
  constructor(private auth: AuthService, private router: Router) { }

  async canActivate(): Promise<boolean | UrlTree> {
    try {
      await this.auth.initialize();
    } catch (error) {
      console.error('Auth initialization failed', error);
      return this.router.createUrlTree(['/login']);
    }

    if (this.auth.isAuthenticated) {
      return true;
    }

    return this.router.createUrlTree(['/login']);
  }
}
