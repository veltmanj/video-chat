import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { LiveRoomComponent } from './pages/live-room/live-room.component';

const routes: Routes = [
  { path: '', component: LiveRoomComponent },
  { path: '**', redirectTo: '' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
