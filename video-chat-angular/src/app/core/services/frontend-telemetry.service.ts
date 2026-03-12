import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { ConnectionState, RoomEventType } from '../models/room.models';
import { AuthService } from './auth.service';

interface FrontendTelemetryPayload {
  eventType: string;
  route: string;
  connectionState?: string | null;
  roomId?: string | null;
  sessionId: string;
  occurredAt: string;
  details: Record<string, unknown>;
}

@Injectable({
  providedIn: 'root'
})
export class FrontendTelemetryService {
  private static readonly SESSION_STORAGE_KEY = 'videochat:telemetry-session-id';
  private static readonly TRACKED_ROOM_EVENTS = new Set<RoomEventType>([
    'ROOM_JOINED',
    'ROOM_LEFT',
    'CAMERA_PUBLISHED',
    'CAMERA_REMOVED'
  ]);

  private initialized = false;
  private lastConnectionState: ConnectionState | null = null;
  private readonly sessionId = this.resolveSessionId();

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
    private readonly auth: AuthService
  ) {
    this.initialize();
  }

  recordBrokerState(state: ConnectionState, roomId?: string): void {
    if (this.lastConnectionState === state) {
      return;
    }

    this.lastConnectionState = state;
    this.postEvent('broker_state', {
      connectionState: state,
      roomId,
      details: {
        authenticated: this.auth.isAuthenticated
      }
    });
  }

  recordRoomEvent(direction: 'sent' | 'received', eventType: RoomEventType, roomId: string): void {
    if (!FrontendTelemetryService.TRACKED_ROOM_EVENTS.has(eventType)) {
      return;
    }

    this.postEvent(`room_event_${direction}`, {
      roomId,
      connectionState: this.lastConnectionState,
      details: {
        eventType
      }
    });
  }

  private initialize(): void {
    if (this.initialized) {
      return;
    }

    this.initialized = true;
    this.auth.debugEvents$
      .subscribe((event) => this.handleAuthDebugEvent(event));

    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => {
        this.postEvent('route_view', {
          route: event.urlAfterRedirects
        });
      });

    if (this.auth.isAuthenticated) {
      if (this.auth.oauthDebugEvents.includes('stored-token-restored')) {
        this.postEvent('session_restored');
      } else {
        this.postEvent('session_active');
      }
      this.postEvent('route_view');
    }
  }

  private handleAuthDebugEvent(event: string): void {
    if (event === 'stored-token-restored') {
      this.postEvent('session_restored');
      return;
    }

    if (event.startsWith('credential-received')) {
      const selectBy = event.split(':')[1] ?? 'unknown';
      this.postEvent('login_success', {
        details: {
          selectBy
        }
      });
      return;
    }

    if (event === 'logout') {
      this.postEvent('logout', {
        details: {
          previousConnectionState: this.lastConnectionState
        }
      });
    }
  }

  private postEvent(
    eventType: string,
    options: {
      route?: string;
      connectionState?: ConnectionState | null;
      roomId?: string | null;
      details?: Record<string, unknown>;
    } = {}
  ): void {
    if (!this.auth.isAuthenticated) {
      return;
    }

    const payload: FrontendTelemetryPayload = {
      eventType,
      route: this.normalizeRoute(options.route ?? this.router.url),
      connectionState: options.connectionState ?? this.lastConnectionState,
      roomId: options.roomId ?? null,
      sessionId: this.sessionId,
      occurredAt: new Date().toISOString(),
      details: options.details ?? {}
    };

    this.http.post('/social-api/social/v1/telemetry', payload).subscribe({
      error: () => {
        return;
      }
    });
  }

  private resolveSessionId(): string {
    if (typeof window === 'undefined') {
      return 'server';
    }

    const stored = window.sessionStorage.getItem(FrontendTelemetryService.SESSION_STORAGE_KEY)?.trim();
    if (stored) {
      return stored;
    }

    const created = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `session-${Date.now()}`;
    window.sessionStorage.setItem(FrontendTelemetryService.SESSION_STORAGE_KEY, created);
    return created;
  }

  private normalizeRoute(route: string): string {
    if (!route || !route.trim()) {
      return '/';
    }

    return route.startsWith('/') ? route : `/${route}`;
  }
}
