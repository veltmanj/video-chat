// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ClientIdentity } from '../models/room.models';
import { WebrtcMeshService } from './webrtc-mesh.service';

class MockSender {
  track: MediaStreamTrack | null;

  constructor(track: MediaStreamTrack | null) {
    this.track = track;
  }

  replaceTrack = vi.fn(async (track: MediaStreamTrack | null) => {
    this.track = track;
  });
}

class MockTransceiver {
  readonly receiver: RTCRtpReceiver;
  direction: RTCRtpTransceiverDirection = 'sendrecv';

  constructor(
    readonly sender: RTCRtpSender,
    kind: string
  ) {
    this.receiver = {
      track: { kind } as MediaStreamTrack
    } as RTCRtpReceiver;
  }
}

class MockRTCPeerConnection {
  static instances: MockRTCPeerConnection[] = [];

  signalingState: RTCSignalingState = 'stable';
  connectionState: RTCPeerConnectionState = 'new';
  localDescription: RTCSessionDescriptionInit | null = null;
  remoteDescription: RTCSessionDescriptionInit | null = null;

  onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
  ontrack: ((event: RTCTrackEvent) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;

  private readonly senders: RTCRtpSender[] = [];
  private readonly transceivers: RTCRtpTransceiver[] = [];

  constructor(_configuration?: RTCConfiguration) {
    MockRTCPeerConnection.instances.push(this);
  }

  addTrack(track: MediaStreamTrack, _stream: MediaStream): RTCRtpSender {
    const sender = new MockSender(track) as unknown as RTCRtpSender;
    const transceiver = new MockTransceiver(sender, track.kind) as unknown as RTCRtpTransceiver;

    this.senders.push(sender);
    this.transceivers.push(transceiver);
    return sender;
  }

  getSenders(): RTCRtpSender[] {
    return this.senders;
  }

  getTransceivers(): RTCRtpTransceiver[] {
    return this.transceivers;
  }

  removeTrack(sender: RTCRtpSender): void {
    (sender as unknown as MockSender).track = null;
  }

  async createOffer(): Promise<RTCSessionDescriptionInit> {
    return { type: 'offer', sdp: 'v=0' };
  }

  async createAnswer(): Promise<RTCSessionDescriptionInit> {
    return { type: 'answer', sdp: 'v=0' };
  }

  async setLocalDescription(description?: RTCSessionDescriptionInit): Promise<void> {
    this.localDescription = description ?? { type: 'offer', sdp: 'v=0' };
  }

  async setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void> {
    this.remoteDescription = description;
  }

  async addIceCandidate(_candidate?: RTCIceCandidateInit | null): Promise<void> {
    return;
  }

  close(): void {
    this.connectionState = 'closed';
  }
}

describe('WebrtcMeshService', () => {
  const identity: ClientIdentity = {
    clientId: 'client-local',
    displayName: 'Local'
  };

  const originalPeerConnection = globalThis.RTCPeerConnection;

  beforeEach(() => {
    MockRTCPeerConnection.instances = [];
    globalThis.RTCPeerConnection = MockRTCPeerConnection as unknown as typeof RTCPeerConnection;
  });

  afterEach(() => {
    globalThis.RTCPeerConnection = originalPeerConnection;
  });

  it('reuses an empty video sender slot across repeated remove and add cycles', async () => {
    const service = new WebrtcMeshService();
    service.initialize(identity, () => undefined, () => undefined, () => undefined, () => undefined);

    await service.onPeerJoined('client-remote', 'Remote');

    const peer = MockRTCPeerConnection.instances[0];
    const track1 = { id: 'track-1', kind: 'video' } as MediaStreamTrack;
    const track2 = { id: 'track-2', kind: 'video' } as MediaStreamTrack;
    const track3 = { id: 'track-3', kind: 'video' } as MediaStreamTrack;
    const stream1 = { getTracks: () => [track1] } as unknown as MediaStream;
    const stream2 = { getTracks: () => [track2] } as unknown as MediaStream;
    const stream3 = { getTracks: () => [track3] } as unknown as MediaStream;

    service.addLocalFeed('feed-1', 'Cam 1', stream1);
    await Promise.resolve();

    expect(peer.getSenders()).toHaveLength(1);
    expect((peer.getSenders()[0] as unknown as MockSender).track).toBe(track1);

    service.removeLocalFeed('feed-1');
    expect((peer.getTransceivers()[0] as unknown as MockTransceiver).direction).toBe('recvonly');
    expect((peer.getSenders()[0] as unknown as MockSender).track).toBeNull();

    service.addLocalFeed('feed-2', 'Cam 1', stream2);
    await Promise.resolve();

    expect(peer.getSenders()).toHaveLength(1);
    expect((peer.getTransceivers()[0] as unknown as MockTransceiver).direction).toBe('sendrecv');
    expect((peer.getSenders()[0] as unknown as MockSender).track).toBe(track2);

    service.removeLocalFeed('feed-2');
    service.addLocalFeed('feed-3', 'Cam 1', stream3);
    await Promise.resolve();

    expect(peer.getSenders()).toHaveLength(1);
    expect((peer.getSenders()[0] as unknown as MockSender).track).toBe(track3);
  });
});
