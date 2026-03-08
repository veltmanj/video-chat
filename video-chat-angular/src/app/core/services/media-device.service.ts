import { Injectable } from '@angular/core';
import { CameraDevice } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
/**
 * Wraps browser media-device APIs behind a small Angular-friendly facade.
 *
 * The service keeps the permission probing and constraint fallback logic in one place so component
 * code can stay focused on room behavior instead of browser quirks.
 */
export class MediaDeviceService {
  /**
   * Used when browsers hide labels until the user has granted camera access at least once.
   */
  private static readonly DEFAULT_CAMERA_LABEL_PREFIX = 'Camera';

  /**
   * Minimal probe used only to unlock device labels and virtual devices in stricter browsers.
   */
  private static readonly PROBE_CONSTRAINTS: MediaStreamConstraints = {
    audio: false,
    video: true
  };

  /**
   * Returns the currently available video inputs and performs a lightweight permission probe when
   * the browser has not exposed named devices yet.
   */
  async listVideoInputs(): Promise<CameraDevice[]> {
    const mediaDevices = navigator.mediaDevices;
    if (!mediaDevices?.enumerateDevices) {
      return [];
    }

    let devices = await mediaDevices.enumerateDevices();
    let videoInputs = this.extractVideoInputs(devices);
    const hasNamedVideoInput = videoInputs.some((device) => device.label.trim().length > 0);

    // Some browsers only reveal all (virtual) cameras after initial camera permission.
    // If no named camera is visible yet, trigger a lightweight permission probe first.
    if (!videoInputs.length || !hasNamedVideoInput) {
      const probeStream = await this.requestPermissionProbe();
      this.stopCamera(probeStream || undefined);
      devices = await mediaDevices.enumerateDevices();
      videoInputs = this.extractVideoInputs(devices);
    }

    return videoInputs.map((device, index) => this.toCameraDevice(device, index));
  }

  /**
   * Starts a camera stream for a specific device, with a relaxed fallback for virtual cameras that
   * reject ideal resolution or frame-rate constraints.
   */
  async startCamera(deviceId: string): Promise<MediaStream> {
    const mediaDevices = navigator.mediaDevices;
    if (!mediaDevices?.getUserMedia) {
      throw new Error('Deze browser ondersteunt geen mediaDevices API.');
    }

    try {
      return await mediaDevices.getUserMedia(this.buildPreferredVideoConstraints(deviceId));
    } catch (error: any) {
      const retryable = error && (error.name === 'OverconstrainedError' || error.name === 'NotReadableError');
      if (!retryable) {
        throw error;
      }

      // Virtual cams may reject strict frame-size/fps constraints. Retry with minimal constraints.
      return mediaDevices.getUserMedia(this.buildFallbackVideoConstraints(deviceId));
    }
  }

  /**
   * Stops all tracks of a stream. Components should always delegate here so shutdown behavior stays consistent.
   */
  stopCamera(stream?: MediaStream): void {
    if (!stream) {
      return;
    }

    stream.getTracks().forEach((track) => {
      track.stop();
    });
  }

  /**
   * Attempts a disposable camera permission request so later enumeration exposes the full device list.
   */
  private async requestPermissionProbe(): Promise<MediaStream | null> {
    const mediaDevices = navigator.mediaDevices;
    if (!mediaDevices?.getUserMedia) {
      return null;
    }

    try {
      return await mediaDevices.getUserMedia(MediaDeviceService.PROBE_CONSTRAINTS);
    } catch {
      return null;
    }
  }

  /**
   * Restricts device enumeration to video inputs because the live-room UI only manages cameras.
   */
  private extractVideoInputs(devices: MediaDeviceInfo[]): MediaDeviceInfo[] {
    return devices.filter((device) => device.kind === 'videoinput');
  }

  /**
   * Converts browser device objects into the app-level shape used by the live-room UI.
   */
  private toCameraDevice(device: MediaDeviceInfo, index: number): CameraDevice {
    return {
      deviceId: device.deviceId,
      label: device.label || `${MediaDeviceService.DEFAULT_CAMERA_LABEL_PREFIX} ${index + 1}`
    };
  }

  /**
   * Preferred constraints balance quality and compatibility for normal webcams.
   */
  private buildPreferredVideoConstraints(deviceId: string): MediaStreamConstraints {
    return {
      audio: false,
      video: {
        deviceId: deviceId ? { exact: deviceId } : undefined,
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: 30, max: 60 }
      }
    };
  }

  /**
   * Fallback constraints intentionally avoid resolution/fps preferences because some virtual devices
   * reject anything stricter than "give me the device".
   */
  private buildFallbackVideoConstraints(deviceId: string): MediaStreamConstraints {
    return {
      audio: false,
      video: deviceId ? { deviceId: { exact: deviceId } } : true
    };
  }
}
