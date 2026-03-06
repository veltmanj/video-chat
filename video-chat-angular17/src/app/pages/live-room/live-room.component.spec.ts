import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { BehaviorSubject, Subject } from 'rxjs';
import { CameraFeed, ChatMessage, ConnectionState, RoomEvent } from '../../core/models/room.models';
import { MediaDeviceService } from '../../core/services/media-device.service';
import { RoomSessionService } from '../../core/services/room-session.service';
import { RsocketRoomService } from '../../core/services/rsocket-room.service';
import { WebrtcMeshService } from '../../core/services/webrtc-mesh.service';
import { LiveRoomComponent } from './live-room.component';

@Component({
  selector: 'app-camera-grid',
  template: ''
})
class CameraGridStubComponent {
  @Input() feeds: CameraFeed[] = [];
  @Output() removeFeed = new EventEmitter<string>();
}

@Component({
  selector: 'app-chat-panel',
  template: ''
})
class ChatPanelStubComponent {
  @Input() messages: ChatMessage[] = [];
  @Input() connected = false;
  @Output() sendMessage = new EventEmitter<string>();
}

describe('LiveRoomComponent', () => {
  let fixture: ComponentFixture<LiveRoomComponent>;
  let component: LiveRoomComponent;

  let stateSubject: BehaviorSubject<ConnectionState>;
  let roomEventsSubject: Subject<RoomEvent>;

  let mediaDeviceService: jasmine.SpyObj<MediaDeviceService>;
  let roomSessionService: jasmine.SpyObj<RoomSessionService>;
  let rsocketRoomService: jasmine.SpyObj<RsocketRoomService>;
  let webrtcMeshService: jasmine.SpyObj<WebrtcMeshService>;

  beforeEach(async () => {
    stateSubject = new BehaviorSubject<ConnectionState>('DISCONNECTED');
    roomEventsSubject = new Subject<RoomEvent>();

    mediaDeviceService = jasmine.createSpyObj<MediaDeviceService>('MediaDeviceService', ['listVideoInputs', 'startCamera', 'stopCamera']);

    roomSessionService = jasmine.createSpyObj<RoomSessionService>(
      'RoomSessionService',
      ['createId', 'addLocalFeed', 'removeFeed', 'upsertRemoteFeed', 'removeRemoteTrackFeed', 'removeRemoteFeedsByOwner', 'appendMessage', 'consumeRoomEvent', 'reset'],
      {
        feeds$: new BehaviorSubject<CameraFeed[]>([]).asObservable(),
        messages$: new BehaviorSubject<ChatMessage[]>([]).asObservable()
      }
    );

    rsocketRoomService = jasmine.createSpyObj<RsocketRoomService>(
      'RsocketRoomService',
      ['connect', 'disconnect', 'publish', 'publishWithAck'],
      {
        state$: stateSubject.asObservable(),
        roomEvents$: roomEventsSubject.asObservable()
      }
    );

    webrtcMeshService = jasmine.createSpyObj<WebrtcMeshService>(
      'WebrtcMeshService',
      ['initialize', 'dispose', 'addLocalFeed', 'removeLocalFeed', 'onPeerJoined', 'onPeerLeft', 'handleSignal']
    );

    mediaDeviceService.listVideoInputs.and.resolveTo([{ deviceId: 'cam-1', label: 'Camera 1' }]);
    mediaDeviceService.startCamera.and.resolveTo({ id: 'stream-1', getTracks: () => [] } as any);
    rsocketRoomService.connect.and.resolveTo();
    rsocketRoomService.publishWithAck.and.resolveTo(true);

    let counter = 0;
    roomSessionService.createId.and.callFake((prefix: string) => `${prefix}-${++counter}`);

    await TestBed.configureTestingModule({
      imports: [FormsModule],
      declarations: [LiveRoomComponent, CameraGridStubComponent, ChatPanelStubComponent],
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

  it('connects room and initializes webrtc mesh', async () => {
    component.roomId = 'room-x';
    component.displayName = 'Alice';
    component.brokerEndpoints = 'ws://localhost:9898/rsocket, ws://localhost:9899/rsocket';
    spyOn<any>(component, 'shouldPreferSameOriginProxy').and.returnValue(true);

    await component.connectRoom();

    expect(rsocketRoomService.connect).toHaveBeenCalled();
    const connectArgs = rsocketRoomService.connect.calls.mostRecent().args;
    const brokerUrls = connectArgs[2] as string[];
    expect(brokerUrls).toContain('/rsocket');
    expect(webrtcMeshService.initialize).toHaveBeenCalled();
  });

  it('shows connected state in the UI when state stream emits CONNECTED', () => {
    stateSubject.next('CONNECTED');
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent || '';
    expect(component.connected).toBeTrue();
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
      jasmine.objectContaining({ deviceId: 'cam-1', label: 'Camera 1' })
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

  it('sends chat with publish and appends the local message immediately', async () => {
    await component.connectRoom();
    stateSubject.next('CONNECTED');

    component.sendMessage(' Hallo team ');

    expect(rsocketRoomService.publish).toHaveBeenCalledWith('CHAT_MESSAGE', { text: 'Hallo team' });
    expect(roomSessionService.appendMessage).toHaveBeenCalledWith(jasmine.objectContaining({
      text: 'Hallo team',
      local: true
    }));
  });

  it('blocks chat send while disconnected', () => {
    component.sendMessage('test');

    expect(component.uiError).toContain('Niet verbonden');
    expect(rsocketRoomService.publish).not.toHaveBeenCalledWith('CHAT_MESSAGE', jasmine.anything());
  });
});
