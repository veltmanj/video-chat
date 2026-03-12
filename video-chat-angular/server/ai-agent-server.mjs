import { createServer } from 'node:http';

const port = Number(process.env.AI_AGENT_PORT ?? 8787);
const model = (process.env.OPENAI_MODEL ?? 'gpt-5-mini').trim();
const apiKey = process.env.OPENAI_API_KEY?.trim() ?? '';
const allowedOrigins = (process.env.AI_AGENT_ALLOWED_ORIGINS ?? 'http://localhost:4200,http://127.0.0.1:4200')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);

function writeJson(response, statusCode, body, origin = '') {
  response.writeHead(statusCode, {
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Origin': allowedOrigins.includes(origin) ? origin : allowedOrigins[0] ?? '*',
    'Content-Type': 'application/json; charset=utf-8'
  });
  response.end(JSON.stringify(body));
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];

    request.on('data', (chunk) => {
      chunks.push(chunk);
    });

    request.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch (error) {
        reject(error);
      }
    });

    request.on('error', reject);
  });
}

function normalizeRecentMessages(value) {
  if (!Array.isArray(value)) {
    return [];
  }

  return value
    .filter((message) =>
      message
      && typeof message === 'object'
      && typeof message.senderName === 'string'
      && typeof message.text === 'string'
      && typeof message.sentAt === 'string'
    )
    .slice(-8);
}

function buildPrompt(body) {
  const recentMessages = normalizeRecentMessages(body.recentMessages);
  const recentTranscript = recentMessages.length
    ? recentMessages
      .map((message) => `- ${message.senderName} [${message.sentAt}]: ${message.text}`)
      .join('\n')
    : '- No recent room messages were provided.';

  return [
    'You are Pulse Copilot, a concise AI assistant inside a live video room chat.',
    'Keep replies practical, friendly, and under 120 words unless the user explicitly asks for more detail.',
    'Do not claim you can change cameras, broker connections, or account state yourself. Offer steps instead.',
    '',
    `Room ID: ${body.roomId}`,
    `Participant: ${body.participantName}`,
    '',
    'Recent chat context:',
    recentTranscript,
    '',
    'Latest user request:',
    body.prompt
  ].join('\n');
}

function extractOutputText(payload) {
  if (typeof payload?.output_text === 'string' && payload.output_text.trim()) {
    return payload.output_text.trim();
  }

  const fragments = Array.isArray(payload?.output)
    ? payload.output.flatMap((item) => {
      if (!Array.isArray(item?.content)) {
        return [];
      }

      return item.content.flatMap((content) => {
        if ((content?.type === 'output_text' || content?.type === 'text') && typeof content.text === 'string') {
          return [content.text];
        }

        return [];
      });
    })
    : [];

  return fragments.join('\n').trim();
}

async function requestAgentReply(body) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      input: buildPrompt(body)
    })
  });

  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = typeof payload?.error?.message === 'string'
      ? payload.error.message
      : `OpenAI request failed with status ${response.status}.`;
    throw new Error(message);
  }

  const reply = extractOutputText(payload);
  if (!reply) {
    throw new Error('OpenAI returned an empty response.');
  }

  return {
    model: typeof payload?.model === 'string' ? payload.model : model,
    reply,
    responseId: typeof payload?.id === 'string' ? payload.id : null
  };
}

const server = createServer(async (request, response) => {
  const requestOrigin = request.headers.origin ?? '';

  if (request.method === 'OPTIONS') {
    writeJson(response, 204, {}, requestOrigin);
    return;
  }

  if (request.url !== '/api/ai-agent') {
    writeJson(response, 404, { error: 'Not found.' }, requestOrigin);
    return;
  }

  if (request.method !== 'POST') {
    writeJson(response, 405, { error: 'Method not allowed.' }, requestOrigin);
    return;
  }

  if (!apiKey) {
    writeJson(response, 503, {
      error: 'OPENAI_API_KEY is missing. Set it before starting the AI agent server.'
    }, requestOrigin);
    return;
  }

  try {
    const body = await readBody(request);
    if (
      typeof body?.roomId !== 'string'
      || typeof body?.participantName !== 'string'
      || typeof body?.prompt !== 'string'
      || !body.prompt.trim()
    ) {
      writeJson(response, 400, { error: 'roomId, participantName, and prompt are required.' }, requestOrigin);
      return;
    }

    const result = await requestAgentReply({
      participantName: body.participantName.trim(),
      prompt: body.prompt.trim(),
      recentMessages: normalizeRecentMessages(body.recentMessages),
      roomId: body.roomId.trim()
    });

    writeJson(response, 200, result, requestOrigin);
  } catch (error) {
    writeJson(response, 502, {
      error: error instanceof Error ? error.message : 'Unknown AI agent failure.'
    }, requestOrigin);
  }
});

server.listen(port, () => {
  console.log(`Pulse Copilot server listening on http://localhost:${port}/api/ai-agent`);
});
