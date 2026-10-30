const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../src/main/resources/static/js');
const response = (body, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), {
  status, headers:{'Content-Type':'application/json'}
});
function apiHarness(fetcher, page = '/reports.html?id=report-1') {
  const redirects = [], stored = new Map([['zhixu:last-conversation', 'old']]);
  const location = new URL('http://localhost' + page);
  location.replace = value => redirects.push(value);
  const window = {location, localStorage:{removeItem:key=>stored.delete(key)}};
  const context = vm.createContext({window, URL, fetch:fetcher});
  vm.runInContext(fs.readFileSync(path.join(root, 'auth.js'), 'utf8'), context);
  return {api:window.AuthApi, redirects, stored};
}

test('写请求取得 CSRF 并携带同源 Cookie，登录后刷新令牌且不保存密码', async () => {
  const calls = []; let tokens = 0;
  const app = apiHarness(async (url, options) => {
    calls.push({url, options});
    if (url === '/api/auth/csrf') return response({headerName:'X-CSRF-TOKEN', token:'token-' + ++tokens});
    if (url === '/api/auth/login') return response({username:'buyer-1', role:'buyer', passwordChangeRequired:false});
    if (url === '/api/auth/logout') return response(null, 204);
    return response({ok:true});
  });
  await app.api.login('buyer-1', 'secret-password');
  assert.equal(calls[1].options.headers['X-CSRF-TOKEN'], 'token-1');
  assert.equal(calls[1].options.credentials, 'same-origin');
  assert.deepEqual(JSON.parse(calls[1].options.body), {username:'buyer-1', password:'secret-password'});
  assert.equal(app.stored.size, 0);
  await app.api.request('/api/chat', {method:'POST', body:'{}'});
  assert.equal(calls.at(-1).options.headers['X-CSRF-TOKEN'], 'token-2');
  await app.api.request('/api/conversations');
  assert.equal(tokens, 2);
  await app.api.logout();
  assert.equal(tokens, 2);
  await app.api.request('/api/chat', {method:'POST'});
  assert.equal(tokens, 3);
});

test('401 返回登录页并保留本地报表路径，登录错误和登录页检查不会产生跳转循环', async () => {
  const app = apiHarness(async url => url === '/api/auth/csrf'
    ? response({headerName:'X-CSRF-TOKEN', token:'token'}) : response({message:'未登录'}, 401));
  await app.api.request('/api/conversations');
  assert.equal(app.redirects[0], '/login.html?next=%2Freports.html%3Fid%3Dreport-1');
  await assert.rejects(app.api.me(false), error=>error.status === 401);
  await assert.rejects(app.api.login('wrong-user', 'wrong-password'), error=>error.status === 401);
  assert.equal(app.redirects.length, 1);
});

test('强制改密时进入设置页；过期令牌不自动重发业务写请求', async () => {
  let tokens = 0, writes = 0, forceChange = false;
  const app = apiHarness(async url => {
    if (url === '/api/auth/csrf') return response({headerName:'X-CSRF-TOKEN', token:'token-' + ++tokens});
    writes++;
    return response(forceChange ? {passwordChangeRequired:true} : {message:'令牌过期'}, 403);
  });
  await app.api.request('/api/chat', {method:'POST'});
  assert.equal(writes, 1); assert.equal(tokens, 1); assert.equal(app.redirects.length, 0);
  forceChange = true;
  await app.api.request('/api/chat', {method:'POST'});
  assert.equal(writes, 2); assert.equal(tokens, 2); assert.equal(app.redirects[0], '/account.html');
});

test('登录后跳转只接受固定本地业务页面，拒绝外部地址和协议路径', () => {
  const {api} = apiHarness(async()=>response({}));
  assert.equal(api.safeNext('/reports.html?id=abc'), '/reports.html?id=abc');
  assert.equal(api.safeNext('/index.html'), '/index.html');
  assert.equal(api.safeNext('/admin-accounts.html'), '/admin-accounts.html');
  for (const value of ['//evil.example', 'https://evil.example', '/\\evil.example', '/login.html', '/api/auth/logout', '/reports.html/../login.html', null]) {
    assert.equal(api.safeNext(value), '/');
  }
});

function pageHarness({passwordPage = false, next = '/', me = async()=>{const error = new Error('未登录'); error.status = 401; throw error;}, login, changePassword} = {}) {
  let state, mounted;
  const redirects = [], calls = [];
  const window = {location:{search:'?next=' + encodeURIComponent(next), replace:value=>redirects.push(value)}};
  window.AuthApi = {safeNext:value=>value === '/reports.html' ? value : '/', me,
    login:async(...args)=>{calls.push(args); return login ? login(...args) : {passwordChangeRequired:false};},
    changePassword:async(...args)=>{calls.push(args); if (changePassword) await changePassword(...args);},
    loginPage:()=>redirects.push('/login.html'), logout:async()=>{}};
  const Vue = {ref:value=>({value}), onMounted:callback=>{mounted=callback;}, createApp:options=>({mount(){state=options.setup();}})};
  window.Vue = Vue;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'auth-page.js'), 'utf8'), {
    window, Vue, URLSearchParams, document:{body:{dataset:{authPage:passwordPage ? 'password' : 'login'}}, getElementById:()=>({})}
  });
  return {state, redirects, calls, initialize:()=>mounted()};
}

test('登录身份变化通知其他标签页刷新，通知不包含账号、密码或凭证', async () => {
  let channel, reloads = 0;
  const signals = [];
  class Channel {
    constructor(name) { this.name = name; channel = this; }
    postMessage(value) { signals.push(value); }
  }
  const window = {BroadcastChannel:Channel, location:{reload:()=>{reloads++;}}, localStorage:{removeItem(){}}};
  vm.runInNewContext(fs.readFileSync(path.join(root, 'auth.js'), 'utf8'), {window, URL,
    fetch:async url=>url === '/api/auth/csrf' ? response({headerName:'X-CSRF-TOKEN', token:'token'})
      : url === '/api/auth/login' ? response({username:'buyer-one', passwordChangeRequired:false}) : response(null, 204)});
  await window.AuthApi.login('buyer-one', 'private-password');
  await window.AuthApi.logout();
  window.AuthApi.sessionChanged();
  assert.deepEqual(signals, ['changed', 'changed', 'changed']);
  channel.onmessage({data:'changed'}); assert.equal(reloads, 1);
});

test('登录表单拒绝重复提交，成功进入改密页，并清空内存中的密码', async () => {
  let finish;
  const app = pageHarness({next:'/reports.html', login:()=>new Promise(resolve=>{finish=resolve;})});
  await app.initialize();
  app.state.username.value = ' admin '; app.state.password.value = 'admin';
  const first = app.state.submit(); await Promise.resolve(); await app.state.submit();
  assert.equal(app.calls.length, 1);
  assert.deepEqual(app.calls[0], ['admin', 'admin']);
  finish({passwordChangeRequired:true}); await first;
  assert.equal(app.redirects[0], '/account.html?next=%2Freports.html');
  assert.equal(app.state.password.value, ''); assert.equal(app.state.busy.value, false);
});

test('登录失败显示原因并保留账号，输入的密码不会保留', async () => {
  const app = pageHarness({login:()=>{throw new Error('账号或密码错误');}});
  await app.initialize(); app.state.username.value = 'buyer-1'; app.state.password.value = 'wrong-password';
  await app.state.submit();
  assert.match(app.state.error.value, /账号或密码错误/); assert.equal(app.state.username.value, 'buyer-1');
  assert.equal(app.state.password.value, ''); assert.equal(app.redirects.length, 0);
});

test('改密确认不一致不发送请求，保存成功要求重新登录', async () => {
  const app = pageHarness({passwordPage:true, next:'/reports.html', me:async()=>({username:'admin', passwordChangeRequired:true})});
  await app.initialize();
  app.state.password.value = 'admin'; app.state.newPassword.value = 'new-password'; app.state.confirmation.value = 'different';
  await app.state.submit(); assert.equal(app.calls.length, 0); assert.match(app.state.error.value, /不一致/);
  app.state.confirmation.value = 'new-password'; await app.state.submit();
  assert.deepEqual(app.calls[0], ['admin', 'new-password']);
  assert.equal(app.redirects[0], '/login.html?changed=1&next=%2Freports.html');
  assert.equal(app.state.password.value, ''); assert.equal(app.state.newPassword.value, ''); assert.equal(app.state.confirmation.value, '');
});

test('改密页面登录过期返回登录页，已有登录账号直接进入目标页面', async () => {
  const expired = pageHarness({passwordPage:true}); await expired.initialize();
  assert.equal(expired.redirects[0], '/login.html');
  const signedIn = pageHarness({next:'/reports.html', me:async()=>({passwordChangeRequired:false})});
  await signedIn.initialize(); assert.equal(signedIn.redirects[0], '/reports.html');
});
