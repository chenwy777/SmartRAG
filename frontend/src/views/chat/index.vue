<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');
const sidebarCollapsed = ref(false);
</script>

<template>
  <div v-if="showReferencePreview" class="h-full">
    <ReferencePreviewPage />
  </div>
  <div v-else class="h-full bg-layout p-3 pt-6">
    <div
      class="h-full flex overflow-hidden rounded-2xl bg-white shadow-[0_1px_2px_rgba(15,23,42,0.04),0_8px_28px_rgba(15,23,42,0.06)] dark:bg-[#1c1c1c] dark:shadow-[0_1px_2px_rgba(0,0,0,0.5),0_8px_28px_rgba(0,0,0,0.3)]"
    >
      <ConversationSidebar v-model:collapsed="sidebarCollapsed" />
      <div class="relative min-w-0 flex flex-col flex-1">
        <button
          v-show="sidebarCollapsed"
          class="absolute left-3 top-3 z-20 h-9 w-9 inline-flex items-center justify-center rounded-xl bg-white text-#666 shadow-[0_1px_2px_rgba(15,23,42,0.06),0_4px_12px_rgba(15,23,42,0.08)] ring-1 ring-#0f172a14 transition-all duration-150 active:scale-95 hover:scale-105 dark:bg-#262626 dark:text-#bbb hover:text-[rgb(var(--primary-color))] dark:shadow-[0_1px_2px_rgba(0,0,0,0.4),0_4px_12px_rgba(0,0,0,0.4)] dark:ring-#ffffff14"
          aria-label="展开对话列表"
          @click="sidebarCollapsed = false"
        >
          <icon-material-symbols:left-panel-open-outline-rounded class="text-18px" />
        </button>
        <ChatList />
        <InputBox />
      </div>
    </div>
  </div>
</template>

<style scoped></style>
