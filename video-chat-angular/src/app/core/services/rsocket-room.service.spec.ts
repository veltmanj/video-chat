import { ConnectionState } from '../models/room.models';
import { RsocketRoomService } from './rsocket-room.service';
import { vi } from 'vitest';

describe('RsocketRoomService', () => {
  let service: RsocketRoomService;
  let states: ConnectionState[];

  beforeEach(() => {
    service = new RsocketRoomService();
    states = [];
    service.state$.subscribe((state) => {
      states.push(state);
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    service.disconnect();
  });

  it('transitions to FAILED when broker connect hangs beyond timeout', async () => {
    vi.useFakeTimers();
    vi.spyOn(service as any, 'createConnection').mockImplementation(() => new Promise(() => {
      return;
    }));

    const connectPromise = service.connect(
      { clientId: 'client-a', displayName: 'Alice' },
      'room-a',
      ['wss://broker-timeout/rsocket']
    );

    await vi.advanceTimersByTimeAsync(20001);

    await expect(connectPromise).rejects.toThrow(/timeout/);
    expect(states).toContain('CONNECTING');
    expect(states[states.length - 1]).toBe('FAILED');
  });

  it('tries next broker URL after timeout and reaches CONNECTED', async () => {
    vi.useFakeTimers();
    const streamSubscription = {
      request: vi.fn(),
      cancel: vi.fn()
    };
    const socket = {
      requestStream: vi.fn().mockReturnValue({
        subscribe: (subscriber: any) => {
          if (subscriber.onSubscribe) {
            subscriber.onSubscribe(streamSubscription);
          }
        }
      }),
      fireAndForget: vi.fn(),
      close: vi.fn(),
      connection: {
        close: vi.fn()
      }
    };

    vi.spyOn(service as any, 'createConnection').mockImplementation((url) => {
      if (String(url).includes('dead-broker')) {
        return new Promise(() => {
          return;
        });
      }
      return Promise.resolve(socket);
    });

    const connectPromise = service.connect(
      { clientId: 'client-b', displayName: 'Bob' },
      'room-b',
      ['wss://dead-broker/rsocket', 'wss://healthy-broker/rsocket']
    );

    await vi.advanceTimersByTimeAsync(20001);

    await expect(connectPromise).resolves.toBeUndefined();
    expect(states[states.length - 1]).toBe('CONNECTED');
    expect(socket.requestStream).toHaveBeenCalled();
    expect(streamSubscription.request).toHaveBeenCalled();
    expect(socket.fireAndForget).toHaveBeenCalled();
  });

  it('filters invalid broker URLs and connects with the first valid unique endpoint', async () => {
    const socket = {
      requestStream: vi.fn().mockReturnValue({
        subscribe: (subscriber: any) => {
          if (subscriber.onSubscribe) {
            subscriber.onSubscribe({
              request: () => {
                return;
              },
              cancel: () => {
                return;
              }
            });
          }
        }
      }),
      fireAndForget: vi.fn(),
      close: vi.fn(),
      connection: {
        close: vi.fn()
      }
    };

    const createConnectionSpy = vi.spyOn(service as any, 'createConnection').mockResolvedValue(socket);
    const connectPromise = service.connect(
      { clientId: 'client-c', displayName: 'Carol' },
      'room-c',
      ['   ', 'http://invalid', 'not-a-url', 'wss://healthy-broker/rsocket', 'wss://healthy-broker/rsocket']
    );

    await expect(connectPromise).resolves.toBeUndefined();

    expect(states[states.length - 1]).toBe('CONNECTED');
    expect(createConnectionSpy).toHaveBeenCalledTimes(1);
    expect(createConnectionSpy).toHaveBeenCalledWith('wss://healthy-broker/rsocket');
  });

  it('keeps RECONNECTING state during reconnect attempts even when all endpoints timeout', async () => {
    vi.useFakeTimers();
    vi.spyOn(service as any, 'createConnection').mockImplementation(() => new Promise(() => {
      return;
    }));

    const connectPromise = service.connect(
      { clientId: 'client-d', displayName: 'Dave' },
      'room-d',
      ['wss://dead-broker/rsocket'],
      true
    );

    await vi.advanceTimersByTimeAsync(20001);

    await expect(connectPromise).rejects.toThrow(/timeout/);
    expect(states).toContain('RECONNECTING');
    expect(states[states.length - 1]).toBe('RECONNECTING');
  });
});
