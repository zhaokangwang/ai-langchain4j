(() => {
  if (!window.Vue) { document.getElementById('report-boot-error').hidden=false;return; }
  const {createApp,ref,computed,onMounted}=Vue;
  createApp({setup(){
    const defaults=()=>({startDate:'2026-10-01',endDate:'2026-10-31',status:'',productKeyword:'',groupBy:'PRODUCT'});
    const filter=ref(defaults()),report=ref(null),account=ref(null),busy=ref(false),downloading=ref(false),error=ref(''),pageSize=ref(10);
    const dirty=computed(()=>report.value && JSON.stringify(filter.value)!==JSON.stringify(report.value.filter));
    const statusLabel=value=>({DELIVERED:'已签收',PENDING_SHIPMENT:'待发货',SHIPPED:'已发货',CANCELLED:'已取消','':'全部状态'})[value]||value;
    const dimension=value=>({PRODUCT:'商品',STATUS:'订单状态',DAY:'下单日期'})[value]||value;
    const groupLabel=value=>'按'+dimension(value);
    const money=value=>Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
    const time=value=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
    async function request(url,options){
      const response=await window.AuthApi.request(url,options);
      if(!response.ok){let message='请求失败（'+response.status+'）';try{const data=await response.json();message=data.detail||data.message||message;}catch{}throw new Error(message);}
      return response;
    }
    function remember(id){const url=new URL(window.location.href);url.searchParams.set('id',id);window.history.replaceState(null,'',url);}
    async function generate(){
      if(busy.value)return;
      busy.value=true;error.value='';report.value=null;
      const url=new URL(window.location.href);url.searchParams.delete('id');window.history.replaceState(null,'',url);
      try{
        report.value=await (await request('/api/reports/orders',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(filter.value)})).json();
        pageSize.value=10;filter.value={...report.value.filter};remember(report.value.reportId);
      }catch(failure){error.value=failure.message;}finally{busy.value=false;}
    }
    async function loadPage(page,id=report.value?.reportId){
      if(busy.value||!id)return;
      busy.value=true;error.value='';
      try{report.value=await (await request('/api/reports/orders/'+encodeURIComponent(id)+'?page='+page+'&size='+pageSize.value)).json();}
      catch(failure){error.value=failure.message;if(report.value)pageSize.value=report.value.size;}finally{busy.value=false;}
    }
    function changeSize(){return loadPage(0);}
    function reset(){filter.value=defaults();}
    async function download(){
      if(downloading.value||!report.value)return;
      downloading.value=true;error.value='';
      try{
        const response=await request('/api/reports/orders/'+encodeURIComponent(report.value.reportId)+'/download');
        const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');
        link.href=url;link.download='orders-'+report.value.reportId+'.xlsx';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
      }catch(failure){error.value=failure.message;}finally{downloading.value=false;}
    }
    onMounted(async()=>{
      try{account.value=(await (await request('/api/demo/capabilities')).json()).account;}catch{error.value='无法读取当前身份，请检查后端服务。';}
      const id=new URL(window.location.href).searchParams.get('id');
      if(id){if(!/^[a-f0-9-]{36}$/.test(id)){error.value='报表编号格式无效';return;}await loadPage(0,id);if(report.value)filter.value={...report.value.filter};}
    });
    return{filter,report,account,busy,downloading,error,pageSize,dirty,statusLabel,dimension,groupLabel,money,time,generate,loadPage,changeSize,reset,download};
  }}).mount('#reports');
})();
