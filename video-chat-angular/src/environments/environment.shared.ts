export interface AppEnvironment {
  production: boolean;
  brokerWebsocketUrls: string[];
  backofficeRoute: string;
}

const LOCALHOSTS = new Set(['localhost', '127.0.0.1']);
const DEV_BROKER_URLS = ['ws://localhost:9898/rsocket', 'ws://localhost:9899/rsocket'];

export function createEnvironment(production: boolean): AppEnvironment {
  const browserLocation = typeof window !== 'undefined' ? window.location : null;
  const hostname = browserLocation?.hostname ?? 'localhost';
  const port = browserLocation?.port ? `:${browserLocation.port}` : '';
  const secureContext = browserLocation?.protocol === 'https:';
  const protocol = secureContext ? 'wss' : 'ws';
  const sameOriginBrokerUrl = `${protocol}://${hostname}${port}/rsocket`;
  const localBrokerUrls = !secureContext && LOCALHOSTS.has(hostname) ? DEV_BROKER_URLS : [];
  const brokerWebsocketUrls = port === ':4200'
    ? [...localBrokerUrls, sameOriginBrokerUrl]
    : [sameOriginBrokerUrl, ...localBrokerUrls];

  return {
    production,
    brokerWebsocketUrls,
    backofficeRoute: 'room.events.*'
  };
}
