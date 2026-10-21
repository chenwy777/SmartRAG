<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);

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
const userId = ref<number | null>(null);

const params = computed(() => {
  const query: {
    userid?: number;
    start_date?: string;
    end_date?: string;
  } = {};

  if (userId.value !== null) {
    query.userid = userId.value;
  }

  if (range.value) {
    query.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    query.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }

  return query;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  }
  loading.value = false;
}
</script>

<template>
  <div class="h-full">
    <Teleport defer to="#header-extra">
      <div class="px-10">
        <NForm :model="params" label-placement="left" :show-feedback="false" inline>
          <NFormItem label="用户">
            <TheSelect
              v-model:value="userId"
              url="admin/users/list"
              :params="{ page: 1, size: 999 }"
              key-field="content"
              value-field="userId"
              label-field="username"
              class="clear w-200px!"
              placeholder="全部用户"
            />
          </NFormItem>
          <NFormItem label="时间">
            <NDatePicker v-model:value="range" type="daterange" class="clear" clearable />
          </NFormItem>
        </NForm>
      </div>
    </Teleport>
    <NScrollbar ref="scrollbarRef">
      <NSpin :show="loading" class="h-full">
        <VueMarkdownItProvider>
          <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
        </VueMarkdownItProvider>
        <NEmpty v-if="!list.length" description="暂无数据" class="mt-60" />
      </NSpin>
    </NScrollbar>
  </div>
</template>

<style scoped lang="scss"></style>
