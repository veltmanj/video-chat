import { Routes } from '@angular/router';
import { AuthGuard } from './core/services/auth.guard';
import { LiveRoomComponent } from './pages/live-room/live-room.component';
import { LoginComponent } from './pages/login.component';

export const appRoutes: Routes = [
  { path: '', component: LiveRoomComponent, canActivate: [AuthGuard] },
  { path: 'login', component: LoginComponent },
  { path: '**', redirectTo: 'login' }
];
