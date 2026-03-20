import { Routes } from '@angular/router';
import { AuthGuard } from './core/services/auth.guard';

export const appRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/live-room/live-room.component').then((m) => m.LiveRoomComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'social',
    loadComponent: () => import('./pages/social-hub/social-hub.component').then((m) => m.SocialHubComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'acceptable-use',
    loadComponent: () => import('./pages/acceptable-use.component').then((m) => m.AcceptableUseComponent)
  },
  {
    path: 'cookies',
    loadComponent: () => import('./pages/cookie-policy.component').then((m) => m.CookiePolicyComponent)
  },
  {
    path: 'login',
    loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'privacy',
    loadComponent: () => import('./pages/privacy-policy.component').then((m) => m.PrivacyPolicyComponent)
  },
  {
    path: 'terms',
    loadComponent: () => import('./pages/terms-of-service.component').then((m) => m.TermsOfServiceComponent)
  },
  { path: '**', redirectTo: 'login' }
];
