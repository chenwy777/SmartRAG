export function buildInviteChannelGuide() {
  return [
    '获取 SmartRAG 邀请码方式：',
    '1. 联系当前站点管理员申请邀请码',
    '2. 收到邀请码后，回到注册页继续完成注册'
  ].join('\n');
}

export function buildInviteCodeShareMessage(shareLink: string, inviteCode: string) {
  return [
    '欢迎体验 SmartRAG。',
    `邀请码：${inviteCode}`,
    `注册链接：${shareLink}`,
    '',
    '如果邀请码失效或需要新的邀请码，请联系当前站点管理员。'
  ].join('\n');
}
