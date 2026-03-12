import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { resolveAiAgentConfig } from '../config/runtime-config';

export interface AiAgentContextMessage {
  senderName: string;
  text: string;
  sentAt: string;
}

export interface AiAgentReplyRequest {
  roomId: string;
  participantName: string;
  prompt: string;
  recentMessages: AiAgentContextMessage[];
}

export interface AiAgentReplyResponse {
  agentName?: string;
  reply: string;
  model?: string;
  responseId?: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class AiAgentService {
  private readonly config = resolveAiAgentConfig();

  constructor(private readonly http: HttpClient) { }

  get enabled(): boolean {
    return this.config.enabled;
  }

  get agentName(): string {
    return this.config.name;
  }

  get mentionTrigger(): string {
    return this.config.mention;
  }

  shouldReplyTo(text: string): boolean {
    if (!this.enabled) {
      return false;
    }

    const normalizedText = text.trim().toLowerCase();
    const normalizedMention = this.mentionTrigger.trim().toLowerCase();

    return normalizedText.startsWith(normalizedMention);
  }

  stripMention(text: string): string {
    if (!this.shouldReplyTo(text)) {
      return text.trim();
    }

    return text.trim().slice(this.mentionTrigger.length).trim();
  }

  requestReply(request: AiAgentReplyRequest): Observable<AiAgentReplyResponse> {
    return this.http.post<AiAgentReplyResponse>(this.resolveEndpoint(request.roomId), request);
  }

  private resolveEndpoint(roomId: string): string {
    const trimmedRoomId = roomId.trim();
    if (this.config.endpoint.includes('{roomId}')) {
      return this.config.endpoint.replace('{roomId}', encodeURIComponent(trimmedRoomId));
    }

    return this.config.endpoint;
  }
}
