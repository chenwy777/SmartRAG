<script setup lang="ts">
defineOptions({
  name: 'TokenQuotaDialog'
});

const props = defineProps<{
  rowData: Api.User.Item;
}>();

const emit = defineEmits<{ submitted: [] }>();

const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);

type Model = {
  llmToken: number | null;
  embeddingToken: number | null;
  reason: string;
};

const model = ref<Model>(createDefaultModel());

function createDefaultModel(): Model {
  return {
    llmToken: null,
    embeddingToken: null,
    reason: '管理员手动追加'
  };
}

function close() {
  visible.value = false;
}

function normalizeToken(value: number | null) {
  return value === null ? 0 : Math.trunc(value);
}

async function handleSubmit() {
  const llmToken = normalizeToken(model.value.llmToken);
  const embeddingToken = normalizeToken(model.value.embeddingToken);
  if (llmToken < 0 || embeddingToken < 0) {
    window.$message?.warning('追加 Token 数量不能为负数');
    return;
  }
  if (llmToken === 0 && embeddingToken === 0) {
    window.$message?.warning('请至少追加一种 Token 额度');
    return;
  }

  try {
    loading.value = true;
    const res = await request({
      method: 'POST',
      url: `/admin/users/${props.rowData.userId}/tokens/add`,
      data: {
        llmToken,
        embeddingToken,
        reason: model.value.reason
      }
    });
    if (!res.error) {
      window.$message?.success('Token 额度已追加');
      close();
      emit('submitted');
    }
  } finally {
    loading.value = false;
  }
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="追加 Token 额度"
    :show-icon="false"
    :mask-closable="false"
    class="w-520px!"
  >
    <NForm :model="model" label-placement="left" :label-width="128" mt-10>
      <NFormItem label="用户名">
        <NInput :value="rowData.username" readonly />
      </NFormItem>
      <NFormItem label="LLM Token">
        <NInputNumber
          v-model:value="model.llmToken"
          :min="0"
          :step="10000"
          :precision="0"
          class="w-full"
          placeholder="不追加可留空"
        />
      </NFormItem>
      <NFormItem label="Embedding Token">
        <NInputNumber
          v-model:value="model.embeddingToken"
          :min="0"
          :step="10000"
          :precision="0"
          class="w-full"
          placeholder="不追加可留空"
        />
      </NFormItem>
      <NFormItem label="原因">
        <NInput v-model:value="model.reason" maxlength="200" show-count placeholder="管理员手动追加" />
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" :loading="loading" @click="handleSubmit">追加</NButton>
      </NSpace>
    </template>
  </NModal>
</template>
