import { useAuthStore } from '@/store/modules/auth';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import type { RequestInstanceState } from './type';

const backendMessageMap: Record<string, string> = {
  INVITE_CODE_REQUIRED: '请输入邀请码',
  INVITE_CODE_INVALID: '邀请码不正确，请检查后重试',
  INVITE_CODE_EXPIRED: '邀请码已过期，请重新获取',
  INVITE_CODE_EXHAUSTED: '邀请码已失效，请重新获取',
  REGISTRATION_CLOSED: '当前暂未开放注册',
  'Username already exists': '用户名已存在'
};

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

export function normalizeBackendMessage(message: string) {
  return backendMessageMap[message] || message;
}

/** refresh token */
async function handleRefreshToken() {
  const authStore = useAuthStore();
  const { resetStore } = authStore;

  const rToken = localStg.get('refreshToken') || '';
  const { error, data } = await fetchRefreshToken(rToken);
  if (!error) {
    authStore.setToken(data.token);
    localStg.set('refreshToken', data.refreshToken);
    return true;
  }

  resetStore();

  return false;
}

export async function handleExpiredRequest(state: RequestInstanceState) {
  if (!state.refreshTokenFn) {
    state.refreshTokenFn = handleRefreshToken();
  }

  const success = await state.refreshTokenFn;

  setTimeout(() => {
    state.refreshTokenFn = null;
  }, 1000);

  return success;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
