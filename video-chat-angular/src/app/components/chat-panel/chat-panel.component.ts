import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatMessage } from '../../core/models/room.models';

@Component({
  selector: 'app-chat-panel',
  templateUrl: './chat-panel.component.html',
  styleUrl: './chat-panel.component.scss',
  standalone: true,
  imports: [CommonModule, FormsModule]
})
export class ChatPanelComponent {
  @Input() messages: ChatMessage[] = [];
  @Input() connected = false;
  @Output() sendMessage = new EventEmitter<string>();

  draft = '';

  submitMessage(): void {
    const text = this.draft.trim();
    if (!text || !this.connected) {
      return;
    }

    this.sendMessage.emit(text);
    this.draft = '';
  }
}
