import { Routes } from '@angular/router';
import { LiveRoomComponent } from './pages/live-room/live-room.component';

export const appRoutes: Routes = [
  { path: '', component: LiveRoomComponent },
  { path: '**', redirectTo: '' }
];
