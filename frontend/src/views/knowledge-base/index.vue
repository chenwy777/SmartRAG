<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NTag, NUpload } from 'naive-ui';
import type { FlatResponseData } from '@sa/axios';
import { uploadAccept } from '@/constants/common';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

// 文件预览相关状态
const previewVisible = ref(false);
const previewFileName = ref('');
const previewFileMd5 = ref('');

async function apiFn(params: Api.Common.CommonSearchParams = {}): Promise<FlatResponseData<Api.KnowledgeBase.List>> {
  const response = await request<Api.KnowledgeBase.UploadTask[] | Api.KnowledgeBase.List>({
    url: '/documents/accessible',
    params
  });
  if (response.error) return response as FlatResponseData<Api.KnowledgeBase.List>;

  const payload = response.data;
  if (!Array.isArray(payload)) return response as FlatResponseData<Api.KnowledgeBase.List>;

  const page = params.page && params.page > 0 ? params.page : 1;
  const size = params.size && params.size > 0 ? params.size : 10;
  const start = (page - 1) * size;
  const pageData = payload.slice(start, start + size);

  return {
    ...response,
    data: {
      data: pageData,
      content: pageData,
      number: page,
      size,
      totalElements: payload.length
    }
  };
}

function canManageFile(row: Api.KnowledgeBase.UploadTask) {
  return authStore.isAdmin || String(row.userId) === String(authStore.userInfo.id);
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="mx-4 text-12" />;
    return <SvgIcon localIcon="dflt" class="mx-4 text-12" />;
  }
  return null;
}

// 处理文件预览
function handleFilePreview(fileName: string, fileMd5: string) {
  previewFileName.value = fileName;
  previewFileMd5.value = fileMd5;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
  previewFileMd5.value = '';
}

const { columns, columnChecks, data, getData, loading, mobilePagination } = useTable({
  apiFn,
  showTotal: true,
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: '文件名',
      minWidth: 300,
      render: row => (
        <div class="flex items-center">
          {renderIcon(row.fileName)}
          <NEllipsis lineClamp={2} tooltip>
            <span
              class="cursor-pointer transition-colors hover:text-primary"
              onClick={() => handleFilePreview(row.fileName, row.fileMd5)}
            >
              {row.fileName}
            </span>
          </NEllipsis>
        </div>
      )
    },
    {
      key: 'fileMd5',
      title: 'MD5',
      width: 120,
      render: row => (
        <NEllipsis tooltip>
          <span
            class="cursor-pointer text-3 font-mono transition-colors hover:text-primary"
            onClick={() => {
              navigator.clipboard.writeText(row.fileMd5);
              window.$message?.success('MD5已复制');
            }}
            title="点击复制MD5"
          >
            {row.fileMd5.substring(0, 8)}...
          </span>
        </NEllipsis>
      )
    },
    {
      key: 'totalSize',
      title: '文件大小',
      width: 100,
      render: row => fileSize(row.totalSize)
    },
    {
      key: 'estimatedEmbeddingTokens',
      title: '预估向量化',
      width: 160,
      render: row => renderEstimatedEmbeddingUsage(row)
    },
    {
      key: 'actualEmbeddingTokens',
      title: '实际向量化',
      width: 160,
      render: row => renderActualEmbeddingUsage(row)
    },
    {
      key: 'status',
      title: '上传状态',
      width: 100,
      render: row => renderStatus(row.status, row.progress)
    },
    {
      key: 'orgTagName',
      title: '组织标签',
      width: 150,
      ellipsis: { tooltip: true, lineClamp: 2 }
    },
    {
      key: 'isPublic',
      title: '是否公开',
      width: 100,
      render: row => (row.public || row.isPublic ? <NTag type="success">公开</NTag> : <NTag type="warning">私有</NTag>)
    },
    {
      key: 'createdAt',
      title: '上传时间',
      width: 100,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD')
    },
    {
      key: 'operate',
      title: '操作',
      width: 180,
      render: row => (
        <div class="flex gap-4">
          {canManageFile(row) ? renderResumeUploadButton(row) : null}
          <NButton type="primary" ghost size="small" onClick={() => handleFilePreview(row.fileName, row.fileMd5)}>
            预览
          </NButton>
          {canManageFile(row) ? (
            <NPopconfirm onPositiveClick={() => handleDelete(row.fileMd5)}>
              {{
                default: () => '确认删除当前文件吗？',
                trigger: () => (
                  <NButton type="error" ghost size="small">
                    删除
                  </NButton>
                )
              }}
            </NPopconfirm>
          ) : null}
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const tableTasks = computed(() => {
  const remoteRows = data.value.map(item => tasks.value.find(task => task.fileMd5 === item.fileMd5) || item);
  const localRows = tasks.value.filter(
    task =>
      task.file && task.status !== UploadStatus.Completed && !remoteRows.some(item => item.fileMd5 === task.fileMd5)
  );

  return [...localRows, ...remoteRows];
});

onMounted(async () => {
  await getList();
});

function syncTaskFromServer(target: Api.KnowledgeBase.UploadTask, source: Api.KnowledgeBase.UploadTask) {
  const isLocalUploading =
    target.file && (target.status === UploadStatus.Pending || target.status === UploadStatus.Uploading);
  const status = source.status === UploadStatus.Uploading && !isLocalUploading ? UploadStatus.Break : source.status;

  Object.assign(target, {
    fileName: source.fileName,
    totalSize: source.totalSize,
    status,
    userId: source.userId,
    orgTag: source.orgTag,
    orgTagName: source.orgTagName,
    public: source.public,
    isPublic: source.isPublic,
    createdAt: source.createdAt,
    mergedAt: source.mergedAt,
    estimatedEmbeddingTokens: source.estimatedEmbeddingTokens,
    estimatedChunkCount: source.estimatedChunkCount,
    actualEmbeddingTokens: source.actualEmbeddingTokens,
    actualChunkCount: source.actualChunkCount,
    vectorizationStatus: source.vectorizationStatus,
    vectorizationErrorMessage: source.vectorizationErrorMessage
  });
}

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList() {
  await getData();

  data.value.forEach(item => {
    const index = tasks.value.findIndex(task => task.fileMd5 === item.fileMd5);
    if (index !== -1) {
      syncTaskFromServer(tasks.value[index], item);
    } else if (item.status === UploadStatus.Completed) {
      tasks.value.push(item);
    } else if (!tasks.value.some(task => task.fileMd5 === item.fileMd5)) {
      item.status = UploadStatus.Break;
      tasks.value.push(item);
    }
  });
}

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// 渲染上传状态
function renderStatus(status: UploadStatus, percentage: number) {
  if (status === UploadStatus.Completed) return <NTag type="success">已完成</NTag>;
  else if (status === UploadStatus.Break) return <NTag type="error">上传中断</NTag>;
  return <NProgress percentage={percentage} processing />;
}

function renderEstimatedEmbeddingUsage(row: Api.KnowledgeBase.UploadTask) {
  if (!row.estimatedEmbeddingTokens) {
    return <span class="text-xs text-stone-400">-</span>;
  }

  const estimatedTokenLabel = Number(row.estimatedEmbeddingTokens).toLocaleString();
  const estimatedChunkLabel = Number(row.estimatedChunkCount || 0).toLocaleString();
  return (
    <div class="text-xs text-stone-600 leading-5">
      <div>{estimatedTokenLabel} Tokens</div>
      <div class="text-stone-400">{estimatedChunkLabel} 个切片</div>
    </div>
  );
}

function isVectorizationProcessing(row: Api.KnowledgeBase.UploadTask) {
  return (
    row.status === UploadStatus.Completed &&
    (row.vectorizationStatus === 'PENDING' || row.vectorizationStatus === 'PROCESSING')
  );
}

function hasActualVectorizationUsage(row: Api.KnowledgeBase.UploadTask) {
  return row.actualEmbeddingTokens !== null && row.actualEmbeddingTokens !== undefined;
}

function canRetryVectorization(row: Api.KnowledgeBase.UploadTask) {
  if (!canManageFile(row)) return false;
  if (row.vectorizationStatus === 'FAILED') return true;
  if (row.vectorizationStatus === 'COMPLETED' && !hasActualVectorizationUsage(row)) return true;
  if (!hasActualVectorizationUsage(row) && row.estimatedEmbeddingTokens) return true;
  return false;
}

async function handleRetryVectorization(row: Api.KnowledgeBase.UploadTask) {
  const { error } = await request({
    url: `/documents/${row.fileMd5}/vectorization/retry`,
    method: 'POST'
  });

  if (error) return;

  row.vectorizationStatus = 'PROCESSING';
  row.vectorizationErrorMessage = null;
  row.actualEmbeddingTokens = undefined;
  row.actualChunkCount = undefined;
  window.$message?.success('已提交异步向量化重试任务');
  await getList();
}

function renderActualEmbeddingUsage(row: Api.KnowledgeBase.UploadTask) {
  if (row.status !== UploadStatus.Completed) {
    return <span class="text-xs text-stone-400">-</span>;
  }

  if (hasActualVectorizationUsage(row)) {
    const actualTokenLabel = Number(row.actualEmbeddingTokens).toLocaleString();
    const actualChunkLabel = Number(row.actualChunkCount || 0).toLocaleString();
    return (
      <div class="text-xs text-emerald-700 leading-5">
        <div>{actualTokenLabel} Tokens</div>
        <div class="text-stone-400">{actualChunkLabel} 个切片</div>
      </div>
    );
  }

  if (isVectorizationProcessing(row)) {
    return (
      <div class="text-xs text-sky-700 leading-5">
        <div>向量化处理中</div>
        <div class="text-stone-400">完成后会回写实际 Tokens</div>
      </div>
    );
  }

  if (row.vectorizationStatus === 'COMPLETED') {
    return (
      <div class="flex flex-col gap-6px text-xs leading-5">
        <div class="text-emerald-700 font-500">向量化已完成</div>
        <NEllipsis tooltip lineClamp={2} class="text-stone-500">
          {row.vectorizationErrorMessage || '历史数据未统计实际 Tokens，可按需重试回写'}
        </NEllipsis>
        {canRetryVectorization(row) ? (
          <div>
            <NButton size="tiny" ghost onClick={() => handleRetryVectorization(row)}>
              重试向量化
            </NButton>
          </div>
        ) : null}
      </div>
    );
  }

  if (row.vectorizationStatus === 'FAILED') {
    return (
      <div class="flex flex-col gap-6px text-xs leading-5">
        <div class="text-rose-600 font-500">向量化失败</div>
        <NEllipsis tooltip lineClamp={2} class="text-stone-500">
          {row.vectorizationErrorMessage || '请检查 Embedding 额度或稍后重试'}
        </NEllipsis>
        {canRetryVectorization(row) ? (
          <div>
            <NButton size="tiny" type="error" ghost onClick={() => handleRetryVectorization(row)}>
              重试向量化
            </NButton>
          </div>
        ) : null}
      </div>
    );
  }

  if (canRetryVectorization(row)) {
    return (
      <div class="flex flex-col gap-6px text-xs leading-5">
        <div class="text-amber-600">暂无实际向量化结果</div>
        <div class="text-stone-400">可能仍在处理，或历史任务未回写结果</div>
        <div>
          <NButton size="tiny" ghost onClick={() => handleRetryVectorization(row)}>
            重试向量化
          </NButton>
        </div>
      </div>
    );
  }

  return <span class="text-xs text-stone-400">-</span>;
}

let vectorizationPollingTimer: number | null = null;

function clearVectorizationPolling() {
  if (vectorizationPollingTimer) {
    window.clearTimeout(vectorizationPollingTimer);
    vectorizationPollingTimer = null;
  }
}

function scheduleVectorizationPolling() {
  clearVectorizationPolling();

  if (!tasks.value.some(item => isVectorizationProcessing(item))) {
    return;
  }

  vectorizationPollingTimer = window.setTimeout(async () => {
    await getList();
    scheduleVectorizationPolling();
  }, 3000);
}

watch(
  () =>
    tasks.value
      .map(item => `${item.fileMd5}:${item.vectorizationStatus || ''}:${item.actualEmbeddingTokens ?? ''}`)
      .join('|'),
  () => {
    scheduleVectorizationPolling();
  },
  { immediate: true }
);

onUnmounted(() => {
  clearVectorizationPolling();
});

// #region 文件续传
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          续传
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          续传
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// 任务列表存在文件，直接续传
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="文件列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <NButton size="small" ghost type="primary" @click="handleSearch">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              检索知识库
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        striped
        :columns="columns"
        :data="tableTasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="mobilePagination"
        class="sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />

    <!-- 文件预览弹窗 -->
    <NModal v-model:show="previewVisible" class="document-preview-modal" :auto-focus="false">
      <div class="document-preview-modal-shell">
        <FilePreview
          :file-name="previewFileName"
          :file-md5="previewFileMd5"
          :visible="previewVisible"
          @close="closeFilePreview"
        />
      </div>
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }
}

:deep(.document-preview-modal) {
  width: min(96vw, 1320px);
}

.document-preview-modal-shell {
  overflow: hidden;
  border-radius: 32px;
  box-shadow: 0 36px 120px rgba(15, 23, 42, 0.28);
}
</style>
