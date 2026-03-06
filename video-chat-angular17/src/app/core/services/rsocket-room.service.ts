import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, Subject } from 'rxjs';
import RSocketWebSocketClient from 'rsocket-websocket-client';
import {
  BufferEncoders,
  encodeCompositeMetadata,
  encodeRoute,
  IdentitySerializer,
  MESSAGE_RSOCKET_COMPOSITE_METADATA,
  MESSAGE_RSOCKET_ROUTING,
  RSocketClient,
  toBuffer
} from 'rsocket-core';
import { ClientIdentity, ConnectionState, RoomEvent } from '../models/room.models';

interface BrokerState {
  url: string;
  socket: any;
}

@Injectable({
  providedIn: 'root'
})
export class RsocketRoomService {
  private static readonly RECONNECT_DELAY_MS = 1500;
  private static readonly CONNECT_TIMEOUT_MS = 10000;
  private static readonly CONNECT_TIMEOUT_RETRIES_SINGLE_URL = 2;
  private static readonly CONNECT_CLOSED_RETRIES_SINGLE_URL = 3;
  private static readonly REQUEST_N = 2147483647;

  private readonly stateSubject = new BehaviorSubject<ConnectionState>('DISCONNECTED');
  private readonly eventsSubject = new Subject<RoomEvent>();

  private brokerState: BrokerState | null = null;
  private currentRoomId = '';
  private identity: ClientIdentity | null = null;
  private brokerUrls: string[] = [];
  private reconnectTimer: any = null;
  private reconnecting = false;
  private reconnectEnabled = false;
  private streamSubscription: any = null;
  private streamToken = 0;
  private preferredBrokerUrl: string | null = null;
  private pendingConnectCancel: (() => void) | null = null;

  readonly state$: Observable<ConnectionState> = this.stateSubject.asObservable();
  readonly roomEvents$: Observable<RoomEvent> = this.eventsSubject.asObservable();

  async connect(identity: ClientIdentity, roomId: string, brokerUrls: string[], reconnectAttempt = false): Promise<void> {
    this.identity = identity;
    this.currentRoomId = roomId;
    this.reconnectEnabled = true;
    this.reconnecting = false;

    const uniqueUrls = this.prioritizePreferredBrokerUrl(this.normalizeBrokerUrls(brokerUrls));
    this.brokerUrls = uniqueUrls;

    if (!uniqueUrls.length) {
      this.stateSubject.next(reconnectAttempt ? 'RECONNECTING' : 'FAILED');
      throw new Error('Geen broker URL opgegeven.');
    }

    this.clearReconnectTimer();
    this.cancelPendingConnect();
    this.closeSocket();
    this.stateSubject.next(reconnectAttempt ? 'RECONNECTING' : 'CONNECTING');

    let latestError: Error | null = null;
    const allowSingleUrlTimeoutRetry = uniqueUrls.length === 1;
    for (const brokerUrl of uniqueUrls) {
      try {
        const socket = await this.connectBrokerUrlWithRetry(brokerUrl, allowSingleUrlTimeoutRetry);
        this.clearReconnectTimer();
        this.brokerState = { url: brokerUrl, socket };
        this.preferredBrokerUrl = brokerUrl;
        this.stateSubject.next('CONNECTED');
        this.reconnecting = false;
        this.subscribeToRoomEvents(socket);
        this.publish('ROOM_JOINED', { capabilities: ['multi-webcam', 'chat', 'heartbeat'] });
        return;
      } catch (error) {
        latestError = error as Error;
      }
    }

    this.stateSubject.next(reconnectAttempt ? 'RECONNECTING' : 'FAILED');
    throw latestError || new Error('Verbinding met brokers mislukt.');
  }

  private async connectBrokerUrlWithRetry(url: string, retryOnTransientFailure: boolean): Promise<any> {
    const maxAttempts = retryOnTransientFailure
      ? Math.max(
        RsocketRoomService.CONNECT_TIMEOUT_RETRIES_SINGLE_URL,
        RsocketRoomService.CONNECT_CLOSED_RETRIES_SINGLE_URL
      )
      : 1;

    let latestError: Error | null = null;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return await this.createConnectionWithTimeout(url);
      } catch (error) {
        latestError = error as Error;
        const timeoutError = this.isTimeoutError(latestError);
        const closedError = this.isConnectionClosedError(latestError);
        const canRetryTimeout = timeoutError && attempt < RsocketRoomService.CONNECT_TIMEOUT_RETRIES_SINGLE_URL;
        const canRetryClosed = closedError && attempt < RsocketRoomService.CONNECT_CLOSED_RETRIES_SINGLE_URL;

        if (!retryOnTransientFailure || (!canRetryTimeout && !canRetryClosed)) {
          throw latestError;
        }
      }
    }

    throw latestError || new Error('Verbinding met broker mislukt.');
  }

  disconnect(): void {
    this.reconnectEnabled = false;
    this.clearReconnectTimer();
    this.reconnecting = false;
    this.cancelPendingConnect();

    if (this.identity && this.currentRoomId) {
      this.publish('ROOM_LEFT', {});
    }

    this.closeSocket();
    this.currentRoomId = '';
    this.preferredBrokerUrl = null;
    this.stateSubject.next('DISCONNECTED');
  }

  publish(type: RoomEvent['type'], payload: any): void {
    const event = this.buildEvent(type, payload);
    if (!event || !this.brokerState || !this.brokerState.socket) {
      return;
    }

    this.brokerState.socket.fireAndForget({
      data: this.encodeData({
        action: 'ROOM_EVENT',
        route: 'room.events.publish',
        event
      }),
      metadata: this.encodeRouteMetadata('room.events.publish')
    });
  }

  publishWithAck(type: RoomEvent['type'], payload: any): Promise<boolean> {
    const event = this.buildEvent(type, payload);
    if (!event || !this.brokerState || !this.brokerState.socket) {
      return Promise.resolve(false);
    }

    const socketRef = this.brokerState.socket;
    return new Promise((resolve) => {
      socketRef.requestResponse({
        data: this.encodeData({
          action: 'ROOM_EVENT',
          route: 'room.events.publish',
          event
        }),
        metadata: this.encodeRouteMetadata('room.events.publish')
      }).subscribe({
        onComplete: () => {
          resolve(true);
        },
        onError: (error: any) => {
          console.warn('RSocket publish failed', error);
          if (this.brokerState && this.brokerState.socket === socketRef && this.reconnectEnabled) {
            this.stateSubject.next('RECONNECTING');
            this.scheduleReconnect();
          }
          resolve(false);
        },
        onSubscribe: () => {
          return;
        }
      });
    });
  }

  private subscribeToRoomEvents(socketRef: any): void {
    if (!this.brokerState || !this.identity) {
      return;
    }

    const streamToken = ++this.streamToken;
    socketRef.requestStream({
      data: this.encodeData({
        action: 'SUBSCRIBE_ROOM',
        route: 'room.events.stream',
        roomId: this.currentRoomId,
        clientId: this.identity.clientId
      }),
      metadata: this.encodeRouteMetadata('room.events.stream')
    }).subscribe({
      onSubscribe: (subscription: any) => {
        if (!this.isActiveStream(socketRef, streamToken)) {
          if (subscription && subscription.cancel) {
            subscription.cancel();
          }
          return;
        }

        this.streamSubscription = subscription;
        subscription.request(RsocketRoomService.REQUEST_N);
      },
      onNext: (payload: any) => {
        if (!this.isActiveStream(socketRef, streamToken)) {
          return;
        }

        if (!payload || !payload.data) {
          return;
        }

        const decoded = this.decodeData(payload.data);
        const event = (decoded?.event || decoded) as RoomEvent;
        if (!event || !event.type || !event.roomId) {
          return;
        }

        this.eventsSubject.next(event);
      },
      onError: (error: any) => {
        if (!this.isActiveStream(socketRef, streamToken)) {
          return;
        }

        this.streamSubscription = null;
        if (!this.reconnectEnabled) {
          return;
        }

        console.warn('RSocket stream error, reconnecting', error);
        this.stateSubject.next('RECONNECTING');
        this.scheduleReconnect();
      },
      onComplete: () => {
        if (!this.isActiveStream(socketRef, streamToken)) {
          return;
        }

        this.streamSubscription = null;
        if (!this.reconnectEnabled) {
          return;
        }

        this.stateSubject.next('RECONNECTING');
        this.scheduleReconnect();
      }
    });
  }

  private createConnectionWithTimeout(url: string): Promise<any> {
    return new Promise((resolve, reject) => {
      let timedOut = false;
      const timeoutId = setTimeout(() => {
        timedOut = true;
        this.cancelPendingConnect();
        reject(new Error('Broker connect timeout (' + RsocketRoomService.CONNECT_TIMEOUT_MS + 'ms): ' + url));
      }, RsocketRoomService.CONNECT_TIMEOUT_MS);

      this.createConnection(url).then(
        (socket) => {
          if (timedOut) {
            this.closeSocketRef(socket);
            return;
          }
          clearTimeout(timeoutId);
          resolve(socket);
        },
        (error) => {
          if (timedOut) {
            return;
          }
          clearTimeout(timeoutId);
          reject(error);
        }
      );
    });
  }

  private createConnection(url: string): Promise<any> {
    return new Promise((resolve, reject) => {
      const client = new RSocketClient({
        serializers: {
          data: IdentitySerializer,
          metadata: IdentitySerializer
        },
        setup: {
          keepAlive: 30000,
          lifetime: 120000,
          dataMimeType: 'application/json',
          metadataMimeType: MESSAGE_RSOCKET_COMPOSITE_METADATA.string
        },
        transport: new RSocketWebSocketClient({
          url,
          wsCreator: (connectionUrl: string) => {
            const websocket = new WebSocket(connectionUrl);
            websocket.binaryType = 'arraybuffer';
            return websocket;
          }
        }, BufferEncoders)
      });

      client.connect().subscribe({
        onComplete: (socket: any) => {
          this.pendingConnectCancel = null;
          resolve(socket);
        },
        onError: (error: Error) => {
          this.pendingConnectCancel = null;
          reject(error);
        },
        onSubscribe: (subscription: any) => {
          this.pendingConnectCancel = () => {
            this.pendingConnectCancel = null;
            if (subscription && subscription.cancel) {
              subscription.cancel();
            }
          };
        }
      });
    });
  }

  private cancelPendingConnect(): void {
    if (!this.pendingConnectCancel) {
      return;
    }

    try {
      this.pendingConnectCancel();
    } catch (error) {
      console.warn('Kon hangende connect-poging niet annuleren', error);
    } finally {
      this.pendingConnectCancel = null;
    }
  }

  private scheduleReconnect(): void {
    if (!this.reconnectEnabled || this.reconnecting || !this.identity || !this.currentRoomId || !this.brokerUrls.length) {
      return;
    }

    this.reconnecting = true;
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(async () => {
      try {
        await this.connect(this.identity as ClientIdentity, this.currentRoomId, this.brokerUrls, true);
      } catch (error) {
        console.error('Reconnect mislukt', error);
        this.reconnecting = false;
        this.scheduleReconnect();
      }
    }, RsocketRoomService.RECONNECT_DELAY_MS);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private normalizeBrokerUrls(brokerUrls: string[]): string[] {
    const normalized = brokerUrls
      .map((brokerUrl: string) => brokerUrl.trim())
      .filter((brokerUrl: string) => !!brokerUrl)
      .map((brokerUrl: string) => this.resolveBrokerUrl(brokerUrl))
      .filter((brokerUrl: string | null): brokerUrl is string => !!brokerUrl);

    return normalized.filter((brokerUrl: string, idx: number, all: string[]) => all.indexOf(brokerUrl) === idx);
  }

  private resolveBrokerUrl(brokerUrl: string): string | null {
    if (typeof window !== 'undefined' && brokerUrl.startsWith('/')) {
      const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      return `${wsProtocol}://${window.location.host}${brokerUrl}`;
    }

    try {
      const parsed = new URL(brokerUrl);
      if (parsed.protocol !== 'ws:' && parsed.protocol !== 'wss:') {
        return null;
      }
      return parsed.toString();
    } catch {
      console.warn('Ongeldige broker URL overgeslagen', brokerUrl);
      return null;
    }
  }

  private isTimeoutError(error: Error): boolean {
    return String(error && error.message ? error.message : error).toLowerCase().includes('timeout');
  }

  private isConnectionClosedError(error: Error): boolean {
    const message = String(error && error.message ? error.message : error).toLowerCase();
    return message.includes('connection closed')
      || message.includes('closed before setup')
      || message.includes('websocket is not open');
  }

  private prioritizePreferredBrokerUrl(brokerUrls: string[]): string[] {
    if (!this.preferredBrokerUrl) {
      return brokerUrls;
    }

    const preferredIndex = brokerUrls.indexOf(this.preferredBrokerUrl);
    if (preferredIndex <= 0) {
      return brokerUrls;
    }

    const preferred = brokerUrls[preferredIndex];
    const others = brokerUrls.filter((brokerUrl: string, idx: number) => idx !== preferredIndex);
    return [preferred, ...others];
  }

  private encodeRouteMetadata(route: string): Uint8Array {
    return encodeCompositeMetadata([[MESSAGE_RSOCKET_ROUTING, encodeRoute(route)]]);
  }

  private encodeData(data: any): Uint8Array {
    return toBuffer(JSON.stringify(data));
  }

  private decodeData(data: any): any {
    if (data == null) {
      return null;
    }

    try {
      if (typeof data === 'string') {
        return JSON.parse(data);
      }

      if (typeof data === 'object' && !ArrayBuffer.isView(data) && !(data instanceof ArrayBuffer)) {
        return data;
      }

      return JSON.parse(toBuffer(data).toString('utf8'));
    } catch (error) {
      console.warn('RSocket payload decode failed', error);
      return null;
    }
  }

  private buildEvent(type: RoomEvent['type'], payload: any): RoomEvent | null {
    if (!this.identity || !this.currentRoomId) {
      return null;
    }

    return {
      type,
      roomId: this.currentRoomId,
      senderId: this.identity.clientId,
      senderName: this.identity.displayName,
      sentAt: new Date().toISOString(),
      payload
    };
  }

  private closeSocket(): void {
    this.cancelPendingConnect();
    this.streamToken++;
    if (this.streamSubscription && this.streamSubscription.cancel) {
      this.streamSubscription.cancel();
    }
    this.streamSubscription = null;

    if (this.brokerState && this.brokerState.socket && this.brokerState.socket.close) {
      this.brokerState.socket.close();
    }

    if (this.brokerState && this.brokerState.socket && this.brokerState.socket.connection && this.brokerState.socket.connection.close) {
      this.brokerState.socket.connection.close();
    }

    this.brokerState = null;
  }

  private closeSocketRef(socket: any): void {
    if (!socket) {
      return;
    }

    if (socket.close) {
      socket.close();
    }

    if (socket.connection && socket.connection.close) {
      socket.connection.close();
    }
  }

  private isActiveStream(socketRef: any, streamToken: number): boolean {
    return !!this.brokerState
      && this.brokerState.socket === socketRef
      && this.streamToken === streamToken;
  }
}
