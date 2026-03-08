import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BehaviorSubject, Subject } from 'rxjs';
import { type MockedObject, vi } from 'vitest';
import { CameraFeed, ChatMessage, ConnectionState, RoomEvent } from '../../core/models/room.models';
import { MediaDeviceService } from '../../core/services/media-device.service';
import { RoomSessionService } from '../../core/services/room-session.service';
import { RsocketRoomService } from '../../core/services/rsocket-room.service';
import { WebrtcMeshService } from '../../core/services/webrtc-mesh.service';
import { LiveRoomComponent } from './live-room.component';

describe('LiveRoomComponent', () => {
  let fixture: ComponentFixture<LiveRoomComponent>;
  let component: LiveRoomComponent;

  let stateSubject: BehaviorSubject<ConnectionState>;
  let roomEventsSubject: Subject<RoomEvent>;

  let mediaDeviceService: MockedObject<MediaDeviceService>;
  let roomSessionService: MockedObject<RoomSessionService>;
  let rsocketRoomService: MockedObject<RsocketRoomService>;
  let webrtcMeshService: MockedObject<WebrtcMeshService>;
  let originalMediaDevices: MediaDevices | undefined;
  let registeredDeviceChangeListener: (() => void) | null;

  beforeEach(async () => {
    originalMediaDevices = navigator.mediaDevices;
    registeredDeviceChangeListener = null;
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        addEventListener: vi.fn((eventName: string, listener: () => void) => {
          if (eventName === 'devicechange') {
            registeredDeviceChangeListener = listener;
          }
        }),
        removeEventListener: vi.fn((eventName: string, listener: () => void) => {
          if (eventName === 'devicechange' && registeredDeviceChangeListener === listener) {
            registeredDeviceChangeListener = null;
          }
        })
      }
    });

    stateSubject = new BehaviorSubject<ConnectionState>('DISCONNECTED');
    roomEventsSubject = new Subject<RoomEvent>();

    mediaDeviceService = {
      listVideoInputs: vi.fn(),
      startCamera: vi.fn(),
      stopCamera: vi.fn()
    } as unknown as MockedObject<MediaDeviceService>;

    roomSessionService = {
      createId: vi.fn(),
      addLocalFeed: vi.fn(),
      removeFeed: vi.fn(),
      upsertRemoteFeed: vi.fn(),
      removeRemoteTrackFeed: vi.fn(),
      removeRemoteFeedsByOwner: vi.fn(),
      appendMessage: vi.fn(),
      consumeRoomEvent: vi.fn(),
      reset: vi.fn(),
      feeds$: new BehaviorSubject<CameraFeed[]>([]).asObservable(),
      messages$: new BehaviorSubject<ChatMessage[]>([]).asObservable()
    } as unknown as MockedObject<RoomSessionService>;

    rsocketRoomService = {
      connect: vi.fn(),
      disconnect: vi.fn(),
      publish: vi.fn(),
      publishWithAck: vi.fn(),
      state$: stateSubject.asObservable(),
      roomEvents$: roomEventsSubject.asObservable()
    } as unknown as MockedObject<RsocketRoomService>;

    webrtcMeshService = {
      initialize: vi.fn(),
      dispose: vi.fn(),
      addLocalFeed: vi.fn(),
      removeLocalFeed: vi.fn(),
      onPeerJoined: vi.fn(),
      onPeerLeft: vi.fn(),
      handleSignal: vi.fn()
    } as unknown as MockedObject<WebrtcMeshService>;

    mediaDeviceService.listVideoInputs.mockResolvedValue([{ deviceId: 'cam-1', label: 'Camera 1' }]);
    mediaDeviceService.startCamera.mockResolvedValue({ id: 'stream-1', getTracks: () => [] } as any);
    rsocketRoomService.connect.mockResolvedValue(undefined);
    rsocketRoomService.publishWithAck.mockResolvedValue(true);

    let counter = 0;
    roomSessionService.createId.mockImplementation((prefix: string) => `${prefix}-${++counter}`);

    await TestBed.configureTestingModule({
      imports: [LiveRoomComponent],
      providers: [
        { provide: MediaDeviceService, useValue: mediaDeviceService },
        { provide: RoomSessionService, useValue: roomSessionService },
        { provide: RsocketRoomService, useValue: rsocketRoomService },
        { provide: WebrtcMeshService, useValue: webrtcMeshService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LiveRoomComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: originalMediaDevices
    });
  });

  it('connects room and initializes webrtc mesh', async () => {
    component.roomId = 'room-x';
    component.displayName = 'Alice';
    component.brokerEndpoints = 'ws://localhost:9898/rsocket, ws://localhost:9899/rsocket';
    vi.spyOn(component as any, 'shouldPreferSameOriginProxy').mockReturnValue(true);

    await component.connectRoom();

    expect(rsocketRoomService.connect).toHaveBeenCalled();
    const connectArgs = rsocketRoomService.connect.mock.calls.at(-1) as any[];
    const brokerUrls = connectArgs[2] as string[];
    expect(brokerUrls).toContain('/rsocket');
    expect(webrtcMeshService.initialize).toHaveBeenCalled();
  });

  it('shows connected state in the UI when state stream emits CONNECTED', () => {
    stateSubject.next('CONNECTED');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent || '';
    expect(component.connected).toBe(true);
    expect(text).toContain('CONNECTED');
  });

  it('shows validation error when adding camera while disconnected', async () => {
    component.selectedDeviceSelection = '0';

    await component.addCamera();

    expect(component.uiError).toContain('Niet verbonden');
    expect(rsocketRoomService.publish).not.toHaveBeenCalled();
  });

  it('adds and publishes selected camera when connected', async () => {
    component.selectedDeviceSelection = '0';
    await component.connectRoom();
    stateSubject.next('CONNECTED');

    await component.addCamera();

    expect(mediaDeviceService.startCamera).toHaveBeenCalledWith('cam-1');
    expect(roomSessionService.addLocalFeed).toHaveBeenCalled();
    expect(webrtcMeshService.addLocalFeed).toHaveBeenCalled();
    expect(rsocketRoomService.publish).toHaveBeenCalledWith(
      'CAMERA_PUBLISHED',
      expect.objectContaining({ deviceId: 'cam-1', label: 'Camera 1' })
    );
    expect(mediaDeviceService.listVideoInputs).toHaveBeenCalledTimes(2);
  });

  it('initiates webrtc peer join when receiving CAMERA_PUBLISHED from another client', async () => {
    await component.connectRoom();
    stateSubject.next('CONNECTED');

    roomEventsSubject.next({
      type: 'CAMERA_PUBLISHED',
      roomId: 'main-stage',
      senderId: 'client-remote-1',
      senderName: 'Remote 1',
      sentAt: new Date().toISOString(),
      payload: {
        feedId: 'feed-remote-1',
        label: 'OBS Virtual Camera'
      }
    });

    await fixture.whenStable();

    expect(webrtcMeshService.onPeerJoined).toHaveBeenCalledWith('client-remote-1', 'Remote 1');
  });

  it('refreshes devices automatically on media device change', async () => {
    expect(mediaDeviceService.listVideoInputs).toHaveBeenCalledTimes(1);
    expect(registeredDeviceChangeListener).not.toBeNull();

    mediaDeviceService.listVideoInputs.mockResolvedValue([{ deviceId: 'cam-2', label: 'Camera 2' }]);
    registeredDeviceChangeListener!();
    await fixture.whenStable();

    expect(mediaDeviceService.listVideoInputs).toHaveBeenCalledTimes(2);
    expect(component.devices).toEqual([{ deviceId: 'cam-2', label: 'Camera 2' }]);
  });

  it('sends chat with publish and appends the local message immediately', async () => {
    await component.connectRoom();
    stateSubject.next('CONNECTED');

    component.sendMessage(' Hallo team ');

    expect(rsocketRoomService.publish).toHaveBeenCalledWith('CHAT_MESSAGE', { text: 'Hallo team' });
    expect(roomSessionService.appendMessage).toHaveBeenCalledWith(expect.objectContaining({
      text: 'Hallo team',
      local: true
    }));
  });

  it('blocks chat send while disconnected', () => {
    component.sendMessage('test');

    expect(component.uiError).toContain('Niet verbonden');
    expect(rsocketRoomService.publish).not.toHaveBeenCalledWith('CHAT_MESSAGE', expect.anything());
  });
});
