import { CommonModule } from '@angular/common';
import { Component, DestroyRef, OnDestroy, OnInit, inject } from '@angular/core';
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
export class LiveRoomComponent implements OnInit, OnDestroy {
  private static readonly DEFAULT_ROOM_ID = 'main-stage';
  private static readonly DEFAULT_DISPLAY_NAME_PREFIX = 'Host';

  roomId = 'main-stage';
  displayName = `${LiveRoomComponent.DEFAULT_DISPLAY_NAME_PREFIX}-${Math.floor(Math.random() * 1000)}`;
  brokerEndpoints = environment.brokerWebsocketUrls.join(', ');

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

  constructor(
    private mediaDeviceService: MediaDeviceService,
    private roomSessionService: RoomSessionService,
    private rsocketRoomService: RsocketRoomService,
    private webrtcMeshService: WebrtcMeshService
  ) {
    this.roomId = LiveRoomComponent.DEFAULT_ROOM_ID;
  }

  get connected(): boolean {
    return this.connectionState === 'CONNECTED';
  }

  ngOnInit(): void {
    this.roomSessionService.feeds$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((feeds: CameraFeed[]) => {
        this.feeds = feeds;
      });

    this.roomSessionService.messages$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((messages: ChatMessage[]) => {
        this.messages = messages;
      });

    this.rsocketRoomService.state$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((state: ConnectionState) => {
        this.connectionState = state;
        this.applyConnectionState(state);
      });

    this.rsocketRoomService.roomEvents$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event: RoomEvent) => {
        this.handleRoomEvent(event);
      });

    this.refreshDevices();
  }

  ngOnDestroy(): void {
    this.stopAllLocalStreams();
    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
  }

  async connectRoom(): Promise<void> {
    this.clearStatus();
    this.isConnecting = true;

    const brokerUrls = this.buildBrokerUrls();
    if (!brokerUrls.length) {
      this.setError('Vul minstens 1 broker URL in.');
      this.isConnecting = false;
      return;
    }

    const identity = this.createIdentity();

    try {
      await this.rsocketRoomService.connect(identity, this.roomId.trim(), brokerUrls);
      this.currentIdentity = identity;
      this.initializeWebrtcMesh(identity);
    } catch (error) {
      console.error('Connectie mislukt', error);
      const message = this.extractErrorMessage(error);
      if (message.includes('SecurityError') || message.includes('insecure')) {
        this.setError('Connectie geblokkeerd door browser security. Gebruik op HTTPS alleen wss:// of /rsocket.');
      } else if (message.includes('localhost') || message.includes('127.0.0.1')) {
        this.setError('Connectie naar localhost mislukt. Gebruik /rsocket of wss://<LAN-IP>/rsocket.');
      } else {
        this.setError(`Connectie mislukt: ${message}`);
      }
    } finally {
      this.isConnecting = false;
    }
  }

  disconnectRoom(): void {
    this.stopAllLocalStreams();
    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
    this.currentIdentity = null;
    this.roomSessionService.reset();
    this.uiInfo = 'Verbinding verbroken.';
  }

  async refreshDevices(): Promise<void> {
    this.clearStatus();
    try {
      this.devices = await this.mediaDeviceService.listVideoInputs();
      this.syncSelectedDeviceSelection();

      if (!this.devices.length) {
        const insecureContext = typeof window !== 'undefined' && !window.isSecureContext;
        if (insecureContext) {
          this.setError('Geen camera beschikbaar op een onveilige verbinding. Open de app via HTTPS (of localhost).');
        } else {
          this.setError('Geen camera gevonden. Check browser permissions en sluit andere camera-apps.');
        }
      }
    } catch (error) {
      console.error('Device refresh mislukt', error);
      this.setError('Kon camera-lijst niet laden.');
    }
  }

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

      this.roomSessionService.addLocalFeed(this.createLocalFeed(feedId, device, stream));

      this.webrtcMeshService.addLocalFeed(feedId, device.label, stream);

      this.rsocketRoomService.publish('CAMERA_PUBLISHED', this.createCameraPublishedPayload(feedId, device));

      await this.refreshDevices();
      this.uiInfo = `Camera toegevoegd: ${device.label}`;
    } catch (error: any) {
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
    } finally {
      this.isAddingCamera = false;
    }
  }

  canAddCamera(): boolean {
    return this.connected && !this.isAddingCamera && !!this.getSelectedDevice();
  }

  private buildBrokerUrls(): string[] {
    const fromInput = this.brokerEndpoints
      .split(/[,\n]/)
      .map((url: string) => url.trim())
      .filter((url: string) => !!url);

    const preferred = this.shouldPreferSameOriginProxy() ? ['/rsocket'] : [];
    const merged = [...preferred, ...fromInput];
    return merged.filter((url: string, idx: number, all: string[]) => all.indexOf(url) === idx);
  }

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

  private extractErrorMessage(error: unknown): string {
    if (!error) {
      return 'onbekende fout';
    }

    if (typeof error === 'string') {
      return error;
    }

    if (typeof error === 'object' && 'message' in error && typeof (error as any).message === 'string') {
      return (error as any).message;
    }

    try {
      return JSON.stringify(error);
    } catch {
      return String(error);
    }
  }

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
      await this.webrtcMeshService.onPeerJoined(event.senderId, event.senderName);
      this.roomSessionService.consumeRoomEvent(event, this.currentIdentity.clientId);
      return;
    }

    if (event.type === 'ROOM_LEFT' && event.senderId !== this.currentIdentity.clientId) {
      this.webrtcMeshService.onPeerLeft(event.senderId);
      return;
    }

    if (event.type === 'WEBRTC_SIGNAL' && event.senderId !== this.currentIdentity.clientId && event.payload) {
      await this.webrtcMeshService.handleSignal(event.senderId, event.senderName, event.payload as WebrtcSignalPayload);
      return;
    }

    this.roomSessionService.consumeRoomEvent(event, this.currentIdentity.clientId);
  }

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

  private clearStatus(): void {
    this.uiError = '';
    this.uiInfo = '';
  }

  private setError(message: string): void {
    this.uiInfo = '';
    this.uiError = message;
  }

  private createIdentity(): ClientIdentity {
    return {
      clientId: this.roomSessionService.createId('client'),
      displayName: this.displayName.trim() || 'Anonymous'
    };
  }

  private initializeWebrtcMesh(identity: ClientIdentity): void {
    this.webrtcMeshService.initialize(
      identity,
      (payload: WebrtcSignalPayload) => {
        this.rsocketRoomService.publish('WEBRTC_SIGNAL', payload);
      },
      (remoteFeed: CameraFeed) => {
        this.roomSessionService.upsertRemoteFeed(remoteFeed);
      },
      (ownerId: string, trackId: string) => {
        this.roomSessionService.removeRemoteTrackFeed(ownerId, trackId);
      },
      (ownerId: string) => {
        this.roomSessionService.removeRemoteFeedsByOwner(ownerId);
      }
    );
  }

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

  private stopAllLocalStreams(): void {
    this.feeds.forEach((feed) => {
      if (feed.local && feed.stream) {
        this.mediaDeviceService.stopCamera(feed.stream);
      }
    });
  }

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

  private createCameraPublishedPayload(feedId: string, device: CameraDevice): CameraPublishedPayload {
    return {
      feedId,
      deviceId: device.deviceId,
      label: device.label
    };
  }

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
