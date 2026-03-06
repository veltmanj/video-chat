import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { CameraFeed, ChatMessage, RoomEvent } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
export class RoomSessionService {
  private readonly feedsSubject = new BehaviorSubject<CameraFeed[]>([]);
  private readonly messagesSubject = new BehaviorSubject<ChatMessage[]>([]);

  readonly feeds$: Observable<CameraFeed[]> = this.feedsSubject.asObservable();
  readonly messages$: Observable<ChatMessage[]> = this.messagesSubject.asObservable();

  addLocalFeed(feed: CameraFeed): void {
    const feeds = this.feedsSubject.value.slice();
    feeds.push(feed);
    this.feedsSubject.next(feeds);
  }

  removeFeed(feedId: string): CameraFeed | null {
    const feeds = this.feedsSubject.value.slice();
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
    const feeds = this.feedsSubject.value.slice();
    const index = feeds.findIndex((item) => item.id === feed.id);

    if (index !== -1) {
      feeds[index] = feed;
    } else {
      feeds.push(feed);
    }

    this.feedsSubject.next(feeds);
  }

  removeRemoteFeedsByOwner(ownerId: string): void {
    const feeds = this.feedsSubject.value.filter((feed) => !(feed.ownerId === ownerId && !feed.local));
    this.feedsSubject.next(feeds);
  }

  removeRemoteTrackFeed(ownerId: string, trackId: string): void {
    const feedId = this.createRemoteTrackFeedId(ownerId, trackId);
    this.removeFeed(feedId);
  }

  appendMessage(message: ChatMessage): void {
    const messages = this.messagesSubject.value.slice();
    messages.push(message);
    this.messagesSubject.next(messages.slice(-200));
  }

  consumeRoomEvent(event: RoomEvent, localClientId: string): void {
    if (event.type === 'CHAT_MESSAGE' && event.payload && event.payload.text) {
      this.appendMessage({
        id: this.createId('msg'),
        roomId: event.roomId,
        senderId: event.senderId,
        senderName: event.senderName,
        text: event.payload.text,
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
}
