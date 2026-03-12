import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import {
  AiMessagePayload,
  CameraFeed,
  CameraPublishedPayload,
  CameraRemovedPayload,
  ChatMessage,
  ChatMessagePayload,
  RoomEvent
} from '../models/room.models';

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
        const placeholderIndex = !feed.local
          ? this.findRemotePublishedFeedIndex(nextFeeds, feed)
          : -1;

        if (placeholderIndex !== -1) {
          nextFeeds[placeholderIndex] = {
            ...nextFeeds[placeholderIndex],
            ...feed,
            id: nextFeeds[placeholderIndex].id,
            publishedFeedId: nextFeeds[placeholderIndex].publishedFeedId ?? feed.publishedFeedId,
            label: nextFeeds[placeholderIndex].label || feed.label,
            online: true
          };
        } else {
          nextFeeds.push(feed);
        }
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
    this.updateFeeds((feeds) => feeds.flatMap((feed) => {
      if (feed.ownerId !== ownerId || feed.local || feed.trackId !== trackId) {
        return [feed];
      }

      if (feed.publishedFeedId) {
        return [{
          ...feed,
          stream: undefined,
          trackId: undefined,
          online: false
        }];
      }

      return [];
    }));
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

    const aiPayload = this.getAiMessagePayload(event);
    if (aiPayload) {
      this.appendMessage({
        id: this.createId('msg'),
        roomId: event.roomId,
        senderId: event.senderId,
        senderName: event.senderName,
        text: aiPayload.text,
        sentAt: event.sentAt,
        local: false,
        role: 'assistant'
      });
      return;
    }

    const cameraPublishedPayload = this.getCameraPublishedPayload(event);
    if (cameraPublishedPayload && event.senderId !== localClientId) {
      if (this.attachPublishedFeedToExistingRemoteTrack(event.senderId, cameraPublishedPayload)) {
        return;
      }

      this.upsertRemoteFeed({
        id: this.createRemotePublishedFeedId(event.senderId, cameraPublishedPayload.feedId),
        ownerId: event.senderId,
        ownerName: event.senderName,
        ownerProfileImageUrl: cameraPublishedPayload.profileImageUrl ?? null,
        publishedFeedId: cameraPublishedPayload.feedId,
        label: cameraPublishedPayload.label || `${event.senderName} camera`,
        local: false,
        muted: true,
        online: true
      });
      return;
    }

    const cameraRemovedPayload = this.getCameraRemovedPayload(event);
    if (cameraRemovedPayload && event.senderId !== localClientId) {
      this.markRemoteFeedStopped(event.senderId, cameraRemovedPayload.feedId);
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

  createRemotePublishedFeedId(ownerId: string, publishedFeedId: string): string {
    return 'remote-feed-' + ownerId + '-' + publishedFeedId;
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

  private getAiMessagePayload(event: RoomEvent): AiMessagePayload | null {
    if (
      event.type !== 'AI_MESSAGE'
      || !event.payload
      || typeof event.payload !== 'object'
      || !('text' in event.payload)
      || typeof event.payload.text !== 'string'
    ) {
      return null;
    }

    return { text: event.payload.text };
  }

  private getCameraPublishedPayload(event: RoomEvent): CameraPublishedPayload | null {
    if (
      event.type !== 'CAMERA_PUBLISHED'
      || !event.payload
      || typeof event.payload !== 'object'
      || !('feedId' in event.payload)
      || typeof event.payload.feedId !== 'string'
    ) {
      return null;
    }

    const label = 'label' in event.payload && typeof event.payload.label === 'string'
      ? event.payload.label
      : `${event.senderName} camera`;

    return {
      feedId: event.payload.feedId,
      deviceId: 'deviceId' in event.payload && typeof event.payload.deviceId === 'string'
        ? event.payload.deviceId
        : undefined,
      profileImageUrl: 'profileImageUrl' in event.payload
        && (typeof event.payload.profileImageUrl === 'string' || event.payload.profileImageUrl === null)
        ? event.payload.profileImageUrl
        : undefined,
      label
    };
  }

  private getCameraRemovedPayload(event: RoomEvent): CameraRemovedPayload | null {
    if (
      event.type !== 'CAMERA_REMOVED'
      || !event.payload
      || typeof event.payload !== 'object'
      || !('feedId' in event.payload)
      || typeof event.payload.feedId !== 'string'
    ) {
      return null;
    }

    return { feedId: event.payload.feedId };
  }

  private markRemoteFeedStopped(ownerId: string, publishedFeedId: string): void {
    this.updateFeeds((feeds) => feeds.map((feed) => {
      if (
        feed.local
        || feed.ownerId !== ownerId
        || feed.publishedFeedId !== publishedFeedId
      ) {
        return feed;
      }

      return {
        ...feed,
        stream: undefined,
        trackId: undefined,
        online: false
      };
    }));
  }

  private attachPublishedFeedToExistingRemoteTrack(ownerId: string, payload: CameraPublishedPayload): boolean {
    let matchedFeed = false;

    this.updateFeeds((feeds) => {
      const candidateFeeds = feeds.filter((feed) =>
        !feed.local
        && feed.ownerId === ownerId
        && !feed.publishedFeedId
        && !!feed.stream
      );

      if (candidateFeeds.length !== 1) {
        return feeds;
      }

      matchedFeed = true;
      return feeds.map((feed) => {
        if (feed !== candidateFeeds[0]) {
          return feed;
        }

        return {
          ...feed,
          id: this.createRemotePublishedFeedId(ownerId, payload.feedId),
          publishedFeedId: payload.feedId,
          ownerProfileImageUrl: payload.profileImageUrl ?? feed.ownerProfileImageUrl ?? null,
          label: payload.label || `${feed.ownerName} camera`
        };
      });
    });

    return matchedFeed;
  }

  private findRemotePublishedFeedIndex(feeds: CameraFeed[], feed: CameraFeed): number {
    const onlinePlaceholderFeeds = feeds
      .map((item, index) => ({ item, index }))
      .filter(({ item }) =>
        !item.local
        && item.ownerId === feed.ownerId
        && !!item.publishedFeedId
        && item.online
        && !item.stream
      );

    if (onlinePlaceholderFeeds.length === 1) {
      return onlinePlaceholderFeeds[0].index;
    }

    const publishedFeedsForOwner = feeds
      .map((item, index) => ({ item, index }))
      .filter(({ item }) =>
        !item.local
        && item.ownerId === feed.ownerId
        && !!item.publishedFeedId
        && item.online
      );

    return publishedFeedsForOwner.length === 1
      ? publishedFeedsForOwner[0].index
      : -1;
  }
}
