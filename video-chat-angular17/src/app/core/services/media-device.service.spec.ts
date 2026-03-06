import { MediaDeviceService } from './media-device.service';

describe('MediaDeviceService', () => {
  let service: MediaDeviceService;
  const originalMediaDevices = (navigator as any).mediaDevices;

  beforeEach(() => {
    service = new MediaDeviceService();
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'mediaDevices', {
      value: originalMediaDevices,
      configurable: true
    });
  });

  it('lists only video input devices with fallback label', async () => {
    const getUserMedia = jasmine.createSpy();
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        enumerateDevices: jasmine.createSpy().and.resolveTo([
          { kind: 'audioinput', deviceId: 'mic-1', label: 'Mic 1' },
          { kind: 'videoinput', deviceId: 'cam-1', label: '' },
          { kind: 'videoinput', deviceId: 'cam-2', label: 'Camera 2' }
        ]),
        getUserMedia
      },
      configurable: true
    });

    const devices = await service.listVideoInputs();
    expect(devices.length).toBe(2);
    expect(devices[0].label).toBe('Camera 1');
    expect(devices[1].label).toBe('Camera 2');
    expect(getUserMedia).not.toHaveBeenCalled();
  });

  it('probes permission when only unnamed video devices are initially visible', async () => {
    const enumerateDevices = jasmine.createSpy().and.returnValues(
      Promise.resolve([{ kind: 'videoinput', deviceId: 'cam-1', label: '' }]),
      Promise.resolve([
        { kind: 'videoinput', deviceId: 'cam-1', label: 'FaceTime HD Camera' },
        { kind: 'videoinput', deviceId: 'obs-virtual', label: 'OBS Virtual Camera' },
        { kind: 'videoinput', deviceId: 'snap-virtual', label: 'Snap Camera' }
      ])
    );
    const stop = jasmine.createSpy('stop');
    const getTracks = jasmine.createSpy('getTracks').and.returnValue([{ stop }]);
    const getUserMedia = jasmine.createSpy().and.resolveTo({ getTracks } as any);

    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        enumerateDevices,
        getUserMedia
      },
      configurable: true
    });

    const devices = await service.listVideoInputs();

    expect(getUserMedia).toHaveBeenCalledWith({ audio: false, video: true });
    expect(enumerateDevices).toHaveBeenCalledTimes(2);
    expect(devices.map((item) => item.label)).toEqual([
      'FaceTime HD Camera',
      'OBS Virtual Camera',
      'Snap Camera'
    ]);
    expect(stop).toHaveBeenCalled();
  });

  it('retries enumerate after permission probe when no cameras are visible yet', async () => {
    const enumerateDevices = jasmine.createSpy().and.returnValues(
      Promise.resolve([{ kind: 'audioinput', deviceId: 'mic-1', label: 'Mic 1' }]),
      Promise.resolve([
        { kind: 'videoinput', deviceId: 'obs-virtual', label: 'OBS Virtual Camera' },
        { kind: 'videoinput', deviceId: 'snap-virtual', label: 'Snap Camera' }
      ])
    );
    const stop = jasmine.createSpy('stop');
    const getTracks = jasmine.createSpy('getTracks').and.returnValue([{ stop }]);
    const getUserMedia = jasmine.createSpy().and.resolveTo({ getTracks } as any);

    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        enumerateDevices,
        getUserMedia
      },
      configurable: true
    });

    const devices = await service.listVideoInputs();

    expect(getUserMedia).toHaveBeenCalledWith({ audio: false, video: true });
    expect(enumerateDevices).toHaveBeenCalledTimes(2);
    expect(devices.map((item) => item.label)).toEqual(['OBS Virtual Camera', 'Snap Camera']);
    expect(stop).toHaveBeenCalled();
  });

  it('starts camera with expected constraints', async () => {
    const getUserMedia = jasmine.createSpy().and.resolveTo({ id: 'stream-1' } as any);
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: jasmine.createSpy().and.resolveTo([])
      },
      configurable: true
    });

    await service.startCamera('cam-22');

    expect(getUserMedia).toHaveBeenCalled();
    const constraints = getUserMedia.calls.mostRecent().args[0];
    expect(constraints.video.deviceId.exact).toBe('cam-22');
    expect(constraints.audio).toBeFalse();
  });

  it('retries startCamera with minimal constraints when strict constraints fail', async () => {
    const overConstrainedError = { name: 'OverconstrainedError' };
    const getUserMedia = jasmine.createSpy().and.returnValues(
      Promise.reject(overConstrainedError),
      Promise.resolve({ id: 'stream-2' } as any)
    );

    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: jasmine.createSpy().and.resolveTo([])
      },
      configurable: true
    });

    await service.startCamera('obs-virtual');

    expect(getUserMedia).toHaveBeenCalledTimes(2);
    const retryConstraints = getUserMedia.calls.argsFor(1)[0];
    expect(retryConstraints.video.deviceId.exact).toBe('obs-virtual');
    expect(retryConstraints.audio).toBeFalse();
  });

  it('stops all stream tracks', () => {
    const stopA = jasmine.createSpy('stopA');
    const stopB = jasmine.createSpy('stopB');
    const stream = {
      getTracks: () => [{ stop: stopA }, { stop: stopB }]
    } as any;

    service.stopCamera(stream);

    expect(stopA).toHaveBeenCalled();
    expect(stopB).toHaveBeenCalled();
  });
});
