import { Injectable } from '@angular/core';
import { CameraDevice } from '../models/room.models';

@Injectable({
  providedIn: 'root'
})
export class MediaDeviceService {
  async listVideoInputs(): Promise<CameraDevice[]> {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
      return [];
    }

    let devices = await navigator.mediaDevices.enumerateDevices();
    let videoInputs = devices.filter((device) => device.kind === 'videoinput');
    const hasNamedVideoInput = videoInputs.some((device) => !!device.label && !!device.label.trim());

    // Some browsers only reveal all (virtual) cameras after initial camera permission.
    // If no named camera is visible yet, trigger a lightweight permission probe first.
    if (!videoInputs.length || !hasNamedVideoInput) {
      const probeStream = await this.requestPermissionProbe();
      this.stopCamera(probeStream || undefined);
      devices = await navigator.mediaDevices.enumerateDevices();
      videoInputs = devices.filter((device) => device.kind === 'videoinput');
    }

    return videoInputs
      .map((device, index) => ({
        deviceId: device.deviceId,
        label: device.label || 'Camera ' + (index + 1)
      }));
  }

  async startCamera(deviceId: string): Promise<MediaStream> {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('Deze browser ondersteunt geen mediaDevices API.');
    }

    try {
      return await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          deviceId: deviceId ? { exact: deviceId } : undefined,
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30, max: 60 }
        }
      });
    } catch (error: any) {
      const retryable = error && (error.name === 'OverconstrainedError' || error.name === 'NotReadableError');
      if (!retryable) {
        throw error;
      }

      // Virtual cams may reject strict frame-size/fps constraints. Retry with minimal constraints.
      return navigator.mediaDevices.getUserMedia({
        audio: false,
        video: deviceId ? { deviceId: { exact: deviceId } } : true
      });
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
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      return null;
    }

    try {
      return await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: true
      });
    } catch {
      return null;
    }
  }
}
