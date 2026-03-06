import { fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { ConnectionState } from '../models/room.models';
import { RsocketRoomService } from './rsocket-room.service';

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
    service.disconnect();
  });

  it('transitions to FAILED when broker connect hangs beyond timeout', fakeAsync(() => {
    spyOn<any>(service, 'createConnection').and.returnValue(new Promise(() => {
      return;
    }));

    let settled = false;
    let rejection: any = null;

    service.connect(
      { clientId: 'client-a', displayName: 'Alice' },
      'room-a',
      ['wss://broker-timeout/rsocket']
    ).then(
      () => {
        settled = true;
      },
      (error) => {
        rejection = error;
        settled = true;
      }
    );

    tick(20001);
    flushMicrotasks();

    expect(settled).toBeTrue();
    expect(states).toContain('CONNECTING');
    expect(states[states.length - 1]).toBe('FAILED');
    expect(String(rejection)).toContain('timeout');
  }));

  it('tries next broker URL after timeout and reaches CONNECTED', fakeAsync(() => {
    const streamSubscription = {
      request: jasmine.createSpy('request'),
      cancel: jasmine.createSpy('cancel')
    };
    const socket = {
      requestStream: jasmine.createSpy('requestStream').and.returnValue({
        subscribe: (subscriber: any) => {
          if (subscriber.onSubscribe) {
            subscriber.onSubscribe(streamSubscription);
          }
        }
      }),
      fireAndForget: jasmine.createSpy('fireAndForget'),
      close: jasmine.createSpy('close'),
      connection: {
        close: jasmine.createSpy('connectionClose')
      }
    };

    spyOn<any>(service, 'createConnection').and.callFake((url: string) => {
      if (url.includes('dead-broker')) {
        return new Promise(() => {
          return;
        });
      }
      return Promise.resolve(socket);
    });

    let settled = false;
    service.connect(
      { clientId: 'client-b', displayName: 'Bob' },
      'room-b',
      ['wss://dead-broker/rsocket', 'wss://healthy-broker/rsocket']
    ).then(() => {
      settled = true;
    });

    tick(20001);
    flushMicrotasks();

    expect(settled).toBeTrue();
    expect(states[states.length - 1]).toBe('CONNECTED');
    expect(socket.requestStream).toHaveBeenCalled();
    expect(streamSubscription.request).toHaveBeenCalled();
    expect(socket.fireAndForget).toHaveBeenCalled();
  }));

  it('filters invalid broker URLs and connects with the first valid unique endpoint', fakeAsync(() => {
    const socket = {
      requestStream: jasmine.createSpy('requestStream').and.returnValue({
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
      fireAndForget: jasmine.createSpy('fireAndForget'),
      close: jasmine.createSpy('close'),
      connection: {
        close: jasmine.createSpy('connectionClose')
      }
    };

    const createConnectionSpy = spyOn<any>(service, 'createConnection').and.returnValue(Promise.resolve(socket));

    let settled = false;
    service.connect(
      { clientId: 'client-c', displayName: 'Carol' },
      'room-c',
      ['   ', 'http://invalid', 'not-a-url', 'wss://healthy-broker/rsocket', 'wss://healthy-broker/rsocket']
    ).then(() => {
      settled = true;
    });

    flushMicrotasks();

    expect(settled).toBeTrue();
    expect(createConnectionSpy).toHaveBeenCalledTimes(1);
    expect(createConnectionSpy).toHaveBeenCalledWith('wss://healthy-broker/rsocket');
    expect(states[states.length - 1]).toBe('CONNECTED');
  }));

  it('keeps RECONNECTING state during reconnect attempts even when all endpoints timeout', fakeAsync(() => {
    spyOn<any>(service, 'createConnection').and.returnValue(new Promise(() => {
      return;
    }));

    let settled = false;
    let rejection: any = null;

    service.connect(
      { clientId: 'client-d', displayName: 'Dave' },
      'room-d',
      ['wss://dead-broker/rsocket'],
      true
    ).then(
      () => {
        settled = true;
      },
      (error) => {
        rejection = error;
        settled = true;
      }
    );

    tick(20001);
    flushMicrotasks();

    expect(settled).toBeTrue();
    expect(states).toContain('RECONNECTING');
    expect(states[states.length - 1]).toBe('RECONNECTING');
    expect(String(rejection)).toContain('timeout');
  }));
});
