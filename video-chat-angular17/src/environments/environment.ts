const appHost = typeof window !== 'undefined' ? window.location.hostname : 'localhost';
const wsProtocol = typeof window !== 'undefined' && window.location.protocol === 'https:' ? 'wss' : 'ws';
const appPort = typeof window !== 'undefined' && window.location.port ? `:${window.location.port}` : '';
const sameOriginBrokerUrl = `${wsProtocol}://${appHost}${appPort}/rsocket`;
const isLocalHost = appHost === 'localhost' || appHost === '127.0.0.1';
const localDevBrokerUrls = wsProtocol === 'ws' && isLocalHost
  ? ['ws://localhost:9898/rsocket', 'ws://localhost:9899/rsocket']
  : [];
const brokerWebsocketUrls = appPort === ':4200'
  ? [...localDevBrokerUrls, sameOriginBrokerUrl]
  : [sameOriginBrokerUrl, ...localDevBrokerUrls];

export const environment = {
  production: false,
  brokerWebsocketUrls,
  backofficeRoute: 'room.events.*'
};
