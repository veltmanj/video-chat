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
});
