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
import { ClientIdentity, ConnectionState, RoomEvent, RoomEventPayloadMap, RoomEventType } from '../models/room.models';

type TimerHandle = ReturnType<typeof setTimeout>;

interface BrokerRequest {
  data: Uint8Array;
  metadata: Uint8Array;
}

interface BrokerSubscription {
  request?(count: number): void;
  cancel?(): void;
}

interface BrokerSocket {
  fireAndForget?(request: BrokerRequest): void;
  requestResponse?(request: BrokerRequest): {
    subscribe(observer: BrokerResponseObserver): void;
  };
  requestStream?(request: BrokerRequest): {
    subscribe(observer: BrokerStreamObserver): void;
  };
  close?(): void;
  connection?: {
    close?(): void;
  };
}

interface BrokerResponseObserver {
  onComplete(): void;
  onError(error: unknown): void;
  onSubscribe(subscription: BrokerSubscription): void;
}

interface BrokerStreamObserver {
  onSubscribe(subscription: BrokerSubscription): void;
  onNext(payload: { data?: unknown }): void;
  onError(error: unknown): void;
  onComplete(): void;
}

interface BrokerState {
  url: string;
  socket: BrokerSocket;
}

interface StreamSubscriptionRequest {
  action: 'SUBSCRIBE_ROOM';
  route: string;
  roomId: string;
  clientId: string;
  authToken: string;
}

interface RoomEventRequest<TType extends RoomEventType> {
  action: 'ROOM_EVENT';
  route: string;
  authToken: string;
  event: RoomEvent<TType>;
}

interface BrokerAuthorizationRequest {
  action: 'AUTHORIZE_ROOM';
  route: string;
  roomId: string;
  clientId: string;
  authToken: string;
}

interface RoomEventEnvelope {
  event?: RoomEvent;
}

@Injectable({
  providedIn: 'root'
})
/**
 * Owns the broker connection lifecycle for the Angular client.
 *
 * Responsibilities:
 * - open and close the RSocket-over-WebSocket connection
 * - publish room events
 * - keep a room event stream alive with reconnect logic
 * - translate browser-relative broker URLs into ws/wss endpoints
 */
export class RsocketRoomService {
  private static readonly ROUTES = {
    authorize: 'room.events.authorize',
    publish: 'room.events.publish',
    stream: 'room.events.stream'
  } as const;

  private static readonly RECONNECT_DELAY_MS = 1500;
  private static readonly CONNECT_TIMEOUT_MS = 10000;
  private static readonly CONNECT_TIMEOUT_RETRIES_SINGLE_URL = 2;
  private static readonly CONNECT_CLOSED_RETRIES_SINGLE_URL = 3;
  private static readonly REQUEST_N = 2147483647;
  private static readonly JOIN_CAPABILITIES = ['multi-webcam', 'chat', 'heartbeat'];

  private readonly stateSubject = new BehaviorSubject<ConnectionState>('DISCONNECTED');
  private readonly eventsSubject = new Subject<RoomEvent>();

  private brokerState: BrokerState | null = null;
  private currentRoomId = '';
  private identity: ClientIdentity | null = null;
  private authToken = '';
  /**
   * Last normalized broker URL list supplied by the UI. Reconnects reuse this exact ordered list.
   */
  private brokerUrls: string[] = [];
  private reconnectTimer: TimerHandle | null = null;
  private reconnecting = false;
  private reconnectEnabled = false;
  private streamSubscription: BrokerSubscription | null = null;
  /**
   * Incremented for each new room stream subscription so late callbacks from old streams can be ignored safely.
   */
  private streamToken = 0;
  /**
   * Remember the last working broker endpoint so reconnect attempts try that one first.
   */
  private preferredBrokerUrl: string | null = null;
  private pendingConnectCancel: (() => void) | null = null;

  readonly state$: Observable<ConnectionState> = this.stateSubject.asObservable();
  readonly roomEvents$: Observable<RoomEvent> = this.eventsSubject.asObservable();

  /**
   * Opens a broker connection and subscribes the client to room events.
   */
  async connect(
    identity: ClientIdentity,
    roomId: string,
    brokerUrls: string[],
    authToken: string,
    reconnectAttempt = false
  ): Promise<void> {
    this.identity = identity;
    this.currentRoomId = roomId;
    this.authToken = authToken.trim();
    this.reconnectEnabled = true;
    this.reconnecting = false;
    this.brokerUrls = this.prioritizePreferredBrokerUrl(this.normalizeBrokerUrls(brokerUrls));

    if (!this.brokerUrls.length) {
      this.transitionTo(reconnectAttempt ? 'RECONNECTING' : 'FAILED');
      throw new Error('Geen broker URL opgegeven.');
    }

    this.prepareConnectionAttempt(reconnectAttempt);

    let latestError: Error | null = null;
    const retrySingleUrl = this.brokerUrls.length === 1;

    for (const brokerUrl of this.brokerUrls) {
      try {
        const socket = await this.connectBrokerUrlWithRetry(brokerUrl, retrySingleUrl);
        await this.authorizeConnection(socket, identity.clientId);
        this.activateBrokerConnection(brokerUrl, socket);
        return;
      } catch (error) {
        latestError = error as Error;
      }
    }

    this.transitionTo(reconnectAttempt ? 'RECONNECTING' : 'FAILED');
    throw latestError || new Error('Verbinding met brokers mislukt.');
  }

  /**
   * Disconnects the client intentionally. This disables reconnects and attempts a best-effort ROOM_LEFT publish.
   */
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
    this.authToken = '';
    this.preferredBrokerUrl = null;
    this.transitionTo('DISCONNECTED');
  }

  /**
   * Publishes an event without waiting for broker acknowledgement.
   */
  publish<TType extends RoomEventType>(type: TType, payload: RoomEventPayloadMap[TType]): void {
    const event = this.buildEvent(type, payload);
    const socket = this.brokerState?.socket;
    if (!event || !socket?.fireAndForget) {
      return;
    }

    socket.fireAndForget(this.createEventRequest(event));
  }

  /**
   * Publishes an event and resolves whether the broker acknowledged it.
   */
  publishWithAck<TType extends RoomEventType>(type: TType, payload: RoomEventPayloadMap[TType]): Promise<boolean> {
    const event = this.buildEvent(type, payload);
    const socket = this.brokerState?.socket;
    if (!event || !socket?.requestResponse) {
      return Promise.resolve(false);
    }

    return new Promise((resolve) => {
      socket.requestResponse!(this.createEventRequest(event)).subscribe({
        onComplete: () => {
          resolve(true);
        },
        onError: (error: unknown) => {
          console.warn('RSocket publish failed', error);
          if (this.isCurrentSocket(socket) && this.reconnectEnabled) {
            this.transitionTo('RECONNECTING');
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

  /**
   * Clears the previous connection state so a new connect attempt starts from a known baseline.
   */
  private prepareConnectionAttempt(reconnectAttempt: boolean): void {
    this.clearReconnectTimer();
    this.cancelPendingConnect();
    this.closeSocket();
    this.transitionTo(reconnectAttempt ? 'RECONNECTING' : 'CONNECTING');
  }

  /**
   * Marks a broker connection as active and starts the room stream immediately.
   */
  private activateBrokerConnection(brokerUrl: string, socket: BrokerSocket): void {
    this.clearReconnectTimer();
    this.brokerState = { url: brokerUrl, socket };
    this.preferredBrokerUrl = brokerUrl;
    this.reconnecting = false;
    this.transitionTo('CONNECTED');
    this.subscribeToRoomEvents(socket);
    this.publish('ROOM_JOINED', { capabilities: [...RsocketRoomService.JOIN_CAPABILITIES] });
  }

  /**
   * Retries a single broker URL for the transient failures that are known to happen during browser connect/setup.
   */
  private async connectBrokerUrlWithRetry(url: string, retryOnTransientFailure: boolean): Promise<BrokerSocket> {
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
        if (!retryOnTransientFailure || !this.canRetryConnection(latestError, attempt)) {
          throw latestError;
        }
      }
    }

    throw latestError || new Error('Verbinding met broker mislukt.');
  }

  /**
   * Only retry failures that are likely transient in practice. Other errors should fail fast.
   */
  private canRetryConnection(error: Error, attempt: number): boolean {
    const timeoutError = this.isTimeoutError(error);
    const closedError = this.isConnectionClosedError(error);

    return (timeoutError && attempt < RsocketRoomService.CONNECT_TIMEOUT_RETRIES_SINGLE_URL)
      || (closedError && attempt < RsocketRoomService.CONNECT_CLOSED_RETRIES_SINGLE_URL);
  }

  /**
   * Subscribes to the broker room stream and guards callbacks with the current stream token.
   */
  private subscribeToRoomEvents(socket: BrokerSocket): void {
    if (!this.identity || !socket.requestStream) {
      return;
    }

    const streamToken = ++this.streamToken;
    socket.requestStream(this.createStreamRequest(this.identity.clientId)).subscribe({
      onSubscribe: (subscription: BrokerSubscription) => {
        if (!this.isActiveStream(socket, streamToken)) {
          subscription.cancel?.();
          return;
        }

        this.streamSubscription = subscription;
        subscription.request?.(RsocketRoomService.REQUEST_N);
      },
      onNext: (payload: { data?: unknown }) => {
        if (!this.isActiveStream(socket, streamToken) || !payload.data) {
          return;
        }

        const decoded = this.decodeData<RoomEvent | RoomEventEnvelope>(payload.data);
        const event = this.extractDecodedEvent(decoded);
        if (!event) {
          return;
        }

        this.eventsSubject.next(event);
      },
      onError: (error: unknown) => {
        this.handleStreamInterruption(socket, streamToken, error);
      },
      onComplete: () => {
        this.handleStreamInterruption(socket, streamToken);
      }
    });
  }

  /**
   * Handles broker stream loss for the active subscription and schedules a reconnect when allowed.
   */
  private handleStreamInterruption(socket: BrokerSocket, streamToken: number, error?: unknown): void {
    if (!this.isActiveStream(socket, streamToken)) {
      return;
    }

    this.streamSubscription = null;
    if (!this.reconnectEnabled) {
      return;
    }

    if (error) {
      if (this.isAuthorizationError(error)) {
        console.warn('RSocket autorisatie geweigerd', error);
        this.reconnectEnabled = false;
        this.transitionTo('FAILED');
        return;
      }

      console.warn('RSocket stream error, reconnecting', error);
    }

    this.transitionTo('RECONNECTING');
    this.scheduleReconnect();
  }

  /**
   * Wraps the underlying RSocket connect call with an explicit timeout because browser websocket
   * failures can otherwise hang for too long before surfacing to the UI.
   */
  private createConnectionWithTimeout(url: string): Promise<BrokerSocket> {
    return new Promise((resolve, reject) => {
      let timedOut = false;
      const timeoutId = setTimeout(() => {
        timedOut = true;
        this.cancelPendingConnect();
        reject(new Error(`Broker connect timeout (${RsocketRoomService.CONNECT_TIMEOUT_MS}ms): ${url}`));
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

  /**
   * Creates a single RSocket-over-WebSocket connection.
   */
  private createConnection(url: string): Promise<BrokerSocket> {
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
            const websocket = new WebSocket(connectionUrl, ['rsocket']);
            websocket.binaryType = 'arraybuffer';
            const nativeSend = websocket.send.bind(websocket);
            websocket.send = ((data: string | ArrayBufferLike | Blob | ArrayBufferView) => {
              if (ArrayBuffer.isView(data)) {
                // Safari is stricter than Chromium about reused/shared backing buffers during the
                // first setup frames. Copy the bytes into a fresh ArrayBuffer before sending.
                const view = data as ArrayBufferView;
                const copy = new Uint8Array(view.byteLength);
                copy.set(new Uint8Array(view.buffer, view.byteOffset, view.byteLength));
                nativeSend(copy.buffer);
                return;
              }

              if (data instanceof ArrayBuffer) {
                nativeSend(data.slice(0));
                return;
              }

              nativeSend(data as string | ArrayBufferLike | Blob);
            }) as typeof websocket.send;
            return websocket;
          }
        }, BufferEncoders)
      });

      client.connect().subscribe({
        onComplete: (socket: BrokerSocket) => {
          this.pendingConnectCancel = null;
          resolve(socket);
        },
        onError: (error: Error) => {
          this.pendingConnectCancel = null;
          reject(error);
        },
        onSubscribe: (subscription: BrokerSubscription) => {
          this.pendingConnectCancel = () => {
            this.pendingConnectCancel = null;
            subscription.cancel?.();
          };
        }
      });
    });
  }

  /**
   * Fails fast when the supplied operator token is not accepted by the broker.
   */
  private authorizeConnection(socket: BrokerSocket, clientId: string): Promise<void> {
    if (!socket.requestResponse) {
      return Promise.reject(new Error('Broker ondersteunt geen autorisatieverzoek.'));
    }

    const payload: BrokerAuthorizationRequest = {
      action: 'AUTHORIZE_ROOM',
      route: RsocketRoomService.ROUTES.authorize,
      roomId: this.currentRoomId,
      clientId,
      authToken: this.authToken
    };

    return new Promise((resolve, reject) => {
      socket.requestResponse!(this.createRouteRequest(RsocketRoomService.ROUTES.authorize, payload)).subscribe({
        onComplete: () => {
          resolve();
        },
        onError: (error: unknown) => {
          reject(error);
        },
        onSubscribe: () => {
          return;
        }
      });
    });
  }

  /**
   * Aborts an in-flight connect attempt when the UI disconnects or the timeout fires first.
   */
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

  /**
   * Schedules a reconnect attempt if reconnects are enabled and no attempt is already queued.
   */
  private scheduleReconnect(): void {
    if (!this.canReconnect()) {
      return;
    }

    this.reconnecting = true;
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(async () => {
      if (!this.identity) {
        return;
      }

      try {
        await this.connect(this.identity, this.currentRoomId, this.brokerUrls, this.authToken, true);
      } catch (error) {
        console.error('Reconnect mislukt', error);
        this.reconnecting = false;
        this.scheduleReconnect();
      }
    }, RsocketRoomService.RECONNECT_DELAY_MS);
  }

  private canReconnect(): boolean {
    return this.reconnectEnabled
      && !this.reconnecting
      && !!this.identity
      && !!this.currentRoomId
      && this.brokerUrls.length > 0;
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  /**
   * Normalizes, resolves, and de-duplicates the broker URL list provided by the UI.
   */
  private normalizeBrokerUrls(brokerUrls: string[]): string[] {
    const normalized = brokerUrls
      .map((brokerUrl) => brokerUrl.trim())
      .filter(Boolean)
      .map((brokerUrl) => this.resolveBrokerUrl(brokerUrl))
      .filter((brokerUrl): brokerUrl is string => !!brokerUrl);

    return normalized.filter((brokerUrl, index, all) => all.indexOf(brokerUrl) === index);
  }

  /**
   * Resolves same-origin websocket paths such as "/rsocket" into full ws/wss URLs.
   */
  private resolveBrokerUrl(brokerUrl: string): string | null {
    if (typeof window !== 'undefined' && brokerUrl.startsWith('/')) {
      const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
      return `${wsProtocol}://${window.location.host}${brokerUrl}`;
    }

    try {
      const parsed = new URL(brokerUrl);
      return parsed.protocol === 'ws:' || parsed.protocol === 'wss:' ? parsed.toString() : null;
    } catch {
      console.warn('Ongeldige broker URL overgeslagen', brokerUrl);
      return null;
    }
  }

  private isTimeoutError(error: Error): boolean {
    return this.errorMessage(error).includes('timeout');
  }

  private isConnectionClosedError(error: Error): boolean {
    const message = this.errorMessage(error);
    return message.includes('connection closed')
      || message.includes('closed before setup')
      || message.includes('websocket is not open');
  }

  private errorMessage(error: unknown): string {
    return String(error && typeof error === 'object' && 'message' in error ? (error as Error).message : error)
      .toLowerCase();
  }

  private isAuthorizationError(error: unknown): boolean {
    const message = this.errorMessage(error);
    return message.includes('unauthorized') || message.includes('forbidden') || message.includes('access denied');
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
    const others = brokerUrls.filter((_, index) => index !== preferredIndex);
    return [preferred, ...others];
  }

  /**
   * Creates the room stream subscription payload expected by the broker.
   */
  private createStreamRequest(clientId: string): BrokerRequest {
    const payload: StreamSubscriptionRequest = {
      action: 'SUBSCRIBE_ROOM',
      route: RsocketRoomService.ROUTES.stream,
      roomId: this.currentRoomId,
      clientId,
      authToken: this.authToken
    };

    return this.createRouteRequest(RsocketRoomService.ROUTES.stream, payload);
  }

  /**
   * Creates the publish payload expected by the broker.
   */
  private createEventRequest<TType extends RoomEventType>(event: RoomEvent<TType>): BrokerRequest {
    const payload: RoomEventRequest<TType> = {
      action: 'ROOM_EVENT',
      route: RsocketRoomService.ROUTES.publish,
      authToken: this.authToken,
      event
    };

    return this.createRouteRequest(RsocketRoomService.ROUTES.publish, payload);
  }

  private createRouteRequest(route: string, data: unknown): BrokerRequest {
    return {
      data: this.encodeData(data),
      metadata: this.encodeRouteMetadata(route)
    };
  }

  private encodeRouteMetadata(route: string): Uint8Array {
    return encodeCompositeMetadata([[MESSAGE_RSOCKET_ROUTING, encodeRoute(route)]]);
  }

  private encodeData(data: unknown): Uint8Array {
    return toBuffer(JSON.stringify(data));
  }

  private decodeData<T>(data: unknown): T | null {
    if (data == null) {
      return null;
    }

    try {
      if (typeof data === 'string') {
        return JSON.parse(data) as T;
      }

      if (typeof data === 'object' && !ArrayBuffer.isView(data) && !(data instanceof ArrayBuffer)) {
        return data as T;
      }

      return JSON.parse(toBuffer(data as Uint8Array).toString('utf8')) as T;
    } catch (error) {
      console.warn('RSocket payload decode failed', error);
      return null;
    }
  }

  private extractDecodedEvent(decoded: RoomEvent | RoomEventEnvelope | null): RoomEvent | null {
    if (!decoded) {
      return null;
    }

    const event = this.isRoomEvent(decoded) ? decoded : decoded.event ?? null;
    return this.isRoomEvent(event) ? event : null;
  }

  private buildEvent<TType extends RoomEventType>(
    type: TType,
    payload: RoomEventPayloadMap[TType]
  ): RoomEvent<TType> | null {
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
    this.streamSubscription?.cancel?.();
    this.streamSubscription = null;
    this.closeSocketRef(this.brokerState?.socket ?? null);
    this.brokerState = null;
  }

  private closeSocketRef(socket: BrokerSocket | null): void {
    if (!socket) {
      return;
    }

    socket.close?.();
    socket.connection?.close?.();
  }

  private isCurrentSocket(socket: BrokerSocket): boolean {
    return this.brokerState?.socket === socket;
  }

  private isActiveStream(socket: BrokerSocket, streamToken: number): boolean {
    return this.brokerState?.socket === socket && this.streamToken === streamToken;
  }

  private transitionTo(state: ConnectionState): void {
    this.stateSubject.next(state);
  }

  private isRoomEvent(value: unknown): value is RoomEvent {
    return !!value
      && typeof value === 'object'
      && 'type' in value
      && 'roomId' in value
      && 'senderId' in value
      && 'senderName' in value
      && 'sentAt' in value;
  }
}
