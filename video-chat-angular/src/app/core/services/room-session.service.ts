import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { CameraFeed, ChatMessage, ChatMessagePayload, RoomEvent } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
/**
 * Local UI/session state store for the active room.
 *
 * This service is intentionally transport-agnostic: it does not know whether updates came from
 * RSocket, WebRTC, or local user input. That keeps rendering state separate from signaling logic.
 */
export class RoomSessionService {
  /**
   * Chat history is bounded so long sessions do not grow memory usage unbounded in the browser.
   */
  private static readonly MAX_MESSAGES = 200;

  private readonly feedsSubject = new BehaviorSubject<CameraFeed[]>([]);
  private readonly messagesSubject = new BehaviorSubject<ChatMessage[]>([]);

  readonly feeds$: Observable<CameraFeed[]> = this.feedsSubject.asObservable();
  readonly messages$: Observable<ChatMessage[]> = this.messagesSubject.asObservable();

  /**
   * Adds a locally published camera feed to the rendered grid.
   */
  addLocalFeed(feed: CameraFeed): void {
    this.updateFeeds((feeds) => [...feeds, feed]);
  }

  /**
   * Removes a feed by id and returns it so the caller can stop any attached media stream if needed.
   */
  removeFeed(feedId: string): CameraFeed | null {
    const feeds = [...this.feedsSubject.value];
    const index = feeds.findIndex((item) => item.id === feedId);

    if (index === -1) {
      return null;
    }

    const removed = feeds[index];
    feeds.splice(index, 1);
    this.feedsSubject.next(feeds);
    return removed;
  }

  /**
   * Inserts or replaces a remote feed. WebRTC renegotiation can recreate the same remote feed id.
   */
  upsertRemoteFeed(feed: CameraFeed): void {
    this.updateFeeds((feeds) => {
      const nextFeeds = [...feeds];
      const index = nextFeeds.findIndex((item) => item.id === feed.id);
      if (index === -1) {
        nextFeeds.push(feed);
      } else {
        nextFeeds[index] = feed;
      }

      return nextFeeds;
    });
  }

  /**
   * Clears all remote feeds for a peer when they leave or their peer connection collapses.
   */
  removeRemoteFeedsByOwner(ownerId: string): void {
    this.updateFeeds((feeds) => feeds.filter((feed) => !(feed.ownerId === ownerId && !feed.local)));
  }

  /**
   * Removes a single remote track-backed feed without touching other feeds owned by the same peer.
   */
  removeRemoteTrackFeed(ownerId: string, trackId: string): void {
    const feedId = this.createRemoteTrackFeedId(ownerId, trackId);
    this.removeFeed(feedId);
  }

  /**
   * Appends a chat message while enforcing the history cap.
   */
  appendMessage(message: ChatMessage): void {
    this.updateMessages((messages) => [...messages, message].slice(-RoomSessionService.MAX_MESSAGES));
  }

  /**
   * Applies broker-level room events that have direct UI meaning.
   */
  consumeRoomEvent(event: RoomEvent, localClientId: string): void {
    const chatPayload = this.getChatMessagePayload(event);
    if (chatPayload) {
      this.appendMessage({
        id: this.createId('msg'),
        roomId: event.roomId,
        senderId: event.senderId,
        senderName: event.senderName,
        text: chatPayload.text,
        sentAt: event.sentAt,
        local: event.senderId === localClientId
      });
      return;
    }

    if (event.type === 'ROOM_LEFT') {
      this.removeRemoteFeedsByOwner(event.senderId);
    }
  }

  /**
   * Feed ids derived from owner/track pairs let WebRTC callbacks remove the same tile they created.
   */
  createRemoteTrackFeedId(ownerId: string, trackId: string): string {
    return 'remote-' + ownerId + '-' + trackId;
  }

  /**
   * Clears transient room UI state when disconnecting or leaving a room.
   */
  reset(): void {
    this.feedsSubject.next([]);
    this.messagesSubject.next([]);
  }

  /**
   * Small client-side id generator used for optimistic local entities such as messages and feeds.
   */
  createId(prefix: string): string {
    return prefix + '-' + Math.random().toString(36).substring(2, 11);
  }

  private updateFeeds(updater: (feeds: CameraFeed[]) => CameraFeed[]): void {
    this.feedsSubject.next(updater(this.feedsSubject.value));
  }

  private updateMessages(updater: (messages: ChatMessage[]) => ChatMessage[]): void {
    this.messagesSubject.next(updater(this.messagesSubject.value));
  }

  /**
   * Validates that an incoming room event really contains the chat payload shape expected by the UI.
   */
  private getChatMessagePayload(event: RoomEvent): ChatMessagePayload | null {
    if (
      event.type !== 'CHAT_MESSAGE'
      || !event.payload
      || typeof event.payload !== 'object'
      || !('text' in event.payload)
      || typeof event.payload.text !== 'string'
    ) {
      return null;
    }

    return { text: event.payload.text };
  }
}
