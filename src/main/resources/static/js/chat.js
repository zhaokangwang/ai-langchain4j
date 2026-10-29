/* 数据库为历史来源；浏览器仅保存最近会话 ID，不缓存业务内容。 */
(() => {
  if (!window.Vue || !window.ChatApi) { document.getElementById('boot-error').hidden = false; return; }
  const { createApp, ref, nextTick, onBeforeUnmount, onMounted } = Vue;
  createApp({ setup() {
    const draft=ref(''), messages=ref([]), conversationId=ref(null), pending=ref(false), error=ref(''), failedAttempt=ref(null);
    const elapsed=ref(0), scrollArea=ref(null), inputElement=ref(null), showLatest=ref(false);
    const copiedAnswer=ref(null), copyNotice=ref(''), toolTraces=ref({});
    const streamMode=ref(true), liveText=ref(''), liveStage=ref(''), liveSteps=ref([]), stopRequested=ref(false);
    const capabilities=ref(null), capabilityLoading=ref(false), capabilityError=ref(''), capabilitiesOpen=ref(false);
    const liveAuthorization=ref(null), liveRouting=ref(null), liveModelCount=ref(0), runSummary=ref(null);
    let capabilityVersion=0, knownAccountId=null;
    const roleLabel=role=>window.ChatInsights?.roleLabel(role)||role||'身份未确认';
    const skillLabel=name=>window.ChatInsights?.skillLabel(name)||name;
    let activeController=null, activeRequestId=null;
    const conversations=ref([]), listStatus=ref('ACTIVE'), listPage=ref(0), listMore=ref(false), listLoading=ref(false), historyError=ref('');
    const historyLoading=ref(false), olderLoading=ref(false), beforeId=ref(null), hasOlder=ref(false), current=ref(null), changing=ref(false), sidebarOpen=ref(false);
    const runDetail=ref(null), detailOpen=ref(false), detailLoading=ref(false), detailError=ref(''), detailPanel=ref(null);
    let timer, copyTimer, sequence=0, followBottom=true, viewVersion=0, listVersion=0, detailVersion=0;
    const storageKey='zhixu:last-conversation';
    const examples=ref([
      {icon:'▤',title:'查一查订单',description:'了解商品状态与物流信息',message:'帮我查询订单 A1002 的状态'},
      {icon:'↺',title:'咨询售后',description:'描述问题，获取处理建议',message:'我收到的键盘有个按键坏了，想退货'},
      {icon:'⌘',title:'学习 Java',description:'用简单的例子理解开发知识',message:'请用一个简单例子解释 Spring Boot 的依赖注入'}
    ]);
    async function refreshCapabilities() {
      if(pending.value||changing.value)return;
      const version=++capabilityVersion, previous=knownAccountId;
      capabilityLoading.value=true;capabilityError.value='';
      try {
        const data=await ChatApi.capabilities();
        if(version!==capabilityVersion)return;
        if(!data.account?.id||!data.account?.role||!Array.isArray(data.skills))throw new Error('身份能力接口返回不完整，请检查后端版本。');
        capabilities.value=data;
        knownAccountId=data.account.id;
        if(previous&&previous!==data.account.id){newConversation();await refreshList();}
        const knowledge={icon:'⌘',title:'学习 Java',description:'用简单例子理解开发知识',message:'请用一个简单例子解释 Java record'};
        examples.value=data.account.role==='CUSTOMER_SERVICE'?[
          {icon:'▤',title:'检查工单材料',description:'检查已分配工单的缺失材料',message:'AS2001 还缺什么材料'},
          {icon:'↺',title:'售后审核建议',description:'根据工单事实给出建议',message:'请给 AS2001 一个审核建议'},knowledge]:data.account.role==='WAREHOUSE'?[
          {icon:'▤',title:'检查仓库库存',description:'仅查询所属仓库',message:'查询 WH01 库存'},
          {icon:'↺',title:'分析发货异常',description:'复核所属仓库订单的发货问题',message:'A1002 为什么没发货'},knowledge]:[
          {icon:'▤',title:'查询物流',description:'查询本人订单，继续追问退款',message:'A1001 的物流在哪'},
          {icon:'↺',title:'咨询售后',description:'了解退货条件和所需材料',message:'键盘坏了想退货'},knowledge];
        if(data.skills.some(s=>s.name==='order-report')) examples.value.push({icon:'▤',title:'生成订单报表',description:'查询数据库、汇总并导出 Excel',message:'生成2026年10月的订单报表，按商品汇总并导出Excel'});
      }catch(failure){if(version===capabilityVersion){capabilities.value=null;capabilityError.value=failure.message||'无法读取当前身份';examples.value=[{icon:'⌘',title:'学习 Java',description:'身份未确认，可先学习开发知识',message:'请解释 Java record'}];}}
      finally{if(version===capabilityVersion)capabilityLoading.value=false;}
    }
    const uuid=()=>window.crypto?.randomUUID?.() || `id-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    function remember(id) { try { id ? window.localStorage.setItem(storageKey,id) : window.localStorage.removeItem(storageKey); } catch {} }
    function handleScroll() { const el=scrollArea.value; if(el) {followBottom=el.scrollHeight-el.scrollTop-el.clientHeight<90;showLatest.value=!followBottom&&messages.value.length>0;} }
    async function scrollBottom(force=false) {
      await nextTick();
      if(force||followBottom) {if(scrollArea.value) scrollArea.value.scrollTop=scrollArea.value.scrollHeight;followBottom=true;showLatest.value=false;}
      else showLatest.value=messages.value.length>0;
    }
    function jumpLatest() {return scrollBottom(true);}
    async function copyAnswer(message) {
      const success=await window.ChatContent?.copyText(message.content);
      clearTimeout(copyTimer);copiedAnswer.value=success?message.id:null;
      copyNotice.value=success?'已复制原始 Markdown 回答':'复制失败，请手动选择回答内容。';
      copyTimer=setTimeout(()=>{copiedAnswer.value=null;copyNotice.value='';},2200);
    }
    async function loadTrace(requestId) {
      const version=viewVersion;
      toolTraces.value[requestId]={loading:true};
      try {
        const data=await ChatApi.run(requestId);
        if(version===viewVersion) toolTraces.value[requestId]={loaded:true,tools:data.tools,summary:window.ChatInsights?.summarize(data),models:data.models||[]};
      } catch(failure) {if(version===viewVersion) toolTraces.value[requestId]={error:failure.message};}
    }
    function toggleTrace(event,requestId) {
      if(event.target.open&&!toolTraces.value[requestId]?.loaded&&!toolTraces.value[requestId]?.loading) loadTrace(requestId);
    }
    function reportLinks(requestId) {
      return (toolTraces.value[requestId]?.tools||[]).filter(t=>t.name==='queryOrderReport'&&t.status==='COMPLETED').flatMap(t=>{
        try {const data=JSON.parse(t.resultText);return /^[a-f0-9-]{36}$/.test(data.reportId)?[{id:data.reportId,url:'/reports.html?id='+data.reportId,count:data.summary.orderCount}]:[];}catch{return [];}
      });
    }
    function statusLabel(status) { return ({RUNNING:'处理中',COMPLETED:'已完成',FAILED:'失败',INTERRUPTED:'已中断',ACTIVE:'进行中',ARCHIVED:'已归档'})[status]||status; }
    function toolLabel(name) { return ({loadSkill:'加载技能',findMyOrders:'搜索订单',getMyOrderDetails:'查询订单详情',getAccessibleRefundProgress:'查询退款进度',getAssignedAfterSalesTicket:'查询分配工单',getWarehouseStock:'查询所属仓库库存',queryOrderReport:'查询并生成订单报表'})[name]||name; }
    function time(value) { return value ? new Date(value).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}) : ''; }
    function pretty(value) { if(value==null) return '暂无记录'; try { return JSON.stringify(JSON.parse(value),null,2); } catch { return value; } }
    function fromStored(item) { return {...item,role:item.role.toLowerCase(),failed:item.status==='FAILED'||item.status==='INTERRUPTED'}; }
    async function refreshList(more=false) {
      if(more&&listLoading.value) return;
      const version=++listVersion, status=listStatus.value, page=more?listPage.value+1:0;
      listLoading.value=true; historyError.value='';
      try {
        const data=await ChatApi.listConversations(status,page);
        if(version!==listVersion) return;
        const all=more?[...conversations.value,...data.items]:data.items;
        conversations.value=[...new Map(all.map(item=>[item.id,item])).values()];
        listPage.value=page; listMore.value=data.hasMore;
      } catch(failure) { if(version===listVersion) historyError.value=failure.message; }
      finally { if(version===listVersion) listLoading.value=false; }
    }
    async function filterList(status) { listStatus.value=status; conversations.value=[]; await refreshList(); }
    async function openConversation(id, restoring=false) {
      if(pending.value||changing.value) return;
      const version=++viewVersion;
      toolTraces.value={};showLatest.value=false;liveAuthorization.value=null;liveRouting.value=null;liveModelCount.value=0;
      historyLoading.value=true; error.value=''; failedAttempt.value=null; draft.value=''; closeDetail();
      try {
        const data=await ChatApi.messages(id);
        if(version!==viewVersion) return;
        conversationId.value=id; current.value=data.conversation; messages.value=data.items.map(fromStored);
        hasOlder.value=data.hasMore; beforeId.value=data.nextBeforeId; remember(id); sidebarOpen.value=false;
        await scrollBottom(true);
      } catch(failure) {
        if(version!==viewVersion) return;
        error.value=failure.message;
        if(restoring&&failure.status===404) remember(null);
      } finally { if(version===viewVersion) historyLoading.value=false; }
    }
    async function loadOlder() {
      if(olderLoading.value||pending.value||historyLoading.value||!beforeId.value) return;
      const version=viewVersion, id=conversationId.value, oldHeight=scrollArea.value?.scrollHeight||0;
      olderLoading.value=true;
      try {
        const data=await ChatApi.messages(id,beforeId.value);
        if(version!==viewVersion) return;
        const existing=new Set(messages.value.map(item=>item.id));
        messages.value=[...data.items.filter(item=>!existing.has(item.id)).map(fromStored),...messages.value];
        hasOlder.value=data.hasMore; beforeId.value=data.nextBeforeId;
        await nextTick(); if(scrollArea.value) scrollArea.value.scrollTop+=scrollArea.value.scrollHeight-oldHeight;
      } catch(failure) { if(version===viewVersion) error.value=failure.message; }
      finally { olderLoading.value=false; }
    }
    async function submit(text,retryMessage=null) {
      if(pending.value||capabilityLoading.value||historyLoading.value||changing.value||current.value?.status==='ARCHIVED'||messages.value.some(item=>item.status==='RUNNING')||!text.trim()||text.length>4000) return;
      pending.value=true; error.value=''; failedAttempt.value=null; elapsed.value=0;
      liveText.value='';liveStage.value=streamMode.value?'正在连接实时接口':'正在处理';liveSteps.value=[];stopRequested.value=false;
      liveAuthorization.value=null;liveRouting.value=null;liveModelCount.value=0;
      const started=Date.now(); timer=setInterval(()=>{elapsed.value=Math.floor((Date.now()-started)/1000);},1000);
      const userMessage=retryMessage||{id:`local-${++sequence}`,role:'user',content:text,failed:false,requestId:uuid()};
      delete toolTraces.value[userMessage.requestId];
      if(retryMessage) messages.value=messages.value.filter(item=>!(item.requestId===userMessage.requestId&&item.incomplete));
      if(!conversationId.value) { conversationId.value=uuid(); remember(conversationId.value); }
      userMessage.failed=false; userMessage.status='RUNNING';
      if(!retryMessage) messages.value.push(userMessage);
      draft.value=''; followBottom=true; await scrollBottom(true);
      try {
        activeRequestId=userMessage.requestId;
        activeController=streamMode.value?new AbortController():null;
        const data=streamMode.value
          ? await ChatApi.streamMessage(text,conversationId.value,userMessage.requestId,onStreamEvent,activeController.signal)
          : await ChatApi.sendMessage(text,conversationId.value,userMessage.requestId);
        conversationId.value=data.conversationId; remember(data.conversationId);
        const displayed=messages.value.find(item=>item.id===userMessage.id);
        if(displayed) { displayed.status='COMPLETED'; displayed.failed=false; }
        if(!messages.value.some(item=>item.role==='assistant'&&item.requestId===data.requestId&&!item.incomplete))
          messages.value.push({id:`local-${++sequence}`,role:'assistant',content:data.answer,requestId:data.requestId||userMessage.requestId,status:'COMPLETED'});
        if(!current.value) current.value={id:data.conversationId,title:text.slice(0,40),status:'ACTIVE'};
        if(data.executedTools?.includes('queryOrderReport')) await loadTrace(data.requestId);
      } catch(failure) {
        const interrupted=stopRequested.value||failure.interrupted||failure.name==='AbortError';
        const displayed=messages.value.find(item=>item.id===userMessage.id);
        if(displayed) {displayed.failed=true; displayed.status=interrupted?'INTERRUPTED':'FAILED';}
        if(liveText.value) messages.value.push({id:`partial-${++sequence}`,role:'assistant',content:liveText.value,requestId:userMessage.requestId,status:interrupted?'INTERRUPTED':'FAILED',incomplete:true});
        draft.value=text; failedAttempt.value=interrupted?null:{text,messageId:userMessage.id};
        error.value=interrupted?'已停止生成。未完成片段不会加入对话上下文；可查看执行记录，或作为新消息重新发送。':failure.message||'发送失败，请稍后重试。';
      } finally {
        clearInterval(timer);pending.value=false;activeController=null;activeRequestId=null;liveText.value='';await scrollBottom();
        if(listStatus.value==='ACTIVE') await refreshList();
        if(window.innerWidth>650) inputElement.value?.focus({preventScroll:true});
      }
    }
    function onStreamEvent(name,data) {
      if(name==='accepted') {conversationId.value=data.conversationId;remember(data.conversationId);liveStage.value=data.cached?'正在读取已保存的回答':'请求已受理';}
      if(name==='authorization')liveAuthorization.value=data;
      if(name==='routing') {liveRouting.value=window.ChatInsights?.route(data)||data;liveStage.value=data.outcome==='DIRECT'?'后端正在直接回复':'已筛选本轮候选技能';scrollBottom();}
      if(name==='model_start') {liveModelCount.value++;liveText.value='';liveStage.value=`模型正在生成 · 第 ${data.round} 轮`;}
      if(name==='delta') {liveText.value+=data.text;scrollBottom();}
      if(name==='model_complete') {liveText.value=data.hasToolCalls?'':data.text;liveStage.value=data.hasToolCalls?'模型已选择工具':'回答生成完毕，正在保存';}
      if(name==='tool_start') {liveSteps.value.push({...data,status:'RUNNING'});liveStage.value=`正在${toolLabel(data.name)}`;}
      if(name==='tool_complete') {
        const step=liveSteps.value.find(item=>item.id===data.id);if(step)Object.assign(step,data);
        liveStage.value=data.status==='COMPLETED'?`${toolLabel(data.name)}已完成`:`${toolLabel(data.name)}未成功`;
      }
    }
    function liveInsight() {
      return {authorization:liveAuthorization.value,routing:liveRouting.value,
        selected:[...new Set([liveRouting.value?.selectedSkill,...liveSteps.value.filter(s=>s.name==='loadSkill'&&s.status==='COMPLETED').map(s=>s.skillName)].filter(Boolean))],
        modelCount:liveModelCount.value,modelLabel:liveModelCount.value?'大模型参与':liveRouting.value?.outcome==='DIRECT'?'后端直接回复（未调用模型）':'等待实际执行事件'};
    }
    async function stopGeneration() {
      if(!pending.value||!activeRequestId||stopRequested.value)return;
      stopRequested.value=true;liveStage.value='正在请求停止';
      try {await ChatApi.cancelStream(activeRequestId);}catch{}finally{activeController?.abort();}
    }
    function send() {
      const text=draft.value.trim(), failed=failedAttempt.value;
      return submit(text,failed&&failed.text===text?messages.value.find(item=>item.id===failed.messageId):null);
    }
    function retry() { if(!failedAttempt.value||pending.value) return; const attempt=failedAttempt.value; return submit(attempt.text,messages.value.find(item=>item.id===attempt.messageId)); }
    function retryStored(message) { return submit(message.content,message); }
    function onKeydown(event) { if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing&&event.keyCode!==229) {event.preventDefault();send();} }
    function newConversation() {
      if(pending.value||changing.value) return;
      ++viewVersion; historyLoading.value=false;
      toolTraces.value={};showLatest.value=false;liveAuthorization.value=null;liveRouting.value=null;liveModelCount.value=0;
      messages.value=[]; conversationId.value=null; current.value=null; draft.value=''; error.value=''; failedAttempt.value=null;
      hasOlder.value=false; beforeId.value=null; remember(null); closeDetail(); sidebarOpen.value=false; inputElement.value?.focus();
      if(listStatus.value!=='ACTIVE') filterList('ACTIVE');
    }
    async function renameConversation() {
      if(!current.value||pending.value||historyLoading.value||changing.value) return;
      const title=window.prompt('会话名称（最多 100 字）',current.value.title);
      if(title===null||!title.trim()) return;
      if(title.trim().length>100) {error.value='标题最多 100 字';return;}
      changing.value=true;
      try { current.value=await ChatApi.changeConversation(conversationId.value,{title:title.trim()}); await refreshList(); }
      catch(failure) { error.value=failure.message; } finally {changing.value=false;}
    }
    async function toggleArchive() {
      if(!current.value||pending.value||historyLoading.value||changing.value) return;
      const status=current.value.status==='ARCHIVED'?'ACTIVE':'ARCHIVED';
      if(!window.confirm(status==='ARCHIVED'?'归档当前会话？所有记录会保留，可在“已归档”中恢复。':'恢复当前会话？')) return;
      changing.value=true;
      try {current.value=await ChatApi.changeConversation(conversationId.value,{status});error.value='';failedAttempt.value=null;await refreshList();}
      catch(failure) {error.value=failure.message;} finally {changing.value=false;}
    }
    async function showRun(requestId,attempt) {
      const version=++detailVersion; detailOpen.value=true; detailLoading.value=true; detailError.value='';runSummary.value=null;
      await nextTick(); detailPanel.value?.focus();
      try {const data=await ChatApi.run(requestId,attempt); if(version===detailVersion){runDetail.value=data;runSummary.value=window.ChatInsights?.summarize(data)||null;}}
      catch(failure) {if(version===detailVersion) detailError.value=failure.message;}
      finally {if(version===detailVersion) detailLoading.value=false;}
    }
    function closeDetail() {++detailVersion;detailOpen.value=false;runDetail.value=null;runSummary.value=null;detailError.value='';detailLoading.value=false;}
    function trapDetail(event) {
      if(!detailPanel.value) return;
      const targets=[...detailPanel.value.querySelectorAll('button,input,summary')].filter(el=>el.getClientRects().length&&!el.disabled);
      if(!targets.length) return;
      const first=targets[0],last=targets[targets.length-1];
      if(event.shiftKey&&(document.activeElement===first||document.activeElement===detailPanel.value)) {event.preventDefault();last.focus();}
      else if(!event.shiftKey&&(document.activeElement===last||document.activeElement===detailPanel.value)) {event.preventDefault();first.focus();}
    }
    async function useExample(text) { if(pending.value||historyLoading.value) return; draft.value=text;await nextTick();inputElement.value?.focus(); }
    onMounted(async()=>{
      await refreshCapabilities();
      refreshList();
      try {const last=window.localStorage.getItem(storageKey); if(last&&/^[A-Za-z0-9_-]{1,64}$/.test(last)) await openConversation(last,true);} catch {}
    });
    onBeforeUnmount(()=>{activeController?.abort();clearInterval(timer);clearTimeout(copyTimer);++viewVersion;++detailVersion;++listVersion;++capabilityVersion;});
    return {draft,messages,conversationId,pending,error,failedAttempt,elapsed,scrollArea,inputElement,examples,showLatest,jumpLatest,
      copiedAnswer,copyNotice,copyAnswer,toolTraces,toggleTrace,loadTrace,reportLinks,
      streamMode,liveText,liveStage,liveSteps,stopRequested,stopGeneration,
      capabilities,capabilityLoading,capabilityError,capabilitiesOpen,refreshCapabilities,roleLabel,skillLabel,liveAuthorization,liveRouting,liveModelCount,runSummary,liveInsight,
      conversations,listStatus,listMore,listLoading,historyError,historyLoading,olderLoading,hasOlder,current,changing,sidebarOpen,
      runDetail,detailOpen,detailLoading,detailError,detailPanel,trapDetail,refreshList,filterList,openConversation,loadOlder,renameConversation,toggleArchive,
      showRun,closeDetail,statusLabel,toolLabel,time,pretty,send,retry,retryStored,onKeydown,newConversation,useExample,handleScroll};
  }}).component('execution-insight',window.ChatInsights?.component||{template:'<p>路由展示组件未加载，请刷新页面。</p>'}).directive('markdown',window.ChatContent?.markdownDirective||{mounted(el,binding){el.textContent=binding.value;}}).mount('#app');
})();
