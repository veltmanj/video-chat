import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { LiveRoomComponent } from './pages/live-room/live-room.component';
import { CameraGridComponent } from './components/camera-grid/camera-grid.component';
import { ChatPanelComponent } from './components/chat-panel/chat-panel.component';

@NgModule({
  declarations: [
    AppComponent,
    LiveRoomComponent,
    CameraGridComponent,
    ChatPanelComponent
  ],
  imports: [
    BrowserModule,
    FormsModule,
    AppRoutingModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule {
}
