(() => {
  if (!window.Vue || !window.AuthApi) { document.getElementById('accounts-boot-error').hidden = false; return; }
  const {createApp, ref, onMounted, nextTick} = Vue;
  createApp({setup() {
    const account = ref(null), items = ref([]), page = ref(0), hasMore = ref(false);
    const loading = ref(true), saving = ref(false), error = ref(''), notice = ref(''), forbidden = ref(false);
    const filter = ref({keyword:'', role:'', enabled:''}), pageSize = 20;
    const modal = ref(null), target = ref(null), dialog = ref(null), formError = ref('');
    const emptyForm = () => ({username:'', role:'buyer', password:'', confirmation:''});
    const form = ref(emptyForm());
    let selectedFilter = {keyword:'', role:'', enabled:''};
    const roleLabel = role => window.AuthApi.roleLabel(role);
    const time = value => new Date(value).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai', hour12:false});
    async function request(url, options) {
      const response = await window.AuthApi.request(url, options);
      if (!response.ok) {
        let message = '账号管理请求失败，请稍后重试。';
        try { const body = await response.json(); message = body.message || body.detail || message; } catch { /* 保留通用错误提示。 */ }
        if (response.status === 403) {
          try { const current = await window.AuthApi.me(); if (!current.administrator) { forbidden.value = true; items.value = []; } }
          catch { /* 会话过期由共享认证入口处理。 */ }
        }
        throw new Error(message);
      }
      return response.status === 204 ? null : response.json();
    }
    async function loadPage(number) {
      loading.value = true; error.value = '';
      const query = new URLSearchParams({page:String(number), size:String(pageSize)});
      for (const [key, value] of Object.entries(selectedFilter)) if (value !== '') query.set(key, value);
      try {
        const result = await request('/api/admin/accounts?' + query);
        items.value = result.items; page.value = result.page; hasMore.value = result.hasMore;
      } catch (failure) { error.value = failure.message; }
      finally { loading.value = false; }
    }
    async function search() {
      if (loading.value || saving.value || forbidden.value) return;
      selectedFilter = {...filter.value, keyword:filter.value.keyword.trim()};
      items.value = []; page.value = 0; hasMore.value = false; notice.value = '';
      await loadPage(0);
    }
    async function turnPage(number) {
      if (loading.value || saving.value || number < 0 || forbidden.value) return;
      await loadPage(number);
    }
    async function openDialog(kind, row = null) {
      if (loading.value || saving.value || forbidden.value || !account.value?.administrator) return;
      modal.value = kind; target.value = row; form.value = emptyForm(); formError.value = ''; notice.value = '';
      await nextTick(); dialog.value?.showModal();
    }
    function closeDialog() {
      if (saving.value) return;
      dialog.value?.close(); modal.value = null; target.value = null; form.value = emptyForm(); formError.value = '';
    }
    async function submit() {
      if (saving.value || !modal.value || forbidden.value) return;
      formError.value = '';
      if (form.value.password !== form.value.confirmation) { formError.value = '两次输入的密码不一致。'; return; }
      saving.value = true;
      const mode = modal.value, selected = target.value;
      try {
        const url = mode === 'create' ? '/api/admin/accounts' : '/api/admin/accounts/' + encodeURIComponent(selected.id) + '/password';
        const body = mode === 'create'
          ? {username:form.value.username.trim(), password:form.value.password, role:form.value.role}
          : {newPassword:form.value.password};
        await request(url, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
        saving.value = false; closeDialog();
        if (mode === 'reset' && selected.id === account.value.id) {
          window.AuthApi.sessionChanged?.();
          window.location.replace('/login.html?changed=1&next=%2Fadmin-accounts.html'); return;
        }
        notice.value = mode === 'create' ? '账号已创建，首次登录需要修改初始密码。' : '密码已重置，旧登录已失效，下次登录需要修改密码。';
        if (mode === 'create') { filter.value = {keyword:'', role:'', enabled:''}; selectedFilter = {...filter.value}; }
        await loadPage(mode === 'create' ? 0 : page.value);
      } catch (failure) { formError.value = failure.message; form.value.password = ''; form.value.confirmation = ''; }
      finally { saving.value = false; }
    }
    function protectedFromDisable(row) { return row.enabled && (row.username === 'admin' || row.id === account.value?.id); }
    async function toggleStatus(row) {
      if (loading.value || saving.value || forbidden.value || protectedFromDisable(row)) return;
      const enabled = !row.enabled;
      if (!window.confirm(`确定${enabled ? '启用' : '禁用'}账号 ${row.username}？${enabled ? '用户需要重新登录。' : '已有登录将失效。'}`)) return;
      saving.value = true; error.value = ''; notice.value = '';
      try {
        await request('/api/admin/accounts/' + encodeURIComponent(row.id) + '/status', {
          method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled})
        });
        notice.value = `账号 ${row.username} 已${enabled ? '启用' : '禁用'}。`;
        await loadPage(page.value);
        if (!items.value.length && page.value > 0 && !error.value) await loadPage(page.value - 1);
      } catch (failure) { error.value = failure.message; }
      finally { saving.value = false; }
    }
    onMounted(async () => {
      try {
        account.value = await window.AuthApi.me();
        if (!account.value.administrator) { forbidden.value = true; error.value = '当前账号没有账号管理权限。'; return; }
        if (account.value.passwordChangeRequired) { window.location.replace('/account.html?next=%2Fadmin-accounts.html'); return; }
        await loadPage(0);
      } catch (failure) { error.value = failure.message || '无法读取管理员身份，请刷新后重试。'; }
      finally { loading.value = false; }
    });
    return {account, items, page, hasMore, loading, saving, error, notice, forbidden, filter, modal, target,
      dialog, form, formError, roleLabel, time, search, turnPage, openDialog, closeDialog, submit, toggleStatus, protectedFromDisable};
  }}).mount('#account-admin');
})();
