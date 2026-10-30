// 无 npm 依赖的前端行为检查：模拟浏览器边界，不调用真实模型。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../src/main/resources/static/js');
test('报表按钮仅来自成功工具结果，并固定导航到本地报表页',async()=>{
  const reportId='00000000-0000-4000-8000-000000000001';
  const app=harness(async()=>({answer:'报表已生成',conversationId:'s1',requestId:'r1',executedTools:['queryOrderReport']}),{
    run:async()=>({tools:[{name:'queryOrderReport',status:'COMPLETED',resultText:JSON.stringify({reportId,summary:{orderCount:3},previewUrl:'https://evil.example'})},
      {name:'queryOrderReport',status:'FAILED',resultText:JSON.stringify({reportId,summary:{orderCount:99}})},
      {name:'queryOrderReport',status:'COMPLETED',resultText:JSON.stringify({reportId:'javascript:alert(1)'})}],models:[],events:[]})
  });
  app.draft.value='生成订单报表';await app.send();
  const links=app.reportLinks('r1');assert.equal(links.length,1);assert.equal(links[0].count,3);
  assert.equal(links[0].url,'/reports.html?id='+reportId);
});
function harness(sendMessage, overrides = {}, storage = new Map(), content = {copyText:async()=>true}) {
  let state;
  let mounted;
  const api = { sendMessage, streamMessage:async(message,id,requestId)=>sendMessage(message,id,requestId),
    capabilities:async()=>({account:{id:'demo-user',role:'BUYER',scope:'本人订单'},skills:[]}),
    cancelStream:async()=>({cancelRequested:true}),listConversations: async () => ({items:[], hasMore:false}), ...overrides };
  const context = vm.createContext({ console, setInterval, clearInterval, setTimeout, clearTimeout, AbortController,
    window: { innerWidth: 1000, confirm: () => true, prompt: () => '新的标题', ChatApi: api, ChatContent:content,
      localStorage: {getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)} },
    document: { getElementById: () => ({ hidden: true }) },
    Vue: { ref: value => ({ value }), nextTick: async () => {}, onBeforeUnmount() {}, onMounted:callback=>{mounted=callback;},
      createApp: options => ({ component(){return this;},directive(){return this;}, mount: () => { state = options.setup(); } }) }
  });
  context.window.Vue = context.Vue;
  context.ChatApi = context.window.ChatApi;
  vm.runInContext(fs.readFileSync(path.join(root, 'insights.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(root, 'chat.js'), 'utf8'), context);
  state.initialize=()=>mounted();
  return state;
}

test('后端身份控制技能展示和示例，能力读取失败可重试，角色变化清空旧会话',async()=>{
  let account='service', fail=false;
  const app=harness(async()=>({answer:'回答',conversationId:'s1'}),{capabilities:async()=>{
    if(fail)throw new Error('后端不可用');
    return {account:{id:account==='service'?'demo-service':'demo-warehouse',role:account==='service'?'CUSTOMER_SERVICE':'WAREHOUSE',scope:'模拟授权范围'},skills:[{name:account==='service'?'after-sales-review':'warehouse-stock-check'}]};
  }});
  await app.initialize();assert.equal(app.capabilities.value.account.role,'CUSTOMER_SERVICE');
  assert.match(app.examples.value[0].message,/AS2001/);
  app.draft.value='问题';await app.send();assert.equal(app.conversationId.value,'s1');
  fail=true;await app.refreshCapabilities();assert.equal(app.capabilities.value,null);assert.match(app.capabilityError.value,/不可用/);
  fail=false;account='warehouse';await app.refreshCapabilities();
  assert.equal(app.capabilities.value.account.role,'WAREHOUSE');assert.equal(app.messages.value.length,0);assert.equal(app.conversationId.value,null);
  assert.match(app.examples.value[0].message,/WH01/);
});

test('能力刷新慢响应不能覆盖新身份',async()=>{
  let resolve;let count=0;
  const app=harness(async()=>{}, {capabilities:async()=>++count===1?new Promise(r=>resolve=r):{account:{id:'new',role:'WAREHOUSE'},skills:[]}});
  const slow=app.refreshCapabilities();await Promise.resolve();await app.refreshCapabilities();
  resolve({account:{id:'old',role:'BUYER'},skills:[]});await slow;
  assert.equal(app.capabilities.value.account.id,'new');
});

test('历史摘要按所选尝试恢复身份和路由，不把直接回复误标为模型回答',async()=>{
  const events=[1,2].flatMap(attemptNo=>[
    {type:'AUTHORIZATION',attemptNo,details:JSON.stringify({account:{id:'historic-user',role:'BUYER',scope:'历史授权范围'},skills:['order-query']})},
    {type:'ROUTING',attemptNo,details:JSON.stringify({reason:attemptNo===1?'等待模型':'业务范围未授权',candidates:attemptNo===1?['order-query']:[],outcome:attemptNo===1?'MODEL':'DIRECT',state:{category:'order',orderNumber:'A1001'}})}]);
  const app=harness(async()=>{}, {run:async(id,attempt=2)=>({requestId:id,selectedAttempt:attempt,tools:[],models:attempt===1?[{}]:[],events})});
  await app.showRun('r',1);assert.equal(app.runSummary.value.modelLabel,'大模型参与');assert.equal(app.runSummary.value.routing.reason,'等待模型');
  await app.showRun('r',2);assert.equal(app.runSummary.value.modelLabel,'后端直接回复（未调用模型）');assert.equal(app.runSummary.value.routing.reason,'业务范围未授权');
  assert.equal(app.runSummary.value.routing.orderNumber,'');assert.equal(app.runSummary.value.routing.category,'');
  assert.equal(app.runSummary.value.authorization.account.id,'historic-user');
  await app.loadTrace('r');assert.equal(app.toolTraces.value.r.summary.modelCount,0);
  const legacy=harness(async()=>{}, {run:async()=>({selectedAttempt:1,tools:[],models:[],events:[{type:'ROUTING',details:'not json'},{type:'AUTHORIZATION',details:'{}'}]})});
  await legacy.showRun('old');assert.equal(legacy.runSummary.value.missing,true);assert.equal(legacy.runSummary.value.authorization,null);
});

test('实时授权、候选和已确认对象来自 SSE，下一轮清空旧路由',async()=>{
  let count=0;
  const app=harness(async()=>{}, {streamMessage:async(text,id,requestId,onEvent)=>{
    if(++count===1){onEvent('authorization',{account:{id:'demo-user',role:'BUYER'}});onEvent('routing',{reason:'关键词召回',candidates:['refund-progress-query'],state:{category:'refund',orderNumber:'A1001'},outcome:'MODEL'});onEvent('model_start',{round:1});
      assert.equal(app.liveInsight().routing.orderNumber,'A1001');assert.equal(app.liveInsight().modelCount,1);}
    else {assert.equal(app.liveRouting.value,null);assert.equal(app.liveAuthorization.value,null);onEvent('routing',{reason:'业务范围未授权',candidates:[],state:{},outcome:'DIRECT'});assert.match(app.liveInsight().modelLabel,/后端直接回复/);}
    return {answer:'回复',conversationId:'s1',requestId};
  }});
  app.draft.value='退款';await app.send();app.draft.value='仓库库存';await app.send();
  app.newConversation();assert.equal(app.liveRouting.value,null);
});

test('同角色共享分区保持会话，执行摘要保留历史实际账号而非当前登录账号',async()=>{
  let username='buyer-one';
  const app=harness(async()=>({answer:'已保存',conversationId:'shared-conversation'}),{
    capabilities:async()=>({account:{id:'demo-user',role:'BUYER',scope:'买家角色共享订单'},actor:{id:username,username},skills:[]}),
    run:async()=>({selectedAttempt:1,tools:[],models:[],events:[{type:'AUTHORIZATION',attemptNo:1,
      details:JSON.stringify({account:{id:'demo-user',role:'BUYER'},actor:{id:'buyer-one',username:'buyer-one',role:'buyer'},skills:[]})}]})
  });
  await app.initialize();app.draft.value='解释 Java';await app.send();
  username='buyer-two';await app.refreshCapabilities();
  assert.equal(app.conversationId.value,'shared-conversation');assert.equal(app.capabilities.value.actor.username,'buyer-two');
  await app.showRun('shared-request');assert.equal(app.runSummary.value.authorization.actor.username,'buyer-one');
});
test('连续会话使用返回 ID；新建会话清空上下文', async () => {
  const calls = [];
  const app = harness(async (message, id) => { calls.push({ message, id }); return { answer: '回复', conversationId: 'session-1' }; });
  app.draft.value = '键盘坏了'; await app.send();
  app.draft.value = '机械键盘那个'; await app.send();
  assert.ok(calls[0].id);
  assert.equal(calls[1].id, 'session-1');
  assert.equal(app.messages.value.length, 4);
  app.newConversation();
  app.draft.value = '新问题'; await app.send();
  assert.ok(calls[2].id);
  assert.notEqual(calls[2].id, calls[0].id);
  assert.equal(app.messages.value.length, 2);
});

test('刷新恢复最近会话，切换慢请求不覆盖新会话，归档不清除数据库', async () => {
  const storage=new Map([['zhixu:last-conversation','session-old']]);
  let slowResolve;
  const changes=[];
  const app=harness(async()=>{}, {
    messages: async id => {
      if(id==='slow') return new Promise(resolve=>{slowResolve=resolve;});
      return {conversation:{id,title:id,status:'ACTIVE'},items:[{id:1,role:'USER',content:id,requestId:'r',status:'COMPLETED'}],hasMore:false};
    },
    changeConversation:async (id,change)=>{changes.push(change);return {id,title:change.title||id,status:change.status||'ACTIVE'};}
  },storage);
  await app.initialize();
  assert.equal(app.conversationId.value,'session-old');
  const slow=app.openConversation('slow');
  await Promise.resolve();
  await app.openConversation('fast');
  slowResolve({conversation:{id:'slow'},items:[],hasMore:false});await slow;
  assert.equal(app.conversationId.value,'fast');
  assert.equal(app.messages.value[0].content,'fast');
  await app.renameConversation();assert.equal(changes[0].title,'新的标题');
  await app.toggleArchive();assert.equal(app.current.value.status,'ARCHIVED');
  assert.equal(app.messages.value.length,1);
  app.draft.value='不应发送'; await app.send();assert.equal(app.messages.value.length,1);
  await app.toggleArchive();assert.equal(app.current.value.status,'ACTIVE');
});

test('分页加载旧消息不重复，执行记录可按尝试切换', async()=>{
  const attempts=[];
  const app=harness(async()=>{}, {
    messages:async(id,before)=>({conversation:{id,title:'会话',status:'ACTIVE'},
      items:before?[{id:1,role:'USER',content:'旧消息',status:'COMPLETED'},{id:2,role:'ASSISTANT',content:'当前',status:'COMPLETED'}]:[{id:2,role:'ASSISTANT',content:'当前',status:'COMPLETED'}],
      hasMore:!before,nextBeforeId:before?null:2}),
    run:async(id,attempt)=>{attempts.push(attempt);return {requestId:id,selectedAttempt:attempt||2};}
  });
  await app.openConversation('session');await app.loadOlder();
  assert.equal(app.messages.value.length,2);
  assert.equal(app.messages.value[0].content,'旧消息');
  await app.showRun('r');await app.showRun('r',1);
  assert.equal(app.runDetail.value.selectedAttempt,1);
  assert.deepEqual(attempts,[undefined,1]);
});

test('复制回答保留 Markdown，展开工具摘要不触发聊天模型', async()=>{
  const copied=[];let runs=0,chats=0;
  const app=harness(async()=>{chats++;}, {run:async()=>{runs++;return {tools:[{id:'t1',name:'loadSkill',skillName:'after-sales-review'}]};}},new Map(),
    {copyText:async text=>{copied.push(text);return true;}});
  await app.copyAnswer({id:1,content:'## 标题\n```java\nint x = 1;\n```'});
  assert.equal(app.copiedAnswer.value,1);
  assert.equal(copied[0],'## 标题\n```java\nint x = 1;\n```');
  await app.loadTrace('r1');
  app.toggleTrace({target:{open:true}},'r1');
  assert.equal(runs,1);assert.equal(chats,0);
  assert.equal(app.toolTraces.value.r1.tools[0].skillName,'after-sales-review');
});

test('阅读历史期间回答返回不强制滚动，点击最新消息才回到底部', async()=>{
  let resolve;
  let entered;const ready=new Promise(done=>{entered=done;});
  const app=harness(()=>new Promise(done=>{resolve=done;entered();}));
  app.scrollArea.value={scrollHeight:1000,clientHeight:400,scrollTop:600};
  app.draft.value='测试滚动';const sent=app.send();
  await ready;
  app.scrollArea.value.scrollTop=80;app.handleScroll();
  resolve({answer:'新的回答',conversationId:'scroll-session',requestId:'scroll-request'});await sent;
  assert.equal(app.scrollArea.value.scrollTop,80);assert.equal(app.showLatest.value,true);
  await app.jumpLatest();assert.equal(app.scrollArea.value.scrollTop,1000);assert.equal(app.showLatest.value,false);
});
test('失败保留输入，重试不重复添加用户消息', async () => {
  let count = 0;
  const requestIds = [];
  const app = harness(async (message, conversation, requestId) => { requestIds.push(requestId); if (++count === 1) throw Error('网络失败'); return { answer: '恢复成功', conversationId: 's-2' }; });
  app.draft.value = '查询订单'; await app.send();
  assert.equal(app.draft.value, '查询订单');
  assert.equal(app.messages.value[0].failed, true);
  assert.equal(app.pending.value, false);
  await app.retry();
  assert.equal(app.messages.value.length, 2);
  assert.equal(app.messages.value[0].failed, false);
  assert.equal(app.error.value, '');
  assert.ok(requestIds[0]);
  assert.equal(requestIds[0], requestIds[1]);
});
test('等待期间阻止重复发送和清空会话；中文输入法回车不发送', async () => {
  let resolve;
  let calls = 0;
  const app = harness(() => { calls++; return new Promise(done => { resolve = done; }); });
  app.draft.value = '测试';
  app.onKeydown({ key: 'Enter', isComposing: true });
  assert.equal(calls, 0);
  const pending = app.send();
  await Promise.resolve(); await Promise.resolve();
  assert.equal(app.pending.value, true);
  app.newConversation(); await app.send();
  assert.equal(calls, 1);
  assert.equal(app.messages.value.length, 1);
  resolve({ answer: '完成', conversationId: 's-3' }); await pending;
  assert.equal(app.pending.value, false);
});
test('Fetch 使用真实接口路径和会话参数，并处理 HTTP 错误', async () => {
  let captured;
  const context = vm.createContext({ window: {}, fetch: async (url, options) => {
    captured = { url, body: JSON.parse(options.body) };
    return { ok: true, json: async () => ({ answer: '回答', conversationId: 'valid-id' }) };
  } });
  context.window.AuthApi = {request:(...args)=>context.fetch(...args)};
  vm.runInContext(fs.readFileSync(path.join(root, 'api.js'), 'utf8'), context);
  await context.window.ChatApi.sendMessage('问题', null);
  assert.equal(captured.url, '/api/chat');
  assert.equal('conversationId' in captured.body, false);
  await context.window.ChatApi.sendMessage('追问', 'valid-id', 'request-1');
  assert.equal(captured.body.conversationId, 'valid-id');
  assert.equal(captured.body.requestId, 'request-1');
  context.fetch = async () => ({ ok: false, status: 502 });
  await assert.rejects(context.window.ChatApi.sendMessage('问题'), /调用达到上限/);
  context.fetch = async () => ({ ok: true, json: async () => ({ answer: '错误的响应' }) });
  await assert.rejects(context.window.ChatApi.sendMessage('问题'), /缺少回答或有效会话/);
});

test('实时事件分轮展示文本和真实工具进度，普通模式仍使用旧接口',async()=>{
  let jsonCalls=0;
  const app=harness(async()=>{jsonCalls++;return {answer:'普通回答',conversationId:'s1',requestId:'j1'};},{
    streamMessage:async(message,id,requestId,event)=>{
      event('accepted',{conversationId:id});event('model_start',{round:1});event('delta',{round:1,text:'准备查询'});
      event('model_complete',{round:1,hasToolCalls:true,text:'准备查询'});assert.equal(app.liveText.value,'');
      event('tool_start',{id:'t1',name:'loadSkill',round:1});assert.equal(app.liveSteps.value[0].status,'RUNNING');
      event('tool_complete',{id:'t1',name:'loadSkill',round:1,status:'COMPLETED',skillName:'order-query',durationMs:2});
      event('model_start',{round:2});event('delta',{round:2,text:'已发货'});
      assert.equal(app.liveText.value,'已发货');
      return {answer:'已发货',conversationId:id,requestId};
    }
  });
  app.draft.value='查订单';await app.send();
  assert.equal(app.messages.value[1].content,'已发货');assert.equal(app.liveSteps.value[0].status,'COMPLETED');
  app.streamMode.value=false;app.draft.value='普通提问';await app.send();assert.equal(jsonCalls,1);
});

test('停止生成保留未完成片段，不把停止操作作为相同请求自动重试',async()=>{
  let entered;const ready=new Promise(done=>{entered=done;});let cancelled=0;
  const app=harness(async()=>{}, {
    streamMessage:async(message,id,requestId,event,signal)=>new Promise((resolve,reject)=>{
      event('delta',{text:'未完成片段'});entered();
      signal.addEventListener('abort',()=>{const error=new Error('abort');error.name='AbortError';reject(error);});
    }),cancelStream:async()=>{cancelled++;}
  });
  app.draft.value='长回答';const sent=app.send();await ready;await app.stopGeneration();await sent;
  assert.equal(cancelled,1);assert.equal(app.messages.value[0].status,'INTERRUPTED');
  assert.equal(app.messages.value[1].incomplete,true);assert.equal(app.failedAttempt.value,null);assert.equal(app.pending.value,false);
});

test('SSE 解析处理 UTF-8 跨包、CRLF、心跳和最终完成，提前断流不能冒充成功',async()=>{
  const encoder=new TextEncoder();const received=[];
  const content='event: heartbeat\r\ndata: {"time":1}\r\n\r\nevent: delta\ndata: {"text":"你好"}\n\nevent: done\ndata: {"answer":"你好","conversationId":"s1","requestId":"r1"}\n\n';
  const bytes=encoder.encode(content);let captured;
  const responseFor=text=>({ok:true,headers:{get:()=> 'text/event-stream'},body:new ReadableStream({start(controller){
    const chunks=encoder.encode(text);for(let i=0;i<chunks.length;i+=2)controller.enqueue(chunks.slice(i,i+2));controller.close();
  }})});
  const context=vm.createContext({window:{},TextDecoder,Uint8Array,fetch:async(url,options)=>{captured={url,options};return responseFor(content);}});
  context.window.AuthApi = {request:(...args)=>context.fetch(...args)};
  vm.runInContext(fs.readFileSync(path.join(root,'api.js'),'utf8'),context);
  const result=await context.window.ChatApi.streamMessage('问题','s1','r1',(name,data)=>received.push({name,data}));
  assert.equal(result.answer,'你好');assert.equal(captured.url,'/api/chat/stream');assert.equal(received[1].data.text,'你好');
  context.fetch=async()=>responseFor('event: delta\ndata: {"text":"一半"}\n\n');
  await assert.rejects(context.window.ChatApi.streamMessage('问题','s1','r1'),/提前结束/);
  context.fetch=async()=>responseFor('event: error\ndata: {"status":499,"message":"已停止"}\n\n');
  await assert.rejects(context.window.ChatApi.streamMessage('问题','s1','r1'),error=>error.interrupted===true);
});
