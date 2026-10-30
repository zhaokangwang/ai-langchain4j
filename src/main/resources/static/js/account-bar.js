(() => {
  const bar = document.querySelector('[data-account-bar]');
  if (!bar || !window.AuthApi) return;
  const name = bar.querySelector('[data-account-name]'), button = bar.querySelector('[data-logout]');
  const error = bar.querySelector('[data-account-error]');
  window.AuthApi.me().then(account => {
    if (account.passwordChangeRequired) { window.location.replace('/account.html'); return; }
    name.textContent = account.username + ' · ' + window.AuthApi.roleLabel(account.role);
    const management = bar.querySelector('[data-account-admin]');
    if (management) management.hidden = !account.administrator;
    button.disabled = false;
  }).catch(() => { error.textContent = '无法读取登录账号，请刷新页面重试。'; });
  button.addEventListener('click', async () => {
    button.disabled = true; error.textContent = '';
    try { await window.AuthApi.logout(); window.location.replace('/login.html'); }
    catch { error.textContent = '退出失败，请重试。'; button.disabled = false; }
  });
})();
