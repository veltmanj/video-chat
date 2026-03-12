// @vitest-environment jsdom
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AiAgentService } from './ai-agent.service';

describe('AiAgentService', () => {
  let service: AiAgentService;
  let httpTestingController: HttpTestingController;

  beforeEach(() => {
    window.__VIDEOCHAT_CONFIG__ = {
      aiAgentEnabled: 'true',
      aiAgentEndpoint: '/social-api/social/v1/rooms/{roomId}/assistant-replies',
      aiAgentMention: '@helper',
      aiAgentName: 'Helper Bot'
    };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });

    service = TestBed.inject(AiAgentService);
    httpTestingController = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTestingController.verify();
    delete window.__VIDEOCHAT_CONFIG__;
  });

  it('uses runtime config for endpoint and mention handling', () => {
    expect(service.enabled).toBe(true);
    expect(service.agentName).toBe('Helper Bot');
    expect(service.mentionTrigger).toBe('@helper');
    expect(service.shouldReplyTo('@helper wat zie je?')).toBe(true);
    expect(service.stripMention('@helper   wat zie je?')).toBe('wat zie je?');
  });

  it('posts reply requests to the configured endpoint', () => {
    service.requestReply({
      participantName: 'Alice',
      prompt: 'Vat dit samen',
      recentMessages: [],
      roomId: 'main-stage'
    }).subscribe((response) => {
      expect(response.reply).toBe('Samengevat');
    });

    const request = httpTestingController.expectOne('/social-api/social/v1/rooms/main-stage/assistant-replies');
    expect(request.request.method).toBe('POST');
    request.flush({ reply: 'Samengevat' });
  });
});
