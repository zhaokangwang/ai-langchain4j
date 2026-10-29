/* 渲染与复制独立于 Vue 状态。先过滤，再用固定 DOM 结构增强代码块。 */
(() => {
  const escape = text => String(text ?? '').replace(/[&<>"']/g, value => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[value]));
  const parser = window.marked ? new window.marked.Marked({gfm:true,breaks:true,renderer:{
    html(token) { return escape(typeof token==='string' ? token : token.text); },
    image(token) { return escape(`图片：${token.text || '未提供描述'}`); }
  }}) : null;
  const timers = new WeakMap();
  function renderMarkdown(source) {
    if(!parser || !window.DOMPurify?.isSupported) return escape(source).replace(/\n/g,'<br>');
    return window.DOMPurify.sanitize(parser.parse(String(source ?? '')), {
      ALLOWED_TAGS:['p','br','h1','h2','h3','h4','h5','h6','ul','ol','li','strong','em','del','blockquote','pre','code','a','hr','table','thead','tbody','tr','th','td'],
      ALLOWED_ATTR:['href','title','class','start','align'], ALLOW_DATA_ATTR:false, ALLOW_ARIA_ATTR:false,
      FORBID_TAGS:['style','script','iframe','object','embed','svg','math','form','input','img'],
      FORBID_ATTR:['style','id','name']
    });
  }
  async function copyText(text) {
    try { if(window.navigator?.clipboard?.writeText) {await window.navigator.clipboard.writeText(text);return true;} } catch {}
    // 非安全来源或剪贴板权限被拒绝时尝试兼容复制，不声称未成功的操作已完成。
    const previous=document.activeElement, selection=window.getSelection?.();
    const ranges=[];
    if(selection) for(let i=0;i<selection.rangeCount;i++) ranges.push(selection.getRangeAt(i).cloneRange());
    const field=document.createElement('textarea');
    field.value=text; field.setAttribute('readonly',''); field.style.cssText='position:fixed;left:-9999px;top:0';
    document.body.appendChild(field);
    let copied=false;
    try {field.select();copied=document.execCommand('copy');} catch {}
    finally {
      field.remove(); previous?.focus?.({preventScroll:true});
      if(selection) {selection.removeAllRanges();ranges.forEach(range=>selection.addRange(range));}
    }
    return copied;
  }
  function clearTimers(element) { (timers.get(element)||[]).forEach(clearTimeout);timers.delete(element); }
  function decorate(element,source) {
    clearTimers(element);
    element.innerHTML=renderMarkdown(source);
    const pending=[]; timers.set(element,pending);
    element.querySelectorAll('a').forEach(link=>{
      // 只允许网页和当前页锚点，不将模型链接导航到本机 API 或执行其他协议。
      const href=link.getAttribute('href')||'';
      if(!/^https?:\/\//i.test(href)&&!href.startsWith('#')) link.removeAttribute('href');
      else if(!href.startsWith('#')) {link.target='_blank';link.rel='noopener noreferrer';}
    });
    element.querySelectorAll('pre').forEach(pre=>{
      const code=pre.querySelector('code');if(!code)return;
      const language=(code.className.match(/(?:^|\s)language-([a-zA-Z0-9_+#.-]{1,32})(?:\s|$)/)||[])[1]||'代码';
      const wrapper=document.createElement('div');wrapper.className='code-block';
      const toolbar=document.createElement('div');toolbar.className='code-toolbar';
      const label=document.createElement('span');label.textContent=language;
      const button=document.createElement('button');button.type='button';button.textContent='复制代码';button.setAttribute('aria-label',`复制 ${language} 代码`);
      button.setAttribute('aria-live','polite');
      button.addEventListener('click',async()=>{
        button.disabled=true;const copied=await copyText(code.textContent);button.textContent=copied?'已复制':'复制失败，请手动选择';button.disabled=false;
        pending.push(setTimeout(()=>{button.textContent='复制代码';},2000));
      });
      pre.replaceWith(wrapper);toolbar.append(label,button);wrapper.append(toolbar,pre);
    });
  }
  window.ChatContent=Object.freeze({renderMarkdown,copyText,markdownDirective:{
    mounted(element,binding) {decorate(element,binding.value);},
    updated(element,binding) {if(binding.value!==binding.oldValue)decorate(element,binding.value);},
    unmounted(element) {clearTimers(element);}
  }});
})();
