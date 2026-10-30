const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const id='00000000-0000-4000-8000-000000000001';
const filter={startDate:'2026-10-01',endDate:'2026-10-31',status:'',productKeyword:'',groupBy:'PRODUCT'};
const page=(number=0)=>({reportId:id,filter:{...filter},rows:[],summary:{orderCount:3,quantity:4,totalAmount:676},groups:[],page:number,size:10,hasMore:false});
function harness(fetcher,url='http://localhost/reports.html'){
  let state,mount;
  const window={AuthApi:{request:fetcher},location:{href:url},history:{replaceState(a,b,value){window.location.href=String(value);}}};
  const Vue={ref:value=>({value}),computed:fn=>({get value(){return fn();}}),onMounted:fn=>{mount=fn;},createApp:options=>({mount(){state=options.setup();}})};
  window.Vue=Vue;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../src/main/resources/static/js/reports.js'),'utf8'),{window,Vue,URL,fetch:fetcher,document:{getElementById:()=>({})},setTimeout});
  return {...state,initialize:()=>mount()};
}
const ok=value=>({ok:true,json:async()=>value});
test('查询保存快照链接，修改条件不会悄悄改变已生成报表',async()=>{
  const calls=[];
  const app=harness(async(url,options)=>{calls.push([url,options]);return ok(page());});
  await app.generate();
  assert.equal(app.report.value.reportId,id);assert.equal(app.dirty.value,false);
  app.filter.value.status='DELIVERED';assert.equal(app.dirty.value,true);
  await app.loadPage(1);
  assert.match(calls[1][0],new RegExp('/'+id+'\\?page=1&size=10$'));
  assert.equal(JSON.parse(calls[0][1].body).status,'');
  assert.equal(app.report.value.summary.totalAmount,676);
});
test('重新生成失败清空旧结果并展示服务端错误',async()=>{
  let fail=false;
  const app=harness(async()=>fail?{ok:false,status:400,json:async()=>({message:'日期范围应为1–366天'})}:ok(page()));
  await app.generate();fail=true;await app.generate();
  assert.equal(app.report.value,null);assert.match(app.error.value,/366/);assert.equal(app.busy.value,false);
});
test('从真实报表链接恢复快照条件，身份接口不调用聊天',async()=>{
  const calls=[];
  const app=harness(async url=>{calls.push(url);return ok(url.includes('capabilities')?{account:{id:'demo-user',scope:'本人订单'}}:page());},'http://localhost/reports.html?id='+id);
  await app.initialize();
  assert.equal(app.account.value.id,'demo-user');assert.equal(app.report.value.reportId,id);
  assert.equal(app.filter.value.startDate,'2026-10-01');assert.equal(app.dirty.value,false);
  assert.equal(calls.length,2);assert.ok(calls.every(url=>!url.includes('/api/chat')));
});
test('分页失败保留快照，下载越权显示错误并恢复按钮',async()=>{
  let fail=false;
  const app=harness(async()=>fail?{ok:false,status:404,json:async()=>({message:'未找到可访问的报表'})}:ok(page()));
  await app.generate();fail=true;app.pageSize.value=20;await app.loadPage(1);
  assert.equal(app.report.value.reportId,id);assert.equal(app.pageSize.value,10);
  await app.download();assert.match(app.error.value,/未找到/);assert.equal(app.downloading.value,false);
});
