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
  @Input() aiAgentBusy = false;
  @Input() aiAgentEnabled = false;
  @Input() aiAgentMention = '@pulse';
  @Input() aiAgentName = 'Pulse Copilot';
  @Input() messages: ChatMessage[] = [];
  @Input() connected = false;
  @Output() sendMessage = new EventEmitter<string>();

  draft = '';

  get composerPlaceholder(): string {
    return this.aiAgentEnabled
      ? `Typ een bericht of mention ${this.aiAgentMention}`
      : 'Typ een bericht voor de room';
  }

  submitMessage(): void {
    const text = this.draft.trim();
    if (!text || !this.connected) {
      return;
    }

    this.sendMessage.emit(text);
    this.draft = '';
  }
}
