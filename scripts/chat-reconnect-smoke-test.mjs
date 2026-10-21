#!/usr/bin/env node

const DEFAULTS = {
  apiBase: 'http://127.0.0.1:8081/api/v1',
  wsBase: 'ws://127.0.0.1:8081/chat',
  username: 'admin',
  password: 'SmartRAG2026!',
  prompt:
    '请用6个编号小节介绍SmartRAG的企业知识库与RAG工作流，每节控制在80字左右，最后补3条实施建议。',
  disconnectAfterChunks: 10,
  disconnectDelayMs: 50,
  activeCheckDelayMs: 800,
  reconnectDelayMs: 1200,
  settleAfterCompletionMs: 500,
  timeoutMs: 30000
};

function parseArgs(argv) {
  const config = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;

    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next == null || next.startsWith('--')) {
      throw new Error(`missing value for --${key}`);
    }

    i += 1;
    switch (key) {
      case 'api-base':
        config.apiBase = next;
        break;
      case 'ws-base':
        config.wsBase = next;
        break;
      case 'username':
        config.username = next;
        break;
      case 'password':
        config.password = next;
        break;
      case 'prompt':
        config.prompt = next;
        break;
      case 'disconnect-after-chunks':
        config.disconnectAfterChunks = Number.parseInt(next, 10);
        break;
      case 'disconnect-delay-ms':
        config.disconnectDelayMs = Number.parseInt(next, 10);
        break;
      case 'active-check-delay-ms':
        config.activeCheckDelayMs = Number.parseInt(next, 10);
        break;
      case 'reconnect-delay-ms':
        config.reconnectDelayMs = Number.parseInt(next, 10);
        break;
      case 'settle-after-completion-ms':
        config.settleAfterCompletionMs = Number.parseInt(next, 10);
        break;
      case 'timeout-ms':
        config.timeoutMs = Number.parseInt(next, 10);
        break;
      default:
        throw new Error(`unknown option: --${key}`);
    }
  }
  return config;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function readJson(response) {
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function login(config) {
  const response = await fetch(`${config.apiBase}/users/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: config.username,
      password: config.password
    })
  });

  const json = await readJson(response);
  if (!response.ok || !json?.data?.token) {
    throw new Error(`login failed: status=${response.status} body=${JSON.stringify(json)}`);
  }

  return json.data.token;
}

async function getJson(path, token, config) {
  const response = await fetch(`${config.apiBase}${path}`, {
    headers: { Authorization: `Bearer ${token}` }
  });
  const json = await readJson(response);
  return { status: response.status, json };
}

function logStep(label, payload) {
  const suffix = payload === undefined ? '' : ` ${JSON.stringify(payload)}`;
  console.log(`[chat-reconnect] ${label}${suffix}`);
}

async function waitForSocketClose(socket) {
  if (socket.readyState === WebSocket.CLOSED) return;
  await new Promise(resolve => {
    socket.addEventListener('close', () => resolve(), { once: true });
  });
}

async function runPrimarySocket(token, config) {
  let generationId = null;
  let conversationId = null;
  let chunkCount = 0;
  let content = '';
  let closeScheduled = false;
  let socketError = null;

  const socket = new WebSocket(`${config.wsBase}/${token}`);
  const opened = new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });

  const closed = new Promise(resolve => {
    socket.addEventListener('close', event => resolve(event), { once: true });
  });

  socket.addEventListener('message', event => {
    const payload = JSON.parse(event.data);

    if (payload.type === 'connection') {
      logStep('ws1-connection', { sessionId: payload.sessionId });
      socket.send(config.prompt);
      return;
    }

    if (payload.type === 'start') {
      generationId = payload.generationId;
      conversationId = payload.conversationId;
      logStep('ws1-start', { generationId, conversationId });
      return;
    }

    if (payload.type === 'error' || payload.error || Number(payload.code) >= 400) {
      socketError = payload;
      socket.close(4001, 'server-error');
      return;
    }

    if (payload.chunk) {
      chunkCount += 1;
      content += payload.chunk;
      if (!closeScheduled && chunkCount >= config.disconnectAfterChunks) {
        closeScheduled = true;
        setTimeout(() => {
          socket.close(1000, 'simulate-offline');
        }, config.disconnectDelayMs);
      }
    }
  });

  await opened;
  const closeEvent = await closed;

  if (socketError) {
    throw new Error(`ws1 failed: ${JSON.stringify(socketError)}`);
  }

  if (!generationId) {
    throw new Error('ws1 closed before generationId was received');
  }

  logStep('ws1-closed', {
    code: closeEvent.code,
    reason: closeEvent.reason,
    generationId,
    chunkCount,
    contentLength: content.length
  });

  return { generationId, conversationId, chunkCount, content };
}

async function runReconnectSocket(token, generationId, config) {
  let chunkCount = 0;
  let content = '';
  let completion = null;
  let socketError = null;

  const socket = new WebSocket(`${config.wsBase}/${token}`);

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.close();
      reject(new Error(`timeout waiting for ws2 completion for generation ${generationId}`));
    }, config.timeoutMs);

    socket.addEventListener('message', event => {
      const payload = JSON.parse(event.data);

      if (payload.type === 'connection') {
        logStep('ws2-connection', { sessionId: payload.sessionId });
        return;
      }

      if (payload.chunk) {
        chunkCount += 1;
        content += payload.chunk;
        return;
      }

      if (payload.type === 'error' || payload.error || Number(payload.code) >= 400) {
        socketError = payload;
        clearTimeout(timeout);
        socket.close(4001, 'server-error');
        reject(new Error(`ws2 failed: ${JSON.stringify(payload)}`));
        return;
      }

      if (payload.type === 'completion') {
        completion = payload;
        clearTimeout(timeout);
        socket.close(1000, 'completed');
        resolve();
      }
    });

    socket.addEventListener('error', error => {
      clearTimeout(timeout);
      reject(error);
    }, { once: true });
  });

  await waitForSocketClose(socket);

  if (socketError) {
    throw new Error(`ws2 failed: ${JSON.stringify(socketError)}`);
  }

  logStep('ws2-completed', {
    generationId,
    chunkCount,
    contentLength: content.length,
    completionStatus: completion?.status ?? null
  });

  return { chunkCount, content, completion };
}

function assertSnapshotOk(label, result, expectedGenerationId) {
  if (result.status !== 200) {
    throw new Error(`${label} request failed: status=${result.status} body=${JSON.stringify(result.json)}`);
  }

  const generationId = result.json?.data?.generationId ?? null;
  if (expectedGenerationId && generationId !== expectedGenerationId) {
    throw new Error(`${label} generation mismatch: expected=${expectedGenerationId} actual=${generationId}`);
  }
}

async function main() {
  if (typeof WebSocket !== 'function') {
    throw new Error('global WebSocket is not available; use Node.js 20 or newer');
  }

  const config = parseArgs(process.argv.slice(2));
  logStep('config', {
    apiBase: config.apiBase,
    wsBase: config.wsBase,
    username: config.username,
    disconnectAfterChunks: config.disconnectAfterChunks,
    timeoutMs: config.timeoutMs
  });

  const token = await login(config);
  logStep('login-ok');

  const firstSocket = await runPrimarySocket(token, config);

  await sleep(config.activeCheckDelayMs);
  const activeAfterDisconnect = await getJson('/chat/active-generation', token, config);
  assertSnapshotOk('active-generation', activeAfterDisconnect, firstSocket.generationId);
  logStep('active-after-disconnect', {
    generationId: activeAfterDisconnect.json?.data?.generationId ?? null,
    status: activeAfterDisconnect.json?.data?.status ?? null,
    contentLength: activeAfterDisconnect.json?.data?.content?.length ?? 0
  });

  await sleep(config.reconnectDelayMs);
  const snapshotBeforeReconnect = await getJson(
    `/chat/generation/${firstSocket.generationId}`,
    token,
    config
  );
  assertSnapshotOk('generation-before-reconnect', snapshotBeforeReconnect, firstSocket.generationId);
  logStep('snapshot-before-reconnect', {
    generationId: snapshotBeforeReconnect.json?.data?.generationId ?? null,
    status: snapshotBeforeReconnect.json?.data?.status ?? null,
    contentLength: snapshotBeforeReconnect.json?.data?.content?.length ?? 0
  });

  const secondSocket = await runReconnectSocket(token, firstSocket.generationId, config);

  await sleep(config.settleAfterCompletionMs);
  const finalSnapshot = await getJson(`/chat/generation/${firstSocket.generationId}`, token, config);
  assertSnapshotOk('generation-final', finalSnapshot, firstSocket.generationId);

  const finalStatus = finalSnapshot.json?.data?.status ?? null;
  const finalContentLength = finalSnapshot.json?.data?.content?.length ?? 0;
  const activeLength = activeAfterDisconnect.json?.data?.content?.length ?? 0;
  const preReconnectLength = snapshotBeforeReconnect.json?.data?.content?.length ?? 0;

  if (activeLength <= firstSocket.content.length) {
    throw new Error(
      `content did not grow after disconnect: ws1=${firstSocket.content.length} active=${activeLength}`
    );
  }
  if (preReconnectLength < activeLength) {
    throw new Error(
      `snapshot before reconnect should not shrink: active=${activeLength} beforeReconnect=${preReconnectLength}`
    );
  }
  if (secondSocket.chunkCount <= 0) {
    throw new Error('ws2 received no chunks after reconnect');
  }
  if (secondSocket.completion?.status !== 'finished') {
    throw new Error(`ws2 completion status is not finished: ${secondSocket.completion?.status ?? 'null'}`);
  }
  if (finalStatus !== 'COMPLETED') {
    throw new Error(`final snapshot status is not COMPLETED: ${finalStatus}`);
  }

  logStep('summary', {
    generationId: firstSocket.generationId,
    conversationId: firstSocket.conversationId,
    ws1Chunks: firstSocket.chunkCount,
    ws1ContentLength: firstSocket.content.length,
    activeContentLength: activeLength,
    beforeReconnectContentLength: preReconnectLength,
    ws2Chunks: secondSocket.chunkCount,
    ws2ContentLength: secondSocket.content.length,
    finalStatus,
    finalContentLength
  });
}

main().catch(error => {
  console.error(`[chat-reconnect] failed ${error?.stack ?? error}`);
  process.exitCode = 1;
});
