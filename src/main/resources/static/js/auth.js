/* Session Cookie 由浏览器管理；密码和登录凭证不写入浏览器存储。 */
(() => {
  let csrfPromise = null;
  let authEvents = null;
  try {
    if (window.BroadcastChannel) {
      authEvents = new window.BroadcastChannel('zhixu-auth-events');
      authEvents.onmessage = () => window.location.reload();
    }
  } catch { /* 浏览器不支持跨标签页通知时，服务端仍校验每次请求。 */ }
  const roleLabels = {buyer:'买家', service:'客服', warehouse:'仓库'};
  function safeNext(value) {
    try {
      if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) return '/';
      const url = new URL(value, window.location.origin);
      return url.origin === window.location.origin && ['/', '/index.html', '/reports.html', '/admin-accounts.html'].includes(url.pathname)
        ? url.pathname + url.search : '/';
    } catch { return '/'; }
  }
  function loginPage() {
    const next = safeNext(window.location.pathname + window.location.search);
    window.location.replace('/login.html?next=' + encodeURIComponent(next));
  }
  async function csrf() {
    if (!csrfPromise) {
      csrfPromise = fetch('/api/auth/csrf', {credentials:'same-origin', cache:'no-store'})
        .then(async response => {
          if (!response.ok) throw new Error('无法获取请求令牌，请刷新页面后重试。');
          const data = await response.json();
          if (data.headerName !== 'X-CSRF-TOKEN' || typeof data.token !== 'string' || !data.token) throw new Error('请求令牌格式无效。');
          return data;
        }).catch(error => { csrfPromise = null; throw error; });
    }
    return csrfPromise;
  }
  async function request(url, options = {}, redirect = true) {
    const headers = {...options.headers};
    if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
      const token = await csrf();
      headers[token.headerName] = token.token;
    }
    const response = await fetch(url, {...options, headers, credentials:'same-origin'});
    if (response.status === 401) {
      csrfPromise = null;
      if (redirect) loginPage();
    } else if (response.status === 403) {
      csrfPromise = null;
      let body;
      try { body = await response.clone().json(); } catch { /* 普通拒绝可能没有 JSON 正文。 */ }
      if (redirect && body?.passwordChangeRequired) window.location.replace('/account.html');
    }
    return response;
  }
  async function json(url, options, redirect = true) {
    const response = await request(url, options, redirect);
    if (!response.ok) {
      let message = ({400:'输入未通过校验，请检查后重试。',401:'账号或密码错误，或登录已过期。',403:'请求令牌无效，请重试。',409:'账号状态已变化，请重新登录。'})[response.status] || '账号服务暂不可用，请稍后重试。';
      try { const body = await response.json(); message = body.message || body.detail || message; } catch { /* 保留状态码对应提示。 */ }
      const error = new Error(message); error.status = response.status; throw error;
    }
    return response.status === 204 ? null : response.json();
  }
  const post = body => ({method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
  function forgetConversation() {
    try { window.localStorage.removeItem('zhixu:last-conversation'); } catch { /* 禁用浏览器存储时仍可登录。 */ }
    try { authEvents?.postMessage('changed'); } catch { /* 通知失败不能影响已完成的登录或退出。 */ }
  }
  window.AuthApi = Object.freeze({
    request, safeNext, loginPage, sessionChanged:forgetConversation, roleLabel:role => roleLabels[role] || role,
    me(redirect = true) { return json('/api/auth/me', undefined, redirect); },
    async login(username, password) {
      const account = await json('/api/auth/login', post({username, password}), false);
      csrfPromise = null; forgetConversation(); return account;
    },
    async logout() { await json('/api/auth/logout', {method:'POST'}); csrfPromise = null; forgetConversation(); },
    async changePassword(currentPassword, newPassword) {
      await json('/api/auth/password', post({currentPassword, newPassword})); csrfPromise = null; forgetConversation();
    }
  });
})();
