import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CameraDevice, CameraFeed, ChatMessage, ClientIdentity, ConnectionState, RoomEvent, WebrtcSignalPayload } from '../../core/models/room.models';
import { MediaDeviceService } from '../../core/services/media-device.service';
import { RoomSessionService } from '../../core/services/room-session.service';
import { RsocketRoomService } from '../../core/services/rsocket-room.service';
import { WebrtcMeshService } from '../../core/services/webrtc-mesh.service';

@Component({
  selector: 'app-live-room',
  templateUrl: './live-room.component.html',
  styleUrl: './live-room.component.scss'
})
export class LiveRoomComponent implements OnInit, OnDestroy {
  roomId = 'main-stage';
  displayName = 'Host-' + Math.floor(Math.random() * 1000);
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
  private subscriptions: Subscription[] = [];

  constructor(
    private mediaDeviceService: MediaDeviceService,
    private roomSessionService: RoomSessionService,
    private rsocketRoomService: RsocketRoomService,
    private webrtcMeshService: WebrtcMeshService
  ) {
  }

  get connected(): boolean {
    return this.connectionState === 'CONNECTED';
  }

  ngOnInit(): void {
    this.subscriptions.push(
      this.roomSessionService.feeds$.subscribe((feeds: CameraFeed[]) => {
        this.feeds = feeds;
      })
    );

    this.subscriptions.push(
      this.roomSessionService.messages$.subscribe((messages: ChatMessage[]) => {
        this.messages = messages;
      })
    );

    this.subscriptions.push(
      this.rsocketRoomService.state$.subscribe((state: ConnectionState) => {
        this.connectionState = state;
        if (state === 'CONNECTING') {
          this.uiError = '';
          this.uiInfo = 'Verbinden met broker...';
        } else if (state === 'CONNECTED') {
          this.uiError = '';
          this.uiInfo = 'Verbonden met broker.';
        } else if (state === 'RECONNECTING') {
          this.uiInfo = '';
          this.uiError = 'Verbinding verbroken, opnieuw verbinden...';
        } else if (state === 'FAILED') {
          this.uiInfo = '';
          this.uiError = 'Broker connectie gefaald. Check URL en broker status.';
        } else if (state === 'DISCONNECTED') {
          this.uiInfo = '';
        }
      })
    );

    this.subscriptions.push(
      this.rsocketRoomService.roomEvents$.subscribe((event: RoomEvent) => {
        this.handleRoomEvent(event);
      })
    );

    this.refreshDevices();
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach((subscription) => {
      subscription.unsubscribe();
    });

    this.feeds.forEach((feed: CameraFeed) => {
      if (feed.stream) {
        this.mediaDeviceService.stopCamera(feed.stream);
      }
    });

    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
  }

  async connectRoom(): Promise<void> {
    this.uiError = '';
    this.uiInfo = '';
    this.isConnecting = true;

    const identity: ClientIdentity = {
      clientId: this.roomSessionService.createId('client'),
      displayName: this.displayName.trim() || 'Anonymous'
    };

    const brokerUrls = this.buildBrokerUrls();

    if (!brokerUrls.length) {
      this.uiError = 'Vul minstens 1 broker URL in.';
      this.isConnecting = false;
      return;
    }
    try {
      await this.rsocketRoomService.connect(identity, this.roomId.trim(), brokerUrls);
      this.currentIdentity = identity;

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
    } catch (error) {
      console.error('Connectie mislukt', error);
      const message = this.extractErrorMessage(error);
      if (message.includes('SecurityError') || message.includes('insecure')) {
        this.uiError = 'Connectie geblokkeerd door browser security. Gebruik op HTTPS alleen wss:// of /rsocket.';
      } else if (message.includes('localhost') || message.includes('127.0.0.1')) {
        this.uiError = 'Connectie naar localhost mislukt. Gebruik /rsocket of wss://<LAN-IP>/rsocket.';
      } else {
        this.uiError = 'Connectie mislukt: ' + message;
      }
    } finally {
      this.isConnecting = false;
    }
  }

  disconnectRoom(): void {
    this.feeds.forEach((feed) => {
      if (feed.local && feed.stream) {
        this.mediaDeviceService.stopCamera(feed.stream);
      }
    });

    this.webrtcMeshService.dispose();
    this.rsocketRoomService.disconnect();
    this.currentIdentity = null;
    this.roomSessionService.reset();
    this.uiInfo = 'Verbinding verbroken.';
  }

  async refreshDevices(): Promise<void> {
    this.uiError = '';
    this.uiInfo = '';
    try {
      this.devices = await this.mediaDeviceService.listVideoInputs();
      if (this.selectedDeviceSelection) {
        const selectedIndex = Number(this.selectedDeviceSelection);
        const stillValid = Number.isInteger(selectedIndex)
          && selectedIndex >= 0
          && selectedIndex < this.devices.length;
        if (!stillValid) {
          this.selectedDeviceSelection = '';
        }
      }

      if (!this.devices.length) {
        const insecureContext = typeof window !== 'undefined' && !window.isSecureContext;
        if (insecureContext) {
          this.uiError = 'Geen camera beschikbaar op een onveilige verbinding. Open de app via HTTPS (of localhost).';
        } else {
          this.uiError = 'Geen camera gevonden. Check browser permissions en sluit andere camera-apps.';
        }
      }
    } catch (error) {
      console.error('Device refresh mislukt', error);
      this.uiError = 'Kon camera-lijst niet laden.';
    }
  }

  async addCamera(): Promise<void> {
    this.uiError = '';
    this.uiInfo = '';

    if (!this.connected || !this.currentIdentity) {
      this.uiError = 'Niet verbonden met room. Klik eerst op Connect.';
      return;
    }

    const device = this.getSelectedDevice();
    if (!device) {
      this.uiError = 'Selecteer eerst een camera.';
      return;
    }

    this.isAddingCamera = true;
    try {
      const stream = await this.mediaDeviceService.startCamera(device.deviceId);
      const feedId = this.roomSessionService.createId('feed');

      this.roomSessionService.addLocalFeed({
        id: feedId,
        ownerId: this.currentIdentity.clientId,
        ownerName: this.currentIdentity.displayName,
        deviceId: device.deviceId,
        label: device.label,
        local: true,
        muted: true,
        online: true,
        stream
      });

      this.webrtcMeshService.addLocalFeed(feedId, device.label, stream);

      this.rsocketRoomService.publish('CAMERA_PUBLISHED', {
        feedId,
        deviceId: device.deviceId,
        label: device.label
      });

      await this.refreshDevices();
      this.uiInfo = 'Camera toegevoegd: ' + device.label;
    } catch (error: any) {
      console.error('Camera start mislukt', error);
      const reason = error && error.name ? error.name : 'UnknownError';
      if (reason === 'NotAllowedError') {
        this.uiError = 'Camera permission geweigerd. Sta camera-toegang toe in de browser.';
      } else if (reason === 'NotReadableError') {
        this.uiError = 'Camera is in gebruik door een andere app.';
      } else if (reason === 'NotFoundError') {
        this.uiError = 'Camera niet gevonden.';
      } else {
        this.uiError = 'Kon camera niet starten (' + reason + ').';
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
      this.uiError = 'Niet verbonden met room. Bericht niet verzonden.';
      return;
    }

    const trimmedText = text.trim();
    if (!trimmedText) {
      return;
    }

    this.rsocketRoomService.publish('CHAT_MESSAGE', { text: trimmedText });

    this.roomSessionService.appendMessage({
      id: this.roomSessionService.createId('msg'),
      roomId: this.roomId,
      senderId: this.currentIdentity.clientId,
      senderName: this.currentIdentity.displayName,
      text: trimmedText,
      sentAt: new Date().toISOString(),
      local: true
    });
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
}
