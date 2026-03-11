import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, NgZone, OnDestroy, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { environment } from '../../../environments/environment';
import { CameraGridComponent } from '../../components/camera-grid/camera-grid.component';
import { ChatPanelComponent } from '../../components/chat-panel/chat-panel.component';
import {
  CameraDevice,
  CameraFeed,
  CameraPublishedPayload,
  ChatMessage,
  ClientIdentity,
  ConnectionState,
  RoomEvent,
  WebrtcSignalPayload
} from '../../core/models/room.models';
import { AuthService } from '../../core/services/auth.service';
import { MediaDeviceService } from '../../core/services/media-device.service';
import { RoomSessionService } from '../../core/services/room-session.service';
import { RsocketRoomService } from '../../core/services/rsocket-room.service';
import { WebrtcMeshService } from '../../core/services/webrtc-mesh.service';

@Component({
  selector: 'app-live-room',
  templateUrl: './live-room.component.html',
  styleUrl: './live-room.component.scss',
  standalone: true,
  imports: [CommonModule, FormsModule, CameraGridComponent, ChatPanelComponent]
})
/**
 * Main room page that orchestrates device management, broker connectivity, WebRTC setup, and UI state.
 */
export class LiveRoomComponent implements OnInit, OnDestroy {
  private static readonly DEFAULT_ROOM_ID = 'main-stage';
  private static readonly DEFAULT_DISPLAY_NAME_PREFIX = 'PulseRoom';
  private static readonly ACCESS_TOKEN_STORAGE_KEY = 'pulse-room:broker-access-token';
  private static readonly RENEGOTIATION_RECOVERY_DELAY_MS = 1500;

  roomId = 'main-stage';
  displayName = '';
  brokerEndpoints = environment.brokerWebsocketUrls.join(', ');
  accessToken = this.readStoredAccessToken();

  devices: CameraDevice[] = [];
  selectedDeviceSelection = '';

  feeds: CameraFeed[] = [];
  messages: ChatMessage[] = [];
  connectionState: ConnectionState = 'DISCONNECTED';
  uiError = '';
  uiInfo = '';
  isConnecting = false;
  isAddingCamera = false;

  private currentIdentity: ClientIdentity | null = null;
  private readonly destroyRef = inject(DestroyRef);
  private readonly ngZone = inject(NgZone);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);
  /**
   * Browser media device callbacks can arrive outside Angular change detection, so always re-enter
   * the zone before updating component state.
   */
  private readonly deviceChangeListener = () => {
    void this.ngZone.run(() => this.refreshDevices());
  };
  private readonly renegotiationRecoveryTimers = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(
    private auth: AuthService,
    private mediaDeviceService: MediaDeviceService,
    private roomSessionService: RoomSessionService,
    private rsocketRoomService: RsocketRoomService,
    private webrtcMeshService: WebrtcMeshService
  ) {
    this.roomId = LiveRoomComponent.DEFAULT_ROOM_ID;
    this.displayName = this.resolvePreferredDisplayName();
  }

  /**
   * Template convenience flag for connection-sensitive UI actions.
   */
  get connected(): boolean {
    return this.connectionState === 'CONNECTED';
  }

  get customBrokerUrlsLocked(): boolean {
    return this.shouldPreferSameOriginProxy();
  }

  get stageSubtitle(): string {
    return this.connected
      ? `${this.displayName} is live on the shared stage.`
      : 'Joining the shared stage and preparing your session.';
  }

  get addCameraLabel(): string {
    if (this.isAddingCamera) {
      return 'Adding...';
    }

    if (this.isConnecting && !this.connected) {
      return 'Joining stage...';
    }

    return 'Add webcam';
  }

  /**
   * Wires the component to the session/broker observables and starts local device discovery.
   */
  ngOnInit(): void {
    this.prefillBrokerToken();
    this.displayName = this.resolvePreferredDisplayName();

    this.roomSessionService.feeds$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((feeds: CameraFeed[]) => {
        this.runInZone(() => {
          this.feeds = feeds;
        });
      });

    this.roomSessionService.messages$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((messages: ChatMessage[]) => {
        this.runInZone(() => {
          this.messages = messages;
        });
      });

    this.rsocketRoomService.state$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((state: ConnectionState) => {
        this.runInZone(() => {
          this.connectionState = state;
          this.applyConnectionState(state);
        });
      });

    this.rsocketRoomService.roomEvents$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event: RoomEvent) => {
        void this.runInZone(() =>
          this.handleRoomEvent(event).catch((error) => {
            console.error('Room event handling failed', error);
            if (this.auth.isDevelopmentMode) {
              this.setError(`Room event handling failed: ${this.extractErrorMessage(error)}`);
            }
          })
        );
      });

    this.auth.authState$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((isAuthenticated: boolean) => {
        this.runInZone(() => {
          this.prefillBrokerToken();
          this.displayName = this.resolvePreferredDisplayName();
        });

        if (isAuthenticated) {
          void this.ensureRoomConnection();
        }
      });

    this.registerDeviceChangeListener();
    void this.refreshDevices();
  }

  /**
   * Releases browser resources and broker connections when the page is destroyed.
   */
  ngOnDestroy(): void {
    this.unregisterDeviceChangeListener();
    this.clearRenegotiationRecoveryTimers();
    this.stopAllLocalStreams();
    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
  }

  /**
   * Creates a room identity, connects to a broker endpoint, and prepares WebRTC callbacks.
   */
  async connectRoom(): Promise<void> {
    this.clearStatus();
    this.isConnecting = true;

    const authToken = this.resolveBrokerToken();
    if (!authToken) {
      this.setError('Vul een broker token in of log eerst in voordat je verbindt.');
      this.isConnecting = false;
      return;
    }

    const brokerUrls = this.buildBrokerUrls();
    if (!brokerUrls.length) {
      this.setError('Vul minstens 1 broker URL in.');
      this.isConnecting = false;
      return;
    }

    const identity = this.createIdentity();
    this.runInZone(() => {
      this.currentIdentity = identity;
      this.initializeWebrtcMesh(identity);
    });

    try {
      await this.rsocketRoomService.connect(identity, this.roomId.trim(), brokerUrls, authToken);
      this.runInZone(() => {
        this.connectionState = 'CONNECTED';
        this.applyConnectionState('CONNECTED');
        this.isConnecting = false;
        this.accessToken = authToken;
        this.storeAccessToken(authToken);
      });
    } catch (error) {
      this.runInZone(() => {
        this.currentIdentity = null;
        this.webrtcMeshService.dispose();
        console.error('Connectie mislukt', error);
        const message = this.extractErrorMessage(error);
        if (message.includes('SecurityError') || message.includes('insecure')) {
          this.setError('Connectie geblokkeerd door browser security. Gebruik op HTTPS alleen wss:// of /rsocket.');
        } else if (message.toLowerCase().includes('unauthorized') || message.toLowerCase().includes('forbidden')) {
          const detailedMessage = this.auth.isDevelopmentMode
            ? `Toegang geweigerd. ${message}`
            : 'Toegang geweigerd. Controleer het broker token of log opnieuw in voor een nieuw ID token.';
          this.setError(detailedMessage);
        } else if (message.includes('localhost') || message.includes('127.0.0.1')) {
          this.setError('Connectie naar localhost mislukt. Gebruik /rsocket of wss://<LAN-IP>/rsocket.');
        } else {
          this.setError(`Connectie mislukt: ${message}`);
        }
      });
    } finally {
      this.runInZone(() => {
        this.isConnecting = false;
      });
    }
  }

  /**
   * Disconnects from the room and clears all local session state.
   */
  disconnectRoom(): void {
    this.stopAllLocalStreams();
    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
    this.currentIdentity = null;
    this.roomSessionService.reset();
    this.uiInfo = 'Verbinding verbroken.';
  }

  /**
   * Refreshes the locally available camera list and keeps the current selection valid when possible.
   */
  async refreshDevices(): Promise<void> {
    this.clearStatus();
    try {
      const devices = await this.mediaDeviceService.listVideoInputs();
      this.runInZone(() => {
        this.devices = devices;
        this.syncSelectedDeviceSelection();

        if (!this.devices.length) {
          const insecureContext = typeof window !== 'undefined' && !window.isSecureContext;
          if (insecureContext) {
            this.setError('Geen camera beschikbaar op een onveilige verbinding. Open de app via HTTPS (of localhost).');
          } else {
            this.setError('Geen camera gevonden. Check browser permissions en sluit andere camera-apps.');
          }
        }
      });
    } catch (error) {
      this.runInZone(() => {
        console.error('Device refresh mislukt', error);
        this.setError('Kon camera-lijst niet laden.');
      });
    }
  }

  /**
   * Starts the selected camera locally, publishes it to the session, and informs the broker.
   */
  async addCamera(): Promise<void> {
    this.clearStatus();

    if (!this.connected || !this.currentIdentity) {
      this.setError('Niet verbonden met room. Klik eerst op Connect.');
      return;
    }

    const device = this.getSelectedDevice();
    if (!device) {
      this.setError('Selecteer eerst een camera.');
      return;
    }

    this.isAddingCamera = true;
    try {
      const stream = await this.mediaDeviceService.startCamera(device.deviceId);
      const feedId = this.roomSessionService.createId('feed');

      this.runInZone(() => {
        this.roomSessionService.addLocalFeed(this.createLocalFeed(feedId, device, stream));
        this.webrtcMeshService.addLocalFeed(feedId, device.label, stream);
        this.rsocketRoomService.publish('CAMERA_PUBLISHED', this.createCameraPublishedPayload(feedId, device));
      });

      await this.refreshDevices();
      this.runInZone(() => {
        this.uiInfo = `Camera toegevoegd: ${device.label}`;
      });
    } catch (error: any) {
      this.runInZone(() => {
        console.error('Camera start mislukt', error);
        const reason = error && error.name ? error.name : 'UnknownError';
        if (reason === 'NotAllowedError') {
          this.setError('Camera permission geweigerd. Sta camera-toegang toe in de browser.');
        } else if (reason === 'NotReadableError') {
          this.setError('Camera is in gebruik door een andere app.');
        } else if (reason === 'NotFoundError') {
          this.setError('Camera niet gevonden.');
        } else {
          this.setError(`Kon camera niet starten (${reason}).`);
        }
      });
    } finally {
      this.runInZone(() => {
        this.isAddingCamera = false;
      });
    }
  }

  /**
   * Removes a local feed from both the UI/session store and all peer connections.
   */
  removeCamera(feedId: string): void {
    const removed = this.roomSessionService.removeFeed(feedId);
    if (!removed) {
      return;
    }

    this.webrtcMeshService.removeLocalFeed(feedId);

    if (removed.stream) {
      this.mediaDeviceService.stopCamera(removed.stream);
    }

    this.rsocketRoomService.publish('CAMERA_REMOVED', { feedId });
  }

  /**
   * Publishes a chat message locally and remotely.
   */
  sendMessage(text: string): void {
    if (!this.currentIdentity || !this.connected) {
      this.setError('Niet verbonden met room. Bericht niet verzonden.');
      return;
    }

    const trimmedText = text.trim();
    if (!trimmedText) {
      return;
    }

    this.rsocketRoomService.publish('CHAT_MESSAGE', { text: trimmedText });
    this.roomSessionService.appendMessage(this.createLocalChatMessage(trimmedText));
  }

  /**
   * Used by the template to keep the add-camera action disabled while preconditions are missing.
   */
  canAddCamera(): boolean {
    return this.connected && !this.isAddingCamera && !this.isConnecting && !!this.getSelectedDevice();
  }

  private async ensureRoomConnection(): Promise<void> {
    if (!this.auth.isAuthenticated) {
      return;
    }

    if (this.isConnecting || this.currentIdentity || this.connectionState === 'CONNECTED' || this.connectionState === 'RECONNECTING') {
      return;
    }

    await this.connectRoom();
  }

  /**
   * Builds the candidate broker URL list. Same-origin "/rsocket" is preferred on HTTPS deployments.
   */
  private buildBrokerUrls(): string[] {
    if (this.customBrokerUrlsLocked) {
      return ['/rsocket'];
    }

    const fromInput = this.brokerEndpoints
      .split(/[,\n]/)
      .map((url: string) => url.trim())
      .filter((url: string) => !!url);

    const preferred = this.shouldPreferSameOriginProxy() ? ['/rsocket'] : [];
    const merged = [...preferred, ...fromInput];
    return merged.filter((url: string, idx: number, all: string[]) => all.indexOf(url) === idx);
  }

  /**
   * Prefer the reverse-proxied websocket when the app itself is loaded from a deployed origin.
   */
  private shouldPreferSameOriginProxy(): boolean {
    if (typeof window === 'undefined') {
      return false;
    }

    if (window.location.protocol === 'https:') {
      return true;
    }

    return window.location.hostname !== 'localhost'
      && window.location.hostname !== '127.0.0.1';
  }

  private readStoredAccessToken(): string {
    if (typeof window === 'undefined') {
      return '';
    }

    return window.sessionStorage.getItem(LiveRoomComponent.ACCESS_TOKEN_STORAGE_KEY) ?? '';
  }

  private storeAccessToken(token: string): void {
    if (typeof window === 'undefined') {
      return;
    }

    window.sessionStorage.setItem(LiveRoomComponent.ACCESS_TOKEN_STORAGE_KEY, token);
  }

  private prefillBrokerToken(): void {
    const authBrokerToken = this.auth.brokerToken?.trim() ?? '';
    if (!authBrokerToken) {
      return;
    }

    this.accessToken = authBrokerToken;
    this.storeAccessToken(authBrokerToken);
  }

  private resolveBrokerToken(): string {
    const authBrokerToken = this.auth.brokerToken?.trim() ?? '';
    if (authBrokerToken) {
      this.accessToken = authBrokerToken;
      this.storeAccessToken(authBrokerToken);
      return authBrokerToken;
    }

    const manuallyProvidedToken = this.accessToken.trim();
    if (manuallyProvidedToken) {
      return manuallyProvidedToken;
    }

    return '';
  }

  /**
   * Converts any thrown value into a UI-friendly error string.
   */
  private extractErrorMessage(error: unknown): string {
    if (!error) {
      return 'onbekende fout';
    }

    if (typeof error === 'string') {
      return error;
    }

    if (typeof error === 'object' && error !== null && 'source' in error) {
      const sourceMessage = this.extractErrorMessage((error as { source?: unknown }).source);
      if (sourceMessage && sourceMessage !== 'onbekende fout') {
        return sourceMessage;
      }
    }

    if (typeof error === 'object' && 'message' in error && typeof error.message === 'string') {
      return error.message;
    }

    try {
      return JSON.stringify(error);
    } catch {
      return String(error);
    }
  }

  /**
   * Maps the select element value back to a stable CameraDevice object.
   */
  private getSelectedDevice(): CameraDevice | null {
    if (!this.devices.length || !this.selectedDeviceSelection) {
      return null;
    }

    const selectedIndex = Number(this.selectedDeviceSelection);
    if (!Number.isInteger(selectedIndex) || selectedIndex < 0 || selectedIndex >= this.devices.length) {
      return null;
    }

    return this.devices[selectedIndex];
  }

  /**
   * Dispatches room events to the part of the client that owns the corresponding state.
   */
  private async handleRoomEvent(event: RoomEvent): Promise<void> {
    if (!this.currentIdentity) {
      return;
    }

    if (event.type === 'CHAT_MESSAGE' && event.senderId === this.currentIdentity.clientId) {
      return;
    }

    if (event.type === 'ROOM_JOINED' && event.senderId !== this.currentIdentity.clientId) {
      await this.webrtcMeshService.onPeerJoined(event.senderId, event.senderName);
      return;
    }

    if (event.type === 'CAMERA_PUBLISHED' && event.senderId !== this.currentIdentity.clientId) {
      const peerExists = this.webrtcMeshService.hasPeer(event.senderId);
      if (!peerExists) {
        await this.webrtcMeshService.onPeerJoined(event.senderId, event.senderName);
      } else {
        this.scheduleRenegotiationRecovery(event.senderId, event.senderName);
      }
      this.roomSessionService.consumeRoomEvent(event, this.currentIdentity.clientId);
      return;
    }

    if (event.type === 'ROOM_LEFT' && event.senderId !== this.currentIdentity.clientId) {
      this.cancelRenegotiationRecovery(event.senderId);
      this.webrtcMeshService.onPeerLeft(event.senderId);
      return;
    }

    if (event.type === 'WEBRTC_SIGNAL' && event.senderId !== this.currentIdentity.clientId && event.payload) {
      const signalPayload = event.payload as WebrtcSignalPayload;
      if (signalPayload.signal.description?.type === 'offer') {
        this.cancelRenegotiationRecovery(event.senderId);
      }

      await this.webrtcMeshService.handleSignal(event.senderId, event.senderName, signalPayload);
      return;
    }

    this.roomSessionService.consumeRoomEvent(event, this.currentIdentity.clientId);
  }

  /**
   * Centralizes connection-state UI text so transport changes do not scatter user messaging.
   */
  private applyConnectionState(state: ConnectionState): void {
    const connectionStateMessages: Record<ConnectionState, { error: string; info: string }> = {
      CONNECTED: {
        error: '',
        info: 'Verbonden met broker.'
      },
      CONNECTING: {
        error: '',
        info: 'Verbinden met broker...'
      },
      DISCONNECTED: {
        error: '',
        info: ''
      },
      FAILED: {
        error: 'Broker connectie gefaald. Check URL en broker status.',
        info: ''
      },
      RECONNECTING: {
        error: 'Verbinding verbroken, opnieuw verbinden...',
        info: ''
      }
    };

    const status = connectionStateMessages[state];
    this.uiError = status.error;
    this.uiInfo = status.info;
  }

  /**
   * Clears transient status messaging before a new user action starts.
   */
  private clearStatus(): void {
    this.uiError = '';
    this.uiInfo = '';
  }

  /**
   * Sets a single error and clears any stale informational message.
   */
  private setError(message: string): void {
    this.uiInfo = '';
    this.uiError = message;
  }

  /**
   * Creates the client identity used for broker and WebRTC signaling.
   */
  private createIdentity(): ClientIdentity {
    return {
      clientId: this.roomSessionService.createId('client'),
      displayName: this.displayName.trim() || this.resolvePreferredDisplayName()
    };
  }

  private resolvePreferredDisplayName(): string {
    const preferredName = this.auth.profileName?.trim();
    if (preferredName) {
      return preferredName;
    }

    const email = this.readIdentityClaim('email');
    if (email) {
      return email.split('@')[0];
    }

    return `${LiveRoomComponent.DEFAULT_DISPLAY_NAME_PREFIX}-${Math.floor(Math.random() * 1000)}`;
  }

  private readIdentityClaim(key: string): string | null {
    const claims = this.auth.identityClaims;
    if (!claims) {
      return null;
    }

    const value = claims[key];
    return typeof value === 'string' && value.trim() ? value.trim() : null;
  }

  /**
   * Hooks WebRTC callbacks into the room-session service and broker publisher while ensuring Angular
   * change detection sees the async browser callbacks.
   */
  private initializeWebrtcMesh(identity: ClientIdentity): void {
    this.webrtcMeshService.initialize(
      identity,
      (payload: WebrtcSignalPayload) => {
        this.runInZone(() => {
          this.rsocketRoomService.publish('WEBRTC_SIGNAL', payload);
        });
      },
      (remoteFeed: CameraFeed) => {
        this.runInZone(() => {
          this.cancelRenegotiationRecovery(remoteFeed.ownerId);
          this.roomSessionService.upsertRemoteFeed(remoteFeed);
        });
      },
      (ownerId: string, trackId: string) => {
        this.runInZone(() => {
          this.roomSessionService.removeRemoteTrackFeed(ownerId, trackId);
        });
      },
      (ownerId: string) => {
        this.runInZone(() => {
          this.roomSessionService.removeRemoteFeedsByOwner(ownerId);
        });
      }
    );
  }

  private runInZone<T>(callback: () => T): T {
    const result = NgZone.isInAngularZone() ? callback() : this.ngZone.run(callback);
    this.changeDetectorRef.detectChanges();
    return result;
  }

  private scheduleRenegotiationRecovery(remoteClientId: string, remoteName: string): void {
    this.cancelRenegotiationRecovery(remoteClientId);

    const timer = setTimeout(() => {
      this.renegotiationRecoveryTimers.delete(remoteClientId);

      if (!this.connected || !this.currentIdentity || this.hasRemoteFeed(remoteClientId)) {
        return;
      }

      void this.runInZone(() => this.webrtcMeshService.onPeerJoined(remoteClientId, remoteName));
    }, LiveRoomComponent.RENEGOTIATION_RECOVERY_DELAY_MS);

    this.renegotiationRecoveryTimers.set(remoteClientId, timer);
  }

  private cancelRenegotiationRecovery(remoteClientId: string): void {
    const existingTimer = this.renegotiationRecoveryTimers.get(remoteClientId);
    if (!existingTimer) {
      return;
    }

    clearTimeout(existingTimer);
    this.renegotiationRecoveryTimers.delete(remoteClientId);
  }

  private clearRenegotiationRecoveryTimers(): void {
    this.renegotiationRecoveryTimers.forEach((timer) => clearTimeout(timer));
    this.renegotiationRecoveryTimers.clear();
  }

  private hasRemoteFeed(remoteClientId: string): boolean {
    return this.feeds.some((feed) => !feed.local && feed.ownerId === remoteClientId && !!feed.stream);
  }

  /**
   * Registers browser device hot-plug notifications for automatic camera list refresh.
   */
  private registerDeviceChangeListener(): void {
    const mediaDevices = typeof navigator !== 'undefined' ? navigator.mediaDevices : null;
    if (!mediaDevices?.addEventListener) {
      return;
    }

    mediaDevices.addEventListener('devicechange', this.deviceChangeListener);
  }

  /**
   * Removes the devicechange listener added during initialization.
   */
  private unregisterDeviceChangeListener(): void {
    const mediaDevices = typeof navigator !== 'undefined' ? navigator.mediaDevices : null;
    if (!mediaDevices?.removeEventListener) {
      return;
    }

    mediaDevices.removeEventListener('devicechange', this.deviceChangeListener);
  }

  /**
   * Drops the selected-device index if the current device list no longer contains that position.
   */
  private syncSelectedDeviceSelection(): void {
    if (!this.selectedDeviceSelection) {
      return;
    }

    const selectedIndex = Number(this.selectedDeviceSelection);
    const stillValid = Number.isInteger(selectedIndex)
      && selectedIndex >= 0
      && selectedIndex < this.devices.length;

    if (!stillValid) {
      this.selectedDeviceSelection = '';
    }
  }

  /**
   * Stops all local media tracks owned by the current page instance.
   */
  private stopAllLocalStreams(): void {
    this.feeds.forEach((feed) => {
      if (feed.local && feed.stream) {
        this.mediaDeviceService.stopCamera(feed.stream);
      }
    });
  }

  /**
   * Normalizes a local camera stream into the feed model consumed by the UI and session store.
   */
  private createLocalFeed(feedId: string, device: CameraDevice, stream: MediaStream): CameraFeed {
    return {
      id: feedId,
      ownerId: this.currentIdentity!.clientId,
      ownerName: this.currentIdentity!.displayName,
      deviceId: device.deviceId,
      label: device.label,
      local: true,
      muted: true,
      online: true,
      stream
    };
  }

  /**
   * Payload shape sent to the broker after a local camera becomes available.
   */
  private createCameraPublishedPayload(feedId: string, device: CameraDevice): CameraPublishedPayload {
    return {
      feedId,
      deviceId: device.deviceId,
      label: device.label
    };
  }

  /**
   * Creates the optimistic local chat message rendered immediately after send.
   */
  private createLocalChatMessage(text: string): ChatMessage {
    return {
      id: this.roomSessionService.createId('msg'),
      roomId: this.roomId,
      senderId: this.currentIdentity!.clientId,
      senderName: this.currentIdentity!.displayName,
      text,
      sentAt: new Date().toISOString(),
      local: true
    };
  }
}
