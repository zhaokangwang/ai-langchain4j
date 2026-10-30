(() => {
  if (!window.Vue || !window.AuthApi) { document.getElementById('auth-boot-error').hidden = false; return; }
  const {createApp, ref, onMounted} = Vue;
  createApp({setup() {
    const passwordPage = document.body.dataset.authPage === 'password';
    const username = ref(''), password = ref(''), newPassword = ref(''), confirmation = ref('');
    const account = ref(null), loading = ref(true), busy = ref(false), error = ref('');
    const params = new URLSearchParams(window.location.search);
    const next = window.AuthApi.safeNext(params.get('next'));
    const notice = params.get('changed') === '1' ? '密码已修改，请使用新密码重新登录。' : '';
    function enter(current) {
      window.location.replace(current.passwordChangeRequired ? '/account.html?next=' + encodeURIComponent(next) : next);
    }
    async function submit() {
      if (busy.value || loading.value) return;
      error.value = '';
      if (passwordPage && newPassword.value !== confirmation.value) { error.value = '两次输入的新密码不一致。'; return; }
      busy.value = true;
      try {
        if (passwordPage) {
          await window.AuthApi.changePassword(password.value, newPassword.value);
          window.location.replace('/login.html?changed=1&next=' + encodeURIComponent(next));
        } else {
          enter(await window.AuthApi.login(username.value.trim(), password.value));
        }
      } catch (failure) { error.value = failure.message || '无法连接后端，请稍后重试。'; }
      finally { busy.value = false; password.value = ''; newPassword.value = ''; confirmation.value = ''; }
    }
    async function logout() {
      if (busy.value) return;
      busy.value = true; error.value = '';
      try { await window.AuthApi.logout(); window.location.replace('/login.html'); }
      catch { error.value = '退出失败，请重试。'; busy.value = false; }
    }
    onMounted(async () => {
      try {
        const current = await window.AuthApi.me(false);
        if (passwordPage) account.value = current; else enter(current);
      } catch (failure) {
        if (failure.status === 401) { if (passwordPage) window.AuthApi.loginPage(); }
        else error.value = '无法读取登录状态，请检查服务后刷新页面。';
      } finally { loading.value = false; }
    });
    return {username, password, newPassword, confirmation, account, loading, busy, error, notice, next, submit, logout};
  }}).mount('#auth');
})();
