/* 同源请求：浏览器不接触模型供应商或 API Key。 */
async function historyRequest(url, options) {
  let response;
  try { response = await window.AuthApi.request(url, options); }
  catch { throw new Error('无法连接后端，请检查服务是否运行。'); }
  if (!response.ok) {
    const error = new Error(({400:'操作参数无效，请检查后重试。',401:'登录已过期，请重新登录。',403:'无权操作或请求令牌已过期，请刷新后重试。',404:'记录不存在或不可访问。',409:'会话正在处理或已归档，请稍后重试。'})[response.status] || `读取记录失败（HTTP ${response.status}）`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}
window.ChatApi = Object.freeze({
  capabilities() {return historyRequest('/api/demo/capabilities');},
  async streamMessage(message, conversationId, requestId, onEvent, signal) {
    let response;
    try {
      response=await window.AuthApi.request('/api/chat/stream',{method:'POST',headers:{'Content-Type':'application/json',Accept:'text/event-stream'},
        body:JSON.stringify({message,conversationId,requestId}),signal});
    } catch(error) {
      if(error.name==='AbortError') throw error;
      throw new Error('无法连接实时接口，请检查后端服务。');
    }
    if(!response.ok) throw new Error(`实时请求失败（HTTP ${response.status}），请查看后端状态，或切换普通模式。`);
    if(!response.headers.get('content-type')?.includes('text/event-stream')||!response.body) throw new Error('后端未返回 SSE 事件流。');
    const reader=response.body.getReader(), decoder=new TextDecoder('utf-8');
    let buffer='',result=null;
    const dispatch=frame=>{
      let name='message';const lines=[];
      for(const line of frame.split(/\r?\n/)) {
        if(line.startsWith('event:')) name=line.slice(6).trim();
        if(line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /,''));
      }
      if(!lines.length)return;
      let data;try {data=JSON.parse(lines.join('\n'));}catch{throw new Error('实时事件格式错误，请检查服务版本。');}
      if(name==='error') {const error=new Error(data.message||'实时生成失败');error.interrupted=data.status===499;error.status=data.status;throw error;}
      if(name==='done') {
        if(typeof data.answer!=='string'||!data.answer.trim()||typeof data.conversationId!=='string') throw new Error('最终回答不完整。');
        result=data;
      }
      onEvent?.(name,data);
    };
    try {
      while(!result) {
        const chunk=await reader.read();buffer+=decoder.decode(chunk.value||new Uint8Array(),{stream:!chunk.done});
        let boundary;
        while((boundary=buffer.match(/\r?\n\r?\n/))) {
          const frame=buffer.slice(0,boundary.index);buffer=buffer.slice(boundary.index+boundary[0].length);dispatch(frame);
          if(result)break;
        }
        if(buffer.length>1048576)throw new Error('实时事件过大，已停止读取。');
        if(chunk.done)break;
      }
      if(!result)throw new Error('实时连接提前结束，最终结果未确认。请查看历史记录后再决定是否重试。');
      return result;
    } finally {await reader.cancel().catch(()=>{});reader.releaseLock();}
  },
  cancelStream(requestId) {
    return historyRequest(`/api/chat/stream/${encodeURIComponent(requestId)}/cancel`,{method:'POST'});
  },
  listConversations(status = 'ACTIVE', page = 0) {
    return historyRequest(`/api/conversations?status=${encodeURIComponent(status)}&page=${page}&size=20`);
  },
  messages(id, beforeId) {
    return historyRequest(`/api/conversations/${encodeURIComponent(id)}/messages?size=30${beforeId ? `&beforeId=${beforeId}` : ''}`);
  },
  changeConversation(id, changes) {
    return historyRequest(`/api/conversations/${encodeURIComponent(id)}`, {method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify(changes)});
  },
  run(id, attempt) {
    return historyRequest(`/api/chat-runs/${encodeURIComponent(id)}${attempt ? `?attempt=${attempt}` : ''}`);
  },
  async sendMessage(message, conversationId, requestId) {
    const body = { message };
    if (conversationId) body.conversationId = conversationId;
    if (requestId) body.requestId = requestId;
    let response;
    try {
      response = await window.AuthApi.request('/api/chat', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(body)
      });
    } catch {
      throw new Error('无法连接后端，请检查服务是否运行及网络连接，然后重试。');
    }
    if (!response.ok) {
      const explanations = {
        401: '登录已过期，请重新登录。',
        403: '无权操作或请求令牌已过期，请刷新后重试。',
        400: '输入未通过校验，请检查问题长度或新建会话后重试。',
        409: '请求仍在处理、已中断或请求标识冲突。请稍后重试；若后端已重启，请确认后新建会话再发送。',
        429: '当前演示会话已满，请稍后再试。',
        502: '模型处理未完成或工具调用达到上限，请调整问题后重试。',
        503: '服务暂不可用，请稍后重试。',
        504: '服务等待超时，请稍后重试。'
      };
      throw new Error(explanations[response.status] || `请求失败（HTTP ${response.status}），请检查后端控制台日志。`);
    }
    let data;
    try { data = await response.json(); } catch { throw new Error('后端返回的内容不是有效 JSON，请检查服务日志。'); }
    if (typeof data.answer !== 'string' || !data.answer.trim() ||
        typeof data.conversationId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(data.conversationId)) {
      throw new Error('后端响应缺少回答或有效会话 ID，请检查服务版本。');
    }
    return data;
  }
});
