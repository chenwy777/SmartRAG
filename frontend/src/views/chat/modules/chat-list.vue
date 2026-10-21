<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessionId, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = list.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

const params = computed(() => {
  const p: Record<string, string> = {};
  if (range.value) {
    p.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    p.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }
  if (conversationId.value) {
    p.conversationId = conversationId.value;
  }
  return p;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
</script>

<template>
  <Suspense>
    <div class="flex h-0 flex-1 flex-col">
      <!-- Date filter in header -->
      <Teleport defer to="#header-extra">
        <div v-if="!showEmpty" class="flex items-center px-4">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="时间" size="small">
              <NDatePicker v-model:value="range" type="daterange" clearable size="small" />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>

      <!-- Empty state -->
      <div v-if="showEmpty" class="flex flex-1 flex-col items-center justify-center gap-4">
        <div class="flex h-20 w-20 items-center justify-center rounded-2xl bg-[rgb(var(--primary-color)/0.08)]">
          <icon-material-symbols:chat-outline-rounded class="text-36px text-[rgb(var(--primary-color)/0.5)]" />
        </div>
        <div class="text-15px font-500 color-#aaa">
          {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
        </div>
        <div class="text-12px color-#bbb">
          在左侧选择一个对话，或点击「新对话」开始
        </div>
      </div>

      <!-- Message list -->
      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div class="mx-auto w-full max-w-[960px] px-4">
            <VueMarkdownItProvider>
              <ChatMessage
                v-for="(item, index) in list"
                :key="index"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
              />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped lang="scss"></style>
