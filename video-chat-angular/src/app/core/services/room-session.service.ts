import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { CameraFeed, ChatMessage, ChatMessagePayload, RoomEvent } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
export class RoomSessionService {
  private static readonly MAX_MESSAGES = 200;

  private readonly feedsSubject = new BehaviorSubject<CameraFeed[]>([]);
  private readonly messagesSubject = new BehaviorSubject<ChatMessage[]>([]);

  readonly feeds$: Observable<CameraFeed[]> = this.feedsSubject.asObservable();
  readonly messages$: Observable<ChatMessage[]> = this.messagesSubject.asObservable();

  addLocalFeed(feed: CameraFeed): void {
    this.updateFeeds((feeds) => [...feeds, feed]);
  }

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

  removeRemoteFeedsByOwner(ownerId: string): void {
    this.updateFeeds((feeds) => feeds.filter((feed) => !(feed.ownerId === ownerId && !feed.local)));
  }

  removeRemoteTrackFeed(ownerId: string, trackId: string): void {
    const feedId = this.createRemoteTrackFeedId(ownerId, trackId);
    this.removeFeed(feedId);
  }

  appendMessage(message: ChatMessage): void {
    this.updateMessages((messages) => [...messages, message].slice(-RoomSessionService.MAX_MESSAGES));
  }

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

  createRemoteTrackFeedId(ownerId: string, trackId: string): string {
    return 'remote-' + ownerId + '-' + trackId;
  }

  reset(): void {
    this.feedsSubject.next([]);
    this.messagesSubject.next([]);
  }

  createId(prefix: string): string {
    return prefix + '-' + Math.random().toString(36).substring(2, 11);
  }

  private updateFeeds(updater: (feeds: CameraFeed[]) => CameraFeed[]): void {
    this.feedsSubject.next(updater(this.feedsSubject.value));
  }

  private updateMessages(updater: (messages: ChatMessage[]) => ChatMessage[]): void {
    this.messagesSubject.next(updater(this.messagesSubject.value));
  }

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
