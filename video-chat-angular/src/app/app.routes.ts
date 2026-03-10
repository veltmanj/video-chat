import { Routes } from '@angular/router';
import { AuthGuard } from './core/services/auth.guard';
import { AcceptableUseComponent } from './pages/acceptable-use.component';
import { LiveRoomComponent } from './pages/live-room/live-room.component';
import { CookiePolicyComponent } from './pages/cookie-policy.component';
import { LoginComponent } from './pages/login.component';
import { PrivacyPolicyComponent } from './pages/privacy-policy.component';
import { SocialHubComponent } from './pages/social-hub/social-hub.component';
import { TermsOfServiceComponent } from './pages/terms-of-service.component';

export const appRoutes: Routes = [
  { path: '', component: LiveRoomComponent, canActivate: [AuthGuard] },
  { path: 'social', component: SocialHubComponent, canActivate: [AuthGuard] },
  { path: 'acceptable-use', component: AcceptableUseComponent },
  { path: 'cookies', component: CookiePolicyComponent },
  { path: 'login', component: LoginComponent },
  { path: 'privacy', component: PrivacyPolicyComponent },
  { path: 'terms', component: TermsOfServiceComponent },
  { path: '**', redirectTo: 'login' }
];
