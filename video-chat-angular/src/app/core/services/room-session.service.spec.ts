import { beforeEach, describe, expect, it } from 'vitest';
import { RoomSessionService } from './room-session.service';
import { CameraFeed, RoomEvent } from '../models/room.models';

describe('RoomSessionService', () => {
  let service: RoomSessionService;

  beforeEach(() => {
    service = new RoomSessionService();
  });

  it('adds and removes feeds', () => {
    const feed: CameraFeed = {
      id: 'feed-1',
      ownerId: 'owner-1',
      ownerName: 'Owner 1',
      label: 'Cam 1',
      local: true,
      muted: true,
      online: true
    };

    service.addLocalFeed(feed);
    let feedsSnapshot: CameraFeed[] = [];
    service.feeds$.subscribe((feeds) => (feedsSnapshot = feeds)).unsubscribe();
    expect(feedsSnapshot.length).toBe(1);

    const removed = service.removeFeed('feed-1');
    expect(removed?.id).toBe('feed-1');
    service.feeds$.subscribe((feeds) => (feedsSnapshot = feeds)).unsubscribe();
    expect(feedsSnapshot.length).toBe(0);
  });

  it('consumes chat room event into message stream', () => {
    const event: RoomEvent = {
      type: 'CHAT_MESSAGE',
      roomId: 'room-1',
      senderId: 'remote-1',
      senderName: 'Remote',
      sentAt: new Date().toISOString(),
      payload: { text: 'hello' }
    };

    service.consumeRoomEvent(event, 'local-1');

    let messageCount = 0;
    let latestText = '';
    service.messages$.subscribe((messages) => {
      messageCount = messages.length;
      latestText = messages[messages.length - 1]?.text || '';
    }).unsubscribe();

    expect(messageCount).toBe(1);
    expect(latestText).toBe('hello');
  });

  it('keeps a remote tile and marks it offline when CAMERA_REMOVED arrives', () => {
    const publishedEvent: RoomEvent = {
      type: 'CAMERA_PUBLISHED',
      roomId: 'room-1',
      senderId: 'remote-1',
      senderName: 'Remote',
      sentAt: new Date().toISOString(),
      payload: {
        feedId: 'feed-remote-1',
        label: 'Remote camera'
      }
    };

    service.consumeRoomEvent(publishedEvent, 'local-1');
    service.upsertRemoteFeed({
      id: service.createRemoteTrackFeedId('remote-1', 'track-1'),
      ownerId: 'remote-1',
      ownerName: 'Remote',
      trackId: 'track-1',
      label: 'Remote fallback label',
      stream: {} as MediaStream,
      local: false,
      muted: true,
      online: true
    });

    const removedEvent: RoomEvent = {
      type: 'CAMERA_REMOVED',
      roomId: 'room-1',
      senderId: 'remote-1',
      senderName: 'Remote',
      sentAt: new Date().toISOString(),
      payload: {
        feedId: 'feed-remote-1'
      }
    };

    service.consumeRoomEvent(removedEvent, 'local-1');

    let feedsSnapshot: CameraFeed[] = [];
    service.feeds$.subscribe((feeds) => (feedsSnapshot = feeds)).unsubscribe();

    expect(feedsSnapshot).toEqual([
      expect.objectContaining({
        id: service.createRemotePublishedFeedId('remote-1', 'feed-remote-1'),
        ownerId: 'remote-1',
        publishedFeedId: 'feed-remote-1',
        trackId: undefined,
        label: 'Remote camera',
        stream: undefined,
        online: false
      })
    ]);
  });

  it('marks a published remote tile offline when the underlying track ends', () => {
    service.consumeRoomEvent({
      type: 'CAMERA_PUBLISHED',
      roomId: 'room-1',
      senderId: 'remote-1',
      senderName: 'Remote',
      sentAt: new Date().toISOString(),
      payload: {
        feedId: 'feed-remote-1',
        label: 'Remote camera'
      }
    }, 'local-1');

    service.upsertRemoteFeed({
      id: service.createRemoteTrackFeedId('remote-1', 'track-1'),
      ownerId: 'remote-1',
      ownerName: 'Remote',
      trackId: 'track-1',
      label: 'Remote camera',
      stream: {} as MediaStream,
      local: false,
      muted: true,
      online: true
    });

    service.removeRemoteTrackFeed('remote-1', 'track-1');

    let feedsSnapshot: CameraFeed[] = [];
    service.feeds$.subscribe((feeds) => (feedsSnapshot = feeds)).unsubscribe();

    expect(feedsSnapshot).toEqual([
      expect.objectContaining({
        ownerId: 'remote-1',
        publishedFeedId: 'feed-remote-1',
        stream: undefined,
        online: false
      })
    ]);
  });

  it('merges a late CAMERA_PUBLISHED event into an existing remote track tile', () => {
    service.upsertRemoteFeed({
      id: service.createRemoteTrackFeedId('remote-1', 'track-1'),
      ownerId: 'remote-1',
      ownerName: 'Remote',
      trackId: 'track-1',
      label: 'Remote fallback label',
      stream: {} as MediaStream,
      local: false,
      muted: true,
      online: true
    });

    service.consumeRoomEvent({
      type: 'CAMERA_PUBLISHED',
      roomId: 'room-1',
      senderId: 'remote-1',
      senderName: 'Remote',
      sentAt: new Date().toISOString(),
      payload: {
        feedId: 'feed-remote-1',
        label: 'FaceTime HD Camera'
      }
    }, 'local-1');

    let feedsSnapshot: CameraFeed[] = [];
    service.feeds$.subscribe((feeds) => (feedsSnapshot = feeds)).unsubscribe();

    expect(feedsSnapshot).toHaveLength(1);
    expect(feedsSnapshot[0]).toEqual(expect.objectContaining({
      id: service.createRemotePublishedFeedId('remote-1', 'feed-remote-1'),
      ownerId: 'remote-1',
      publishedFeedId: 'feed-remote-1',
      trackId: 'track-1',
      stream: expect.any(Object),
      online: true
    }));
  });

});
