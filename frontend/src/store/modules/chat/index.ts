import { useWebSocket } from '@vueuse/core';
import { request } from '@/service/request';

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const NON_RETRYABLE_CLOSE_CODES = new Set([1002, 1003, 1007, 1008]);
  const WS_HEARTBEAT_PING = '__chat_ping__';
  const WS_HEARTBEAT_PONG = '__chat_pong__';

  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });
  const list = ref<Api.Chat.Message[]>([]);
  const sessions = ref<Api.Chat.ConversationSession[]>([]);
  const sessionsLoading = ref(false);
  const activeTab = ref<'active' | 'archived'>('active');

  const store = useAuthStore();

  const sessionId = ref<string>('');
  const allowReconnect = ref(true);
  const authFailureNotified = ref(false);
  const handshakeConfirmed = ref(false);
  const intentionalDisconnect = ref(false);
  const rateLimitUntil = ref<number | null>(null);
  const rateLimitRemainingSeconds = ref(0);
  let rateLimitTimer: ReturnType<typeof setInterval> | null = null;

  function mapGenerationStatus(status?: Api.Chat.GenerationStatus): Api.Chat.Message['status'] {
    if (status === 'COMPLETED' || status === 'CANCELLED') {
      return 'finished';
    }
    if (status === 'FAILED') {
      return 'error';
    }
    if (status === 'STREAMING') {
      return 'loading';
    }
    return 'pending';
  }

  function findAssistantIndexByGenerationId(generationId?: string) {
    if (!generationId) {
      return -1;
    }
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return i;
      }
    }
    return -1;
  }

  function getPendingGenerationId() {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && ['pending', 'loading'].includes(item.status || '') && item.generationId) {
        return item.generationId;
      }
    }
    return '';
  }

  function upsertGenerationSnapshot(snapshot: Api.Chat.GenerationSnapshot | null) {
    if (!snapshot) {
      return;
    }
    conversationId.value = snapshot.conversationId || conversationId.value;

    const assistantIndex = findAssistantIndexByGenerationId(snapshot.generationId);
    const nextStatus = mapGenerationStatus(snapshot.status);

    if (assistantIndex >= 0) {
      const assistant = list.value[assistantIndex];
      assistant.content = snapshot.content || assistant.content || '';
      assistant.status = nextStatus;
      assistant.generationId = snapshot.generationId;
      assistant.conversationId = snapshot.conversationId;
      assistant.timestamp ||= snapshot.updatedAt;
      if (snapshot.referenceMappings && Object.keys(snapshot.referenceMappings).length > 0) {
        assistant.referenceMappings = snapshot.referenceMappings;
      }
      return;
    }

    list.value.push({
      role: 'user',
      content: snapshot.question,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.createdAt
    });
    list.value.push({
      role: 'assistant',
      content: snapshot.content || '',
      status: nextStatus,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.updatedAt,
      referenceMappings: snapshot.referenceMappings
    });
  }

  async function fetchGenerationSnapshot(generationId: string) {
    if (!generationId) {
      return null;
    }
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: `chat/generation/${generationId}`
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function fetchActiveGenerationSnapshot() {
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: 'chat/active-generation'
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function syncGenerationAfterReconnect() {
    const pendingGenerationId = getPendingGenerationId();
    if (pendingGenerationId) {
      upsertGenerationSnapshot(await fetchGenerationSnapshot(pendingGenerationId));
      return;
    }
    upsertGenerationSnapshot(await fetchActiveGenerationSnapshot());
  }

  // ---- Session management ----

  async function loadSessions() {
    sessionsLoading.value = true;
    const { error, data } = await request<Api.Chat.ConversationSession[]>({
      url: 'users/conversations'
    });
    if (!error && data) {
      sessions.value = data;
      // Auto-select first active session if none is active
      if (!conversationId.value) {
        const firstActive = data.find(s => s.status === 'ACTIVE');
        if (firstActive) {
          await switchSession(firstActive.conversationId);
        }
      }
    }
    sessionsLoading.value = false;
  }

  async function createNewSession() {
    const { error, data } = await request<Api.Chat.ConversationSession>({
      url: 'users/conversations',
      method: 'POST'
    });
    if (!error && data) {
      conversationId.value = data.conversationId;
      list.value = [];
      await loadSessions();
    }
    return !error;
  }

  async function switchSession(targetConversationId: string) {
    if (targetConversationId === conversationId.value) {
      return;
    }
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/switch`,
      method: 'PUT'
    });
    if (error) {
      return;
    }
    conversationId.value = targetConversationId;
    list.value = [];
    await loadMessages(targetConversationId);
  }

  async function loadMessages(targetConversationId?: string) {
    const cid = targetConversationId || conversationId.value;
    if (!cid) {
      return;
    }
    const { error, data } = await request<Api.Chat.Message[]>({
      url: 'users/conversation',
      params: { conversationId: cid }
    });
    if (!error && data) {
      list.value = data;
    }
  }

  async function archiveSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/archive`,
      method: 'PUT'
    });
    if (!error) {
      await loadSessions();
      if (targetConversationId === conversationId.value) {
        list.value = [];
        conversationId.value = '';
      }
    }
  }

  async function unarchiveSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/unarchive`,
      method: 'PUT'
    });
    if (!error) {
      await loadSessions();
    }
  }

  const filteredSessions = computed(() => {
    return sessions.value.filter(s => s.status === (activeTab.value === 'archived' ? 'ARCHIVED' : 'ACTIVE'));
  });

  // ---- WebSocket ----

  const socketUrl = computed(() => {
    const token = store.token?.trim();
    if (!token) {
      return undefined;
    }
    return `/proxy-ws/chat/${encodeURIComponent(token)}`;
  });

  const {
    status: wsStatus,
    data: wsData,
    send: rawWsSend,
    open: rawWsOpen,
    close: rawWsClose
  } = useWebSocket(socketUrl, {
    immediate: false,
    autoConnect: false,
    heartbeat: {
      message: WS_HEARTBEAT_PING,
      responseMessage: WS_HEARTBEAT_PONG,
      interval: 20_000,
      pongTimeout: 10_000
    },
    autoReconnect: {
      retries: () => allowReconnect.value,
      delay: 1500,
      onFailed: () => {
        if (allowReconnect.value && socketUrl.value) {
          window.$message?.warning('WebSocket 重连失败，请检查网络或刷新页面后重试');
        }
      }
    },
    onConnected: () => {
      allowReconnect.value = true;
      authFailureNotified.value = false;
      intentionalDisconnect.value = false;
    },
    onDisconnected: (_, event) => {
      if (intentionalDisconnect.value) {
        intentionalDisconnect.value = false;
        allowReconnect.value = Boolean(socketUrl.value);
        return;
      }
      const closedBeforeHandshake = !handshakeConfirmed.value;
      const isAuthOrProtocolFailure = NON_RETRYABLE_CLOSE_CODES.has(event.code) || closedBeforeHandshake;
      allowReconnect.value = !isAuthOrProtocolFailure;
      if (isAuthOrProtocolFailure && !authFailureNotified.value) {
        authFailureNotified.value = true;
        window.$message?.error('聊天连接鉴权失败，请重新登录后再试');
      }
    }
  });

  function syncRateLimitCountdown() {
    if (!rateLimitUntil.value) {
      rateLimitRemainingSeconds.value = 0;
      return;
    }
    const remainingMs = rateLimitUntil.value - Date.now();
    rateLimitRemainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
    if (remainingMs <= 0) {
      clearRateLimitCountdown();
    }
  }

  function clearRateLimitTimer() {
    if (rateLimitTimer !== null) {
      window.clearInterval(rateLimitTimer);
      rateLimitTimer = null;
    }
  }

  function clearRateLimitCountdown() {
    clearRateLimitTimer();
    rateLimitUntil.value = null;
    rateLimitRemainingSeconds.value = 0;
  }

  function startRateLimitCountdown(retryAfterSeconds: number) {
    const normalizedSeconds = Math.max(0, Math.ceil(retryAfterSeconds));
    if (normalizedSeconds <= 0) {
      clearRateLimitCountdown();
      return;
    }
    rateLimitUntil.value = Date.now() + normalizedSeconds * 1000;
    syncRateLimitCountdown();
    clearRateLimitTimer();
    rateLimitTimer = setInterval(syncRateLimitCountdown, 1000);
  }

  function resetConnectionState() {
    handshakeConfirmed.value = false;
    sessionId.value = '';
    authFailureNotified.value = false;
  }

  function wsOpen() {
    if (!socketUrl.value) {
      return;
    }
    resetConnectionState();
    allowReconnect.value = true;
    intentionalDisconnect.value = wsStatus.value === 'OPEN' || wsStatus.value === 'CONNECTING';
    rawWsOpen();
  }

  function wsClose(code?: number, reason?: string) {
    intentionalDisconnect.value = true;
    allowReconnect.value = false;
    rawWsClose(code, reason);
  }

  function handleAuthReset() {
    clearRateLimitCountdown();
    resetConnectionState();
    conversationId.value = '';
    input.value = { message: '' };
    list.value = [];
    sessions.value = [];
    wsClose(1000, 'auth-reset');
  }

  watch(
    socketUrl,
    url => {
      resetConnectionState();
      if (!url) {
        wsClose();
        clearRateLimitCountdown();
        return;
      }
      wsOpen();
    },
    { immediate: true }
  );

  watch(wsData, val => {
    if (!val) return;
    try {
      const data = JSON.parse(val);
      if (data.type === 'connection' && data.sessionId) {
        handshakeConfirmed.value = true;
        sessionId.value = data.sessionId;
        syncGenerationAfterReconnect().catch(() => {});
      }
    } catch {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const scrollToBottom = ref<null | (() => void)>(null);
  const isRateLimited = computed(() => rateLimitRemainingSeconds.value > 0);
  const connectionStatus = computed(() => {
    if (wsStatus.value === 'OPEN') {
      return 'OPEN';
    }
    if (wsStatus.value === 'CONNECTING' && handshakeConfirmed.value) {
      return 'RECONNECTING';
    }
    return wsStatus.value;
  });

  return {
    input,
    conversationId,
    list,
    sessions,
    sessionsLoading,
    activeTab,
    filteredSessions,
    connectionStatus,
    isRateLimited,
    rateLimitRemainingSeconds,
    wsStatus,
    wsData,
    wsSend: rawWsSend,
    wsOpen,
    wsClose,
    sessionId,
    scrollToBottom,
    clearRateLimitCountdown,
    startRateLimitCountdown,
    handleAuthReset,
    fetchGenerationSnapshot,
    upsertGenerationSnapshot,
    syncGenerationAfterReconnect,
    loadSessions,
    createNewSession,
    switchSession,
    loadMessages,
    archiveSession,
    unarchiveSession
  };
});
