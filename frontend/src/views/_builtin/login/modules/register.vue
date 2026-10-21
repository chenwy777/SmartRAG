<script setup lang="ts">
import { normalizeBackendMessage } from '@/service/request/shared';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const route = useRoute();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

const inviteCodeErrorCodes = new Set([
  'INVITE_CODE_REQUIRED',
  'INVITE_CODE_INVALID',
  'INVITE_CODE_EXPIRED',
  'INVITE_CODE_EXHAUSTED'
]);

interface FormModel {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

const model: FormModel = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  inviteCode: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, defaultRequiredRule, createConfirmPwdRule } = useFormRules();

  return {
    username: formRules.userName,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password),
    inviteCode: [defaultRequiredRule]
  };
});

const loading = ref(false);
const inviteCodeErrorMessage = ref('');

function clearInviteCodeError() {
  inviteCodeErrorMessage.value = '';
}

function resolveRegisterError(error: any) {
  const rawMessage = String(error?.response?.data?.message || error?.message || '');
  const message = normalizeBackendMessage(rawMessage || '注册失败，请稍后重试');

  return {
    rawMessage,
    message
  };
}

async function handleSubmit() {
  clearInviteCodeError();
  await validate();
  loading.value = true;
  const { error } = await fetchRegister(model.username, model.password, model.inviteCode.trim());
  if (!error) {
    window.$message?.success('注册成功');
    toggleLoginModule('pwd-login');
  } else {
    const { rawMessage, message } = resolveRegisterError(error);

    if (inviteCodeErrorCodes.has(rawMessage)) {
      inviteCodeErrorMessage.value = message;
    }
  }
  loading.value = false;
}

function syncInviteCodeFromQuery(inviteCode: unknown) {
  if (typeof inviteCode !== 'string') return;
  model.inviteCode = inviteCode.trim();
}

watch(
  () => route.query.inviteCode,
  inviteCode => {
    syncInviteCodeFromQuery(inviteCode);
  },
  { immediate: true }
);

watch(
  () => model.inviteCode,
  () => {
    if (inviteCodeErrorMessage.value) {
      clearInviteCodeError();
    }
  }
);
</script>

<template>
  <div class="register-layout">
    <aside class="invite-side-panel">
      <div class="invite-side-header">
        <div class="invite-side-kicker">邀请码获取</div>
        <div class="invite-side-title">欢迎使用 SmartRAG 知识库</div>
        <div class="invite-side-desc">当前站点采用邀请码注册，请联系站点管理员申请邀请码。</div>
      </div>

      <div class="invite-side-bottom">
        <div class="invite-side-steps">
          <div class="invite-step-row">
            <span class="invite-channel-step">1</span>
            <span>联系站点管理员申请邀请码</span>
          </div>
          <div class="invite-step-row">
            <span class="invite-channel-step">2</span>
            <span>获取邀请码后，填写注册信息</span>
          </div>
          <div class="invite-step-row">
            <span class="invite-channel-step">3</span>
            <span>完成注册后登录，即可使用知识库</span>
          </div>
        </div>
      </div>
    </aside>

    <NForm
      ref="formRef"
      :model="model"
      :rules="rules"
      size="large"
      :show-label="false"
      class="register-form-panel"
      @keyup.enter="handleSubmit"
    >
      <NFormItem path="username">
        <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')">
          <template #prefix>
            <icon-ant-design:user-outlined />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="password">
        <NInput
          v-model:value="model.password"
          type="password"
          show-password-on="click"
          :placeholder="$t('page.login.common.passwordPlaceholder')"
        >
          <template #prefix>
            <icon-ant-design:key-outlined />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem path="confirmPassword">
        <NInput
          v-model:value="model.confirmPassword"
          type="password"
          show-password-on="click"
          :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
        >
          <template #prefix>
            <icon-ant-design:key-outlined />
          </template>
        </NInput>
      </NFormItem>
      <NFormItem
        path="inviteCode"
        :validation-status="inviteCodeErrorMessage ? 'error' : undefined"
        :feedback="inviteCodeErrorMessage || undefined"
      >
        <NInput v-model:value="model.inviteCode" :placeholder="$t('page.login.common.inviteCodePlaceholder')">
          <template #prefix>
            <icon-ant-design:safety-certificate-outlined />
          </template>
        </NInput>
      </NFormItem>
      <div class="register-form-tip mb-4">
        {{ $t('page.login.register.inviteCodeTip') }}
      </div>
      <NSpace vertical :size="18" class="w-full">
        <NButton type="primary" size="large" round block :loading="loading" @click="handleSubmit">
          {{ $t('page.login.common.register') }}
        </NButton>
        <NButton block @click="toggleLoginModule('pwd-login')">
          {{ $t('page.login.common.back') }}
        </NButton>
      </NSpace>

      <div class="mt-4 text-center">
        {{ $t('page.login.register.agreement') }}
        <NButton text type="primary">{{ $t('page.login.register.protocol') }}</NButton>
        {{ $t('page.login.register.and') }}
        <NButton text type="primary">{{ $t('page.login.register.policy') }}</NButton>
      </div>
    </NForm>
  </div>
</template>

<style scoped>
.register-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(280px, 0.92fr);
  gap: 20px;
  align-items: stretch;
}

.register-form-panel {
  min-width: 0;
}

.register-form-tip {
  font-size: 12px;
  line-height: 1.7;
  color: rgb(var(--base-text-color) / 0.66);
}

.invite-side-panel {
  display: flex;
  min-width: 0;
  flex-direction: column;
  border: 1px solid rgb(var(--primary-color) / 0.16);
  border-radius: 24px;
  padding: 20px;
  background:
    linear-gradient(145deg, rgb(var(--primary-color) / 0.12), rgb(var(--primary-color) / 0.04)),
    rgb(var(--container-bg-color));
  box-shadow: 0 18px 40px rgb(var(--primary-color) / 0.08);
}

.invite-side-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
  color: rgb(var(--primary-color));
}

.invite-side-title {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.45;
  color: rgb(var(--primary-color));
}

.invite-side-desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.8;
  color: rgb(var(--base-text-color) / 0.72);
}

.invite-side-steps {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.invite-step-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  line-height: 1.6;
  color: rgb(var(--base-text-color) / 0.78);
}

.invite-side-bottom {
  margin-top: auto;
  padding-top: 18px;
}

.invite-channel-step {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, rgb(var(--primary-color)), rgb(var(--primary-700-color)));
}

@media (max-width: 900px) {
  .register-layout {
    grid-template-columns: 1fr;
  }
}
</style>
