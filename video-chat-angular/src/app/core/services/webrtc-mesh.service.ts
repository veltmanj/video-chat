import { Injectable } from '@angular/core';
import { CameraFeed, ClientIdentity, WebrtcSignalPayload } from '../models/room.models';

interface LocalFeedState {
  feedId: string;
  label: string;
  stream: MediaStream;
}

interface PeerState {
  pc: RTCPeerConnection;
  makingOffer: boolean;
  ignoreOffer: boolean;
  polite: boolean;
  pendingCandidates: RTCIceCandidateInit[];
  needsOffer: boolean;
}

@Injectable({
  providedIn: 'root'
})
/**
 * Manages peer-to-peer media exchange between room participants.
 *
 * The broker distributes signaling messages, but all actual media tracks flow through RTCPeerConnection
 * instances owned here.
 */
export class WebrtcMeshService {
  /**
   * Public STUN fallback so peers can gather candidates in simple LAN/NAT setups without extra config.
   */
  private static readonly DEFAULT_STUN_SERVERS = ['stun:stun.l.google.com:19302'];

  private identity: ClientIdentity | null = null;
  private readonly peers = new Map<string, PeerState>();
  private readonly peerNames = new Map<string, string>();
  private readonly localFeeds = new Map<string, LocalFeedState>();

  private signalPublisher: ((payload: WebrtcSignalPayload) => void) | null = null;
  private remoteFeedUpserter: ((feed: CameraFeed) => void) | null = null;
  private remoteFeedRemover: ((ownerId: string, trackId: string) => void) | null = null;
  private remoteOwnerCleaner: ((ownerId: string) => void) | null = null;

  /**
   * Wires transport-agnostic callbacks into the service. The service itself stays free of Angular state.
   */
  initialize(
    identity: ClientIdentity,
    signalPublisher: (payload: WebrtcSignalPayload) => void,
    remoteFeedUpserter: (feed: CameraFeed) => void,
    remoteFeedRemover: (ownerId: string, trackId: string) => void,
    remoteOwnerCleaner: (ownerId: string) => void
  ): void {
    this.identity = identity;
    this.signalPublisher = signalPublisher;
    this.remoteFeedUpserter = remoteFeedUpserter;
    this.remoteFeedRemover = remoteFeedRemover;
    this.remoteOwnerCleaner = remoteOwnerCleaner;
  }

  /**
   * Ensures a peer connection exists when another participant joins and requests an offer when local
   * cameras already exist.
   */
  async onPeerJoined(remoteClientId: string, remoteName: string): Promise<void> {
    if (!this.identity || remoteClientId === this.identity.clientId) {
      return;
    }

    this.peerNames.set(remoteClientId, remoteName);
    const peer = this.ensurePeer(remoteClientId);

    if (peer.pc.signalingState === 'stable' && this.localFeeds.size > 0) {
      this.requestOffer(remoteClientId);
    }
  }

  /**
   * Exposes whether a peer connection already exists so room events can avoid duplicate renegotiation.
   */
  hasPeer(remoteClientId: string): boolean {
    return this.peers.has(remoteClientId);
  }

  /**
   * Tears down peer state and removes any rendered remote tiles for a departed participant.
   */
  onPeerLeft(remoteClientId: string): void {
    const peer = this.peers.get(remoteClientId);
    if (peer) {
      peer.pc.close();
      this.peers.delete(remoteClientId);
    }

    if (this.remoteOwnerCleaner) {
      this.remoteOwnerCleaner(remoteClientId);
    }

    this.peerNames.delete(remoteClientId);
  }

  /**
   * Adds a newly published local camera to every active peer connection and triggers renegotiation.
   */
  addLocalFeed(feedId: string, label: string, stream: MediaStream): void {
    this.localFeeds.set(feedId, { feedId, label, stream });

    this.peers.forEach((peerState, remoteClientId) => {
      this.addStreamToPeer(peerState.pc, stream);
      this.requestOffer(remoteClientId);
    });
  }

  /**
   * Removes a local camera from all peers and renegotiates so the remote side drops the track cleanly.
   */
  removeLocalFeed(feedId: string): void {
    const feed = this.localFeeds.get(feedId);
    if (!feed) {
      return;
    }

    this.peers.forEach((peerState, remoteClientId) => {
      this.removeStreamFromPeer(peerState.pc, feed.stream);
      this.requestOffer(remoteClientId);
    });

    this.localFeeds.delete(feedId);
  }

  /**
   * Applies an incoming signaling message using the "perfect negotiation" pattern.
   */
  async handleSignal(fromClientId: string, fromName: string, payload: WebrtcSignalPayload): Promise<void> {
    const description = this.normalizeDescription(payload.signal.description);
    const candidate = payload.signal.candidate;

    if (!this.identity || payload.targetClientId !== this.identity.clientId) {
      return;
    }

    this.peerNames.set(fromClientId, fromName);

    const peer = this.ensurePeer(fromClientId);

    if (description) {
      const offerCollision = description.type === 'offer' && (peer.makingOffer || peer.pc.signalingState !== 'stable');
      peer.ignoreOffer = !peer.polite && offerCollision;

      if (peer.ignoreOffer) {
        return;
      }

      await peer.pc.setRemoteDescription(description);
      if (peer.pendingCandidates.length) {
        const pendingCandidates = peer.pendingCandidates.slice();
        peer.pendingCandidates = [];
        for (const pendingCandidate of pendingCandidates) {
          await peer.pc.addIceCandidate(pendingCandidate);
        }
      }

      if (description.type === 'offer') {
        await peer.pc.setLocalDescription(await peer.pc.createAnswer());
        this.publishSignal(fromClientId, {
          description: this.serializeDescription(peer.pc.localDescription)
        });
      }

      this.flushQueuedOffer(fromClientId).catch((error) => {
        console.error('Offer queue na remote description mislukt', error);
      });
      return;
    }

    if (candidate) {
      if (!peer.pc.remoteDescription) {
        peer.pendingCandidates.push(candidate);
        return;
      }

      try {
        await peer.pc.addIceCandidate(candidate);
      } catch (error) {
        if (!peer.ignoreOffer) {
          throw error;
        }
      }
    }
  }

  /**
   * Closes all peer connections and clears in-memory state when the room session ends.
   */
  dispose(): void {
    this.peers.forEach((peer) => {
      peer.pc.close();
    });
    this.peers.clear();
    this.peerNames.clear();
    this.localFeeds.clear();
    this.identity = null;
    this.signalPublisher = null;
    this.remoteFeedUpserter = null;
    this.remoteFeedRemover = null;
    this.remoteOwnerCleaner = null;
  }

  /**
   * Creates or returns a peer connection for a remote participant and wires all browser callbacks.
   */
  private ensurePeer(remoteClientId: string): PeerState {
    const existing = this.peers.get(remoteClientId);
    if (existing) {
      return existing;
    }

    const pc = new RTCPeerConnection({
      iceServers: [{ urls: WebrtcMeshService.DEFAULT_STUN_SERVERS }]
    });

    const peerState: PeerState = {
      pc,
      makingOffer: false,
      ignoreOffer: false,
      polite: this.isPolite(remoteClientId),
      pendingCandidates: [],
      needsOffer: false
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.publishSignal(remoteClientId, {
          candidate: event.candidate.toJSON()
        });
      }
    };

    pc.ontrack = (event) => {
      if (!this.identity || !this.remoteFeedUpserter || event.track.kind !== 'video') {
        return;
      }

      const resolvedStream = event.streams[0] ?? new MediaStream([event.track]);

      // Browsers can fire ontrack before the track becomes unmuted, so reuse the same publish step
      // on both callbacks to avoid missing a remote tile on slower negotiations.
      const publishRemoteFeed = () => this.publishRemoteTrack(remoteClientId, event.track, resolvedStream);

      publishRemoteFeed();
      event.track.onunmute = () => {
        publishRemoteFeed();
      };

      event.track.onended = () => {
        if (this.remoteFeedRemover) {
          this.remoteFeedRemover(remoteClientId, event.track.id);
        }
      };
    };

    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed' || pc.connectionState === 'disconnected') {
        if (this.remoteOwnerCleaner) {
          this.remoteOwnerCleaner(remoteClientId);
        }
      }
    };

    this.localFeeds.forEach((feed) => {
      this.addStreamToPeer(pc, feed.stream);
    });

    this.peers.set(remoteClientId, peerState);
    return peerState;
  }

  /**
   * Marks that a peer needs renegotiation. Actual offer creation is serialized in flushQueuedOffer.
   */
  private requestOffer(remoteClientId: string): void {
    const peer = this.ensurePeer(remoteClientId);
    peer.needsOffer = true;
    this.flushQueuedOffer(remoteClientId).catch((error) => {
      console.error('Offer update mislukt', error);
    });
  }

  /**
   * Prevents overlapping offers by serializing renegotiation attempts per peer.
   */
  private async flushQueuedOffer(remoteClientId: string): Promise<void> {
    const peer = this.ensurePeer(remoteClientId);
    if (!peer.needsOffer || peer.makingOffer || peer.pc.signalingState !== 'stable') {
      return;
    }

    peer.needsOffer = false;
    try {
      await this.makeOffer(remoteClientId);
    } catch (error) {
      peer.needsOffer = true;
      throw error;
    } finally {
      const latest = this.peers.get(remoteClientId);
      if (!latest || !latest.needsOffer || latest.makingOffer || latest.pc.signalingState !== 'stable') {
        return;
      }

      queueMicrotask(() => {
        this.flushQueuedOffer(remoteClientId).catch((flushError) => {
          console.error('Gequeue-de offer update mislukt', flushError);
        });
      });
    }
  }

  /**
   * Creates and publishes a local SDP offer when the connection is in a stable state.
   */
  private async makeOffer(remoteClientId: string): Promise<void> {
    const peer = this.ensurePeer(remoteClientId);
    if (peer.makingOffer || peer.pc.signalingState !== 'stable') {
      peer.needsOffer = true;
      return;
    }

    try {
      peer.makingOffer = true;
      await peer.pc.setLocalDescription(await peer.pc.createOffer());
      this.publishSignal(remoteClientId, {
        description: this.serializeDescription(peer.pc.localDescription)
      });
    } finally {
      peer.makingOffer = false;
    }
  }

  /**
   * Emits a signaling message back through the broker-facing callback.
   */
  private publishSignal(targetClientId: string, signal: WebrtcSignalPayload['signal']): void {
    if (!this.signalPublisher) {
      return;
    }

    this.signalPublisher({
      targetClientId,
      signal
    });
  }

  /**
   * Browser-native RTCSessionDescription objects are not consistently JSON-serializable across engines.
   */
  private serializeDescription(description: RTCSessionDescription | RTCSessionDescriptionInit | null | undefined): RTCSessionDescriptionInit | undefined {
    if (!description || typeof description.type !== 'string') {
      return undefined;
    }

    return {
      type: description.type,
      sdp: typeof description.sdp === 'string' ? description.sdp : undefined
    };
  }

  private normalizeDescription(description: RTCSessionDescriptionInit | undefined): RTCSessionDescriptionInit | null {
    if (!description || typeof description.type !== 'string') {
      return null;
    }

    return {
      type: description.type,
      sdp: typeof description.sdp === 'string' ? description.sdp : undefined
    };
  }

  /**
   * Tie-breaker for perfect negotiation. Only one side should ignore colliding offers.
   */
  private isPolite(remoteClientId: string): boolean {
    if (!this.identity) {
      return true;
    }

    return this.identity.clientId.localeCompare(remoteClientId) > 0;
  }

  /**
   * Adds all tracks of a local stream to a peer connection.
   */
  private addStreamToPeer(pc: RTCPeerConnection, stream: MediaStream): void {
    stream.getTracks().forEach((track) => {
      const reusableTransceiver = pc.getTransceivers().find((transceiver) =>
        transceiver.sender
        && !transceiver.sender.track
        && transceiver.receiver.track?.kind === track.kind
      );

      if (reusableTransceiver) {
        reusableTransceiver.direction = 'sendrecv';
        void reusableTransceiver.sender.replaceTrack(track).catch((error) => {
          console.error('Reusing RTCRtpSender failed, falling back to addTrack', error);
          pc.addTrack(track, stream);
        });
        return;
      }

      pc.addTrack(track, stream);
    });
  }

  /**
   * Removes only the senders belonging to a specific local stream.
   */
  private removeStreamFromPeer(pc: RTCPeerConnection, stream: MediaStream): void {
    const streamTracks = new Set(stream.getTracks());
    pc.getSenders().forEach((sender) => {
      if (sender.track && streamTracks.has(sender.track)) {
        const transceiver = pc.getTransceivers().find((item) => item.sender === sender);
        if (transceiver) {
          transceiver.direction = 'recvonly';
        }

        pc.removeTrack(sender);
      }
    });
  }

  /**
   * Converts a browser track callback into the UI feed shape consumed by RoomSessionService.
   */
  private publishRemoteTrack(remoteClientId: string, track: MediaStreamTrack, stream?: MediaStream): void {
    if (!this.remoteFeedUpserter) {
      return;
    }

    const remoteName = this.peerNames.get(remoteClientId) || 'Remote guest';
    const resolvedStream = stream && stream.getVideoTracks().length > 0
      ? stream
      : new MediaStream([track]);
    this.remoteFeedUpserter({
      id: `remote-${remoteClientId}-${track.id}`,
      ownerId: remoteClientId,
      ownerName: remoteName,
      trackId: track.id,
      label: `${remoteName} camera`,
      stream: resolvedStream,
      local: false,
      muted: false,
      audioEnabled: true,
      online: true
    });
  }
}
