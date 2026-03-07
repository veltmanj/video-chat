export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'FAILED';

export interface BrokerEndpoint {
  name: string;
  url: string;
}

export interface ClientIdentity {
  clientId: string;
  displayName: string;
}

export interface CameraDevice {
  deviceId: string;
  label: string;
}

export interface CameraFeed {
  id: string;
  ownerId: string;
  ownerName: string;
  deviceId?: string;
  label: string;
  stream?: MediaStream;
  local: boolean;
  muted: boolean;
  online: boolean;
}

export interface ChatMessage {
  id: string;
  roomId: string;
  senderId: string;
  senderName: string;
  text: string;
  sentAt: string;
  local?: boolean;
}

export type RoomEventType =
  | 'ROOM_JOINED'
  | 'ROOM_LEFT'
  | 'CHAT_MESSAGE'
  | 'CAMERA_PUBLISHED'
  | 'CAMERA_REMOVED'
  | 'CAMERA_STATUS'
  | 'WEBRTC_SIGNAL'
  | 'HEARTBEAT';

export interface RoomJoinedPayload {
  capabilities: string[];
}

export interface ChatMessagePayload {
  text: string;
}

export interface CameraPublishedPayload {
  feedId: string;
  deviceId?: string;
  label: string;
}

export interface CameraRemovedPayload {
  feedId: string;
}

export interface CameraStatusPayload {
  feedId: string;
  online: boolean;
}

export interface HeartbeatPayload {
  timestamp?: string;
}

export interface RoomEventPayloadMap {
  ROOM_JOINED: RoomJoinedPayload;
  ROOM_LEFT: Record<string, never>;
  CHAT_MESSAGE: ChatMessagePayload;
  CAMERA_PUBLISHED: CameraPublishedPayload;
  CAMERA_REMOVED: CameraRemovedPayload;
  CAMERA_STATUS: CameraStatusPayload;
  WEBRTC_SIGNAL: WebrtcSignalPayload;
  HEARTBEAT: HeartbeatPayload;
}

export interface RoomEvent<TType extends RoomEventType = RoomEventType> {
  type: TType;
  roomId: string;
  senderId: string;
  senderName: string;
  sentAt: string;
  payload?: RoomEventPayloadMap[TType];
}

export interface WebrtcSignalPayload {
  targetClientId: string;
  signal: {
    description?: RTCSessionDescriptionInit;
    candidate?: RTCIceCandidateInit;
  };
}
