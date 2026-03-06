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

export interface RoomEvent {
  type: RoomEventType;
  roomId: string;
  senderId: string;
  senderName: string;
  sentAt: string;
  payload?: any;
}

export interface WebrtcSignalPayload {
  targetClientId: string;
  signal: {
    description?: RTCSessionDescriptionInit;
    candidate?: RTCIceCandidateInit;
  };
}
