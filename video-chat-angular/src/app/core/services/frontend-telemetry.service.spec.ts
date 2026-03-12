// @vitest-environment jsdom
import '@angular/compiler';
import { NavigationEnd } from '@angular/router';
import { Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FrontendTelemetryService } from './frontend-telemetry.service';

describe('FrontendTelemetryService', () => {
  const createService = () => {
    const routerEvents = new Subject<unknown>();
    const http = {
      post: vi.fn().mockReturnValue(of(null))
    };
    const router = {
      events: routerEvents.asObservable(),
      url: '/login'
    };
    const authDebugEvents = new Subject<string>();
    const auth = {
      debugEvents$: authDebugEvents.asObservable(),
      isAuthenticated: false,
      oauthDebugEvents: [] as string[]
    };

    const service = new FrontendTelemetryService(http as any, router as any, auth as any);

    return { service, http, router, routerEvents, auth, authDebugEvents };
  };

  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    window.sessionStorage.clear();
  });

  it('tracks only low-volume room lifecycle events', () => {
    const { service, http, auth } = createService();
    auth.isAuthenticated = true;

    service.recordRoomEvent('received', 'WEBRTC_SIGNAL', 'room-1');
    service.recordRoomEvent('sent', 'CHAT_MESSAGE', 'room-1');
    service.recordRoomEvent('received', 'CAMERA_STATUS', 'room-1');
    service.recordRoomEvent('sent', 'ROOM_JOINED', 'room-1');
    service.recordRoomEvent('received', 'CAMERA_PUBLISHED', 'room-1');

    expect(http.post).toHaveBeenCalledTimes(2);
    expect(http.post).toHaveBeenNthCalledWith(
      1,
      '/social-api/social/v1/telemetry',
      expect.objectContaining({
        eventType: 'room_event_sent',
        roomId: 'room-1',
        details: { eventType: 'ROOM_JOINED' }
      })
    );
    expect(http.post).toHaveBeenNthCalledWith(
      2,
      '/social-api/social/v1/telemetry',
      expect.objectContaining({
        eventType: 'room_event_received',
        roomId: 'room-1',
        details: { eventType: 'CAMERA_PUBLISHED' }
      })
    );
  });

  it('posts route views only when authenticated', () => {
    const { http, auth, routerEvents } = createService();

    routerEvents.next(new NavigationEnd(1, '/social', '/social'));
    expect(http.post).not.toHaveBeenCalled();

    auth.isAuthenticated = true;
    routerEvents.next(new NavigationEnd(2, '/social', '/social'));

    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post).toHaveBeenCalledWith(
      '/social-api/social/v1/telemetry',
      expect.objectContaining({
        eventType: 'route_view',
        route: '/social'
      })
    );
  });
});
