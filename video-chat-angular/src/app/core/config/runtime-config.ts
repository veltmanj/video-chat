export interface VideoChatRuntimeConfig {
  googleClientId?: string;
  appMode?: string;
  aiAgentEnabled?: boolean | string;
  aiAgentEndpoint?: string;
  aiAgentName?: string;
  aiAgentMention?: string;
}

declare global {
  interface Window {
    __VIDEOCHAT_CONFIG__?: VideoChatRuntimeConfig;
  }
}

export interface AiAgentRuntimeConfig {
  enabled: boolean;
  endpoint: string;
  name: string;
  mention: string;
}

const DEFAULT_AI_AGENT_ENDPOINT = '/social-api/social/v1/rooms/{roomId}/assistant-replies';
const DEFAULT_AI_AGENT_NAME = 'Pulse Copilot';
const DEFAULT_AI_AGENT_MENTION = '@pulse';

function getRuntimeConfig(): VideoChatRuntimeConfig {
  if (typeof window === 'undefined') {
    return {};
  }

  return window.__VIDEOCHAT_CONFIG__ ?? {};
}

function normalizeString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function normalizeBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') {
    return value;
  }

  if (typeof value !== 'string') {
    return false;
  }

  return ['1', 'true', 'yes', 'on'].includes(value.trim().toLowerCase());
}

export function resolveGoogleClientId(): string {
  return normalizeString(getRuntimeConfig().googleClientId);
}

export function resolveAppMode(): string {
  return normalizeString(getRuntimeConfig().appMode).toLowerCase() || 'production';
}

export function resolveAiAgentConfig(): AiAgentRuntimeConfig {
  const config = getRuntimeConfig();

  return {
    enabled: normalizeBoolean(config.aiAgentEnabled),
    endpoint: normalizeString(config.aiAgentEndpoint) || DEFAULT_AI_AGENT_ENDPOINT,
    name: normalizeString(config.aiAgentName) || DEFAULT_AI_AGENT_NAME,
    mention: normalizeString(config.aiAgentMention) || DEFAULT_AI_AGENT_MENTION
  };
}
