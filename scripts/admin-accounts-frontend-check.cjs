const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../src/main/resources/static/js');
const result = (body, status = 200) => ({ok:status >= 200 && status < 300, status, json:async()=>body});
const page = (items = [], number = 0, hasMore = false) => ({items, page:number, size:20, hasMore});
function harness(fetcher = async()=>result(page()), options = {}) {
  let state, mounted;
  const calls = [], redirects = [], confirmations = [];
  const current = options.current || {id:'manager-id', username:'admin', administrator:true, passwordChangeRequired:false};
  const window = {location:{replace:url=>redirects.push(url)}, confirm:message=>{confirmations.push(message); return options.confirm !== false;}};
  window.AuthApi = {roleLabel:role=>role, me:options.me || (async()=>current), request:async(url, config)=>{calls.push([url, config]); return fetcher(url, config);}};
  const Vue = {ref:value=>({value}), nextTick:async()=>{}, onMounted:callback=>{mounted=callback;}, createApp:config=>({mount(){state=config.setup();}})};
  window.Vue = Vue;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'admin-accounts.js'), 'utf8'), {
    window, Vue, URLSearchParams, document:{getElementById:()=>({})}
  });
  state.dialog.value = {showModal(){}, close(){}};
  return {state, calls, redirects, confirmations, initialize:()=>mounted()};
}

test('只允许管理员读取账号列表；待改密管理员先进入改密页', async()=>{
  const admin = harness(); await admin.initialize();
  assert.equal(admin.calls.length, 1); assert.match(admin.calls[0][0], /page=0&size=20/);
  const ordinary = harness(undefined, {current:{id:'ordinary', administrator:false}}); await ordinary.initialize();
  assert.equal(ordinary.calls.length, 0); assert.equal(ordinary.state.forbidden.value, true);
  const forced = harness(undefined, {current:{id:'manager', administrator:true, passwordChangeRequired:true}}); await forced.initialize();
  assert.equal(forced.calls.length, 0); assert.equal(forced.redirects[0], '/account.html?next=%2Fadmin-accounts.html');
});

test('筛选提交从第一页开始，保留 false 状态，分页失败保留已有列表', async()=>{
  let fail = false;
  const app = harness(async url=>fail ? result({message:'读取失败'}, 503) : result(page([{id:'one', username:'buyer_1'}], Number(new URLSearchParams(url.split('?')[1]).get('page')), true)));
  await app.initialize();
  app.state.filter.value = {keyword:' buyer_ ', role:'buyer', enabled:'false'};
  await app.state.search();
  const query = new URLSearchParams(app.calls.at(-1)[0].split('?')[1]);
  assert.equal(query.get('keyword'), 'buyer_'); assert.equal(query.get('role'), 'buyer'); assert.equal(query.get('enabled'), 'false'); assert.equal(query.get('page'), '0');
  await app.state.turnPage(1); assert.equal(app.state.page.value, 1);
  fail = true; await app.state.turnPage(2);
  assert.equal(app.state.page.value, 1); assert.equal(app.state.items.value[0].id, 'one'); assert.match(app.state.error.value, /读取失败/);
});

test('创建账号校验密码确认、防止重复提交，只发送账号密码角色，成功后清空密码', async()=>{
  let finish;
  const app = harness(async (url, options)=>options?.method === 'POST' ? new Promise(resolve=>{finish=resolve;}) : result(page()));
  await app.initialize(); await app.state.openDialog('create');
  app.state.form.value = {username:' warehouse-1 ', role:'warehouse', password:'initial-password', confirmation:'wrong'};
  await app.state.submit(); assert.equal(app.calls.length, 1); assert.match(app.state.formError.value, /不一致/);
  app.state.form.value.confirmation = 'initial-password';
  const first = app.state.submit(); await Promise.resolve(); await app.state.submit(); app.state.closeDialog();
  assert.equal(app.state.modal.value, 'create'); assert.equal(app.calls.length, 2);
  assert.deepEqual(JSON.parse(app.calls[1][1].body), {username:'warehouse-1', role:'warehouse', password:'initial-password'});
  finish(result({id:'new-user'}, 201)); await first;
  assert.equal(app.state.modal.value, null); assert.equal(app.state.form.value.password, ''); assert.equal(app.state.form.value.confirmation, '');
  assert.match(app.state.notice.value, /首次登录/); assert.match(app.calls.at(-1)[0], /page=0/);
});

test('账号重复或创建失败显示服务端提示，保留账号角色并清除密码', async()=>{
  const app = harness(async (url, options)=>options?.method === 'POST' ? result({message:'账号已存在'}, 409) : result(page()));
  await app.initialize(); await app.state.openDialog('create');
  app.state.form.value = {username:'buyer-1', role:'buyer', password:'initial-password', confirmation:'initial-password'};
  await app.state.submit();
  assert.equal(app.state.modal.value, 'create'); assert.equal(app.state.form.value.username, 'buyer-1'); assert.equal(app.state.form.value.role, 'buyer');
  assert.equal(app.state.form.value.password, ''); assert.match(app.state.formError.value, /账号已存在/); assert.equal(app.state.saving.value, false);
});

test('重置其他账号只提交新密码；重置自己后返回登录页，不再使用旧登录刷新列表', async()=>{
  const app = harness(async (url, options)=>options?.method === 'POST' ? result(null, 204) : result(page()));
  await app.initialize(); await app.state.openDialog('reset', {id:'other-user', username:'buyer'});
  app.state.form.value.password = app.state.form.value.confirmation = 'reset-password'; await app.state.submit();
  const reset = app.calls.find(([url])=>url.endsWith('/other-user/password'));
  assert.deepEqual(JSON.parse(reset[1].body), {newPassword:'reset-password'}); assert.match(app.state.notice.value, /旧登录已失效/);
  await app.state.openDialog('reset', {id:'manager-id', username:'admin'});
  app.state.form.value.password = app.state.form.value.confirmation = 'own-reset-password';
  const before = app.calls.length; await app.state.submit();
  assert.equal(app.calls.length, before + 1); assert.equal(app.redirects[0], '/login.html?changed=1&next=%2Fadmin-accounts.html');
});

test('禁用需要确认并提交布尔 false，保护预设管理员及当前账号', async()=>{
  const app = harness(async (url, options)=>options?.method === 'PATCH' ? result({enabled:false}) : result(page()));
  await app.initialize();
  await app.state.toggleStatus({id:'root', username:'admin', enabled:true});
  await app.state.toggleStatus({id:'manager-id', username:'manager', enabled:true});
  assert.equal(app.calls.length, 1); assert.equal(app.confirmations.length, 0);
  await app.state.toggleStatus({id:'ordinary', username:'buyer-1', enabled:true});
  const change = app.calls.find(([, options])=>options?.method === 'PATCH');
  assert.deepEqual(JSON.parse(change[1].body), {enabled:false}); assert.match(app.confirmations[0], /已有登录将失效/);
  const cancelled = harness(undefined, {confirm:false}); await cancelled.initialize();
  await cancelled.state.toggleStatus({id:'ordinary', username:'buyer-1', enabled:true}); assert.equal(cancelled.calls.length, 1);
});

test('启用账号后当前筛选页变空时回到上一页', async()=>{
  let changed = false;
  const app = harness(async (url, options)=>{
    if (options?.method === 'PATCH') { changed=true; return result({enabled:true}); }
    const number = Number(new URLSearchParams(url.split('?')[1]).get('page'));
    return result(page(changed ? [] : [{id:'disabled', username:'buyer', enabled:false}], number));
  });
  await app.initialize(); await app.state.turnPage(1);
  await app.state.toggleStatus({id:'disabled', username:'buyer', enabled:false});
  const update = app.calls.find(([, options])=>options?.method === 'PATCH');
  assert.deepEqual(JSON.parse(update[1].body), {enabled:true}); assert.equal(app.state.page.value, 0);
});

test('管理员权限撤销后清空列表并阻止继续提交', async()=>{
  let revoked = false;
  const app = harness(async()=>revoked ? result({message:'没有管理权限'}, 403) : result(page([{id:'one'}])), {
    me:async()=>({id:'manager-id', administrator:!revoked})
  });
  await app.initialize(); revoked=true; await app.state.search();
  assert.equal(app.state.forbidden.value, true); assert.equal(app.state.items.value.length, 0);
  const before = app.calls.length; await app.state.openDialog('create'); assert.equal(app.calls.length, before); assert.equal(app.state.modal.value, null);
});

test('账号栏仅为管理员显示管理入口，退出按钮仍走 Session 注销', async()=>{
  for (const administrator of [false, true]) {
    let click, loggedOut = 0;
    const redirects = [], name = {}, management = {hidden:true}, error = {}, button = {disabled:true, addEventListener:(event, listener)=>{click=listener;}};
    const elements = {'[data-account-name]':name, '[data-account-admin]':management, '[data-account-error]':error, '[data-logout]':button};
    const window = {location:{replace:url=>redirects.push(url)}, AuthApi:{me:async()=>({username:'test', role:'buyer', administrator}), roleLabel:()=> '买家', logout:async()=>{loggedOut++;}}};
    vm.runInNewContext(fs.readFileSync(path.join(root, 'account-bar.js'), 'utf8'), {window, document:{querySelector:()=>({querySelector:key=>elements[key]})}});
    await Promise.resolve(); await Promise.resolve();
    assert.equal(management.hidden, !administrator); assert.equal(name.textContent, 'test · 买家'); assert.equal(button.disabled, false);
    await click(); assert.equal(loggedOut, 1); assert.equal(redirects[0], '/login.html');
  }
});
