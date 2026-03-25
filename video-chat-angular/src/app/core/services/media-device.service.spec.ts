import { vi } from 'vitest';
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
    const getUserMedia = vi.fn();
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        enumerateDevices: vi.fn().mockResolvedValue([
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
    const enumerateDevices = vi.fn()
      .mockResolvedValueOnce([{ kind: 'videoinput', deviceId: 'cam-1', label: '' }])
      .mockResolvedValueOnce([
        { kind: 'videoinput', deviceId: 'cam-1', label: 'FaceTime HD Camera' },
        { kind: 'videoinput', deviceId: 'obs-virtual', label: 'OBS Virtual Camera' },
        { kind: 'videoinput', deviceId: 'snap-virtual', label: 'Snap Camera' }
      ]);
    const stop = vi.fn();
    const getTracks = vi.fn().mockReturnValue([{ stop }]);
    const getUserMedia = vi.fn().mockResolvedValue({ getTracks } as any);

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
    const enumerateDevices = vi.fn()
      .mockResolvedValueOnce([{ kind: 'audioinput', deviceId: 'mic-1', label: 'Mic 1' }])
      .mockResolvedValueOnce([
        { kind: 'videoinput', deviceId: 'obs-virtual', label: 'OBS Virtual Camera' },
        { kind: 'videoinput', deviceId: 'snap-virtual', label: 'Snap Camera' }
      ]);
    const stop = vi.fn();
    const getTracks = vi.fn().mockReturnValue([{ stop }]);
    const getUserMedia = vi.fn().mockResolvedValue({ getTracks } as any);

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

  it('starts camera with expected preferred constraints and audio processing by default', async () => {
    const getUserMedia = vi.fn().mockResolvedValue({ id: 'stream-1' } as any);
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: vi.fn().mockResolvedValue([])
      },
      configurable: true
    });

    await service.startCamera('cam-22');

    expect(getUserMedia).toHaveBeenCalled();
    const constraints = getUserMedia.mock.calls.at(-1)?.[0] as any;
    expect(constraints.video.deviceId.exact).toBe('cam-22');
    expect(constraints.audio).toEqual({
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    });
  });

  it('retries startCamera with minimal constraints when strict constraints fail', async () => {
    const overConstrainedError = { name: 'OverconstrainedError' };
    const getUserMedia = vi.fn()
      .mockRejectedValueOnce(overConstrainedError)
      .mockResolvedValueOnce({ id: 'stream-2' } as any);

    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: vi.fn().mockResolvedValue([])
      },
      configurable: true
    });

    await service.startCamera('obs-virtual');

    expect(getUserMedia).toHaveBeenCalledTimes(2);
    const retryConstraints = getUserMedia.mock.calls[1]?.[0] as any;
    expect(retryConstraints.video.deviceId.exact).toBe('obs-virtual');
    expect(retryConstraints.audio).toEqual({
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    });
  });

  it('can start camera without audio when requested explicitly', async () => {
    const getUserMedia = vi.fn().mockResolvedValue({ id: 'stream-3' } as any);
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: vi.fn().mockResolvedValue([])
      },
      configurable: true
    });

    await service.startCamera('cam-muted', false);

    const constraints = getUserMedia.mock.calls.at(-1)?.[0] as any;
    expect(constraints.video.deviceId.exact).toBe('cam-muted');
    expect(constraints.audio).toBe(false);
  });

  it('stops all stream tracks', () => {
    const stopA = vi.fn();
    const stopB = vi.fn();
    const stream = {
      getTracks: () => [{ stop: stopA }, { stop: stopB }]
    } as any;

    service.stopCamera(stream);

    expect(stopA).toHaveBeenCalled();
    expect(stopB).toHaveBeenCalled();
  });
});
