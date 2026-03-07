import { Injectable } from '@angular/core';
import { CameraDevice } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
export class MediaDeviceService {
  private static readonly DEFAULT_CAMERA_LABEL_PREFIX = 'Camera';
  private static readonly PROBE_CONSTRAINTS: MediaStreamConstraints = {
    audio: false,
    video: true
  };

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

  stopCamera(stream?: MediaStream): void {
    if (!stream) {
      return;
    }

    stream.getTracks().forEach((track) => {
      track.stop();
    });
  }

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

  private extractVideoInputs(devices: MediaDeviceInfo[]): MediaDeviceInfo[] {
    return devices.filter((device) => device.kind === 'videoinput');
  }

  private toCameraDevice(device: MediaDeviceInfo, index: number): CameraDevice {
    return {
      deviceId: device.deviceId,
      label: device.label || `${MediaDeviceService.DEFAULT_CAMERA_LABEL_PREFIX} ${index + 1}`
    };
  }

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

  private buildFallbackVideoConstraints(deviceId: string): MediaStreamConstraints {
    return {
      audio: false,
      video: deviceId ? { deviceId: { exact: deviceId } } : true
    };
  }
}
