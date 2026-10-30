/* 只解析后端授权/路由事实；不从回答文字猜测执行过程。 */
(() => {
  const roles={BUYER:'买家',CUSTOMER_SERVICE:'客服',WAREHOUSE:'仓库人员'};
  const skills={'order-report':'订单报表','order-query':'订单查询','logistics-tracking':'物流查询','delivery-exception-guide':'物流异常建议',
    'return-policy-guide':'退货政策咨询','after-sales-review':'售后工单审核','after-sales-material-check':'工单材料检查',
    'refund-progress-query':'退款进度','warehouse-stock-check':'仓库库存','shipment-exception-review':'发货异常分析'};
  const categories={report:'报表',order:'订单',logistics:'物流',refund:'退款',warehouse:'仓储','after-sales':'售后'};
  const strings=value=>Array.isArray(value)?value.filter(v=>typeof v==='string'):[];
  function parse(value){try{const data=typeof value==='string'?JSON.parse(value):value;return data&&typeof data==='object'&&!Array.isArray(data)?data:null;}catch{return null;}}
  function route(data){
    if(!data||typeof data!=='object')return null;
    const state=data.state||{};
    const direct=data.outcome==='DIRECT';
    return {reason:typeof data.reason==='string'?data.reason:'原因未记录',candidates:strings(data.candidates),
      category:direct?'':categories[state.category]||state.category||'',orderNumber:direct?'':state.orderNumber||data.orderNumber||'',
      ticketNumber:direct?'':state.ticketNumber||data.ticketNumber||'',selectedSkill:direct?'':state.selectedSkill||'',outcome:data.outcome||'',
      choices:(!direct||data.reason==='指代存在多个候选')&&Array.isArray(state.choices)?state.choices:[]};
  }
  function summarize(run){
    // 旧事件没有尝试号，无法可靠归属，不能混入所选尝试的摘要。
    const events=(run.events||[]).filter(e=>e.attemptNo===run.selectedAttempt);
    const auth=events.filter(e=>e.type==='AUTHORIZATION').map(e=>parse(e.details)).filter(Boolean).at(-1)||null;
    const routing=route(events.filter(e=>e.type==='ROUTING').map(e=>parse(e.details)).filter(Boolean).at(-1));
    const selected=[...new Set([routing?.outcome!=='DIRECT'?routing?.selectedSkill:null,...(run.tools||[]).filter(t=>t.name==='loadSkill'&&t.status==='COMPLETED').map(t=>t.skillName)].filter(Boolean))];
    const models=run.models||[];
    return {authorization:auth,routing,selected,modelCount:models.length,
      modelLabel:models.length?'大模型参与':routing?.outcome==='DIRECT'?'后端直接回复（未调用模型）':'暂无模型调用记录',
      missing:!auth&&!routing};
  }
  const component={props:['info'],methods:{roleLabel:role=>roles[role]||role||'身份未确认',skillLabel:name=>skills[name]||name},template:`
    <section v-if="info" class="execution-insight" aria-label="身份与业务路由">
      <p class="insight-mode">{{ info.modelLabel }}<span v-if="info.modelCount"> · {{ info.modelCount }} 轮</span></p>
      <p v-if="info.authorization?.account"><strong>本次身份</strong> {{ roleLabel(info.authorization.account.role) }} · {{ info.authorization.account.id }}<br><span class="insight-muted">数据范围：{{ info.authorization.account.scope }}</span></p>
      <p v-if="info.authorization?.actor"><strong>发起账号</strong> {{ info.authorization.actor.username }}<span class="insight-muted"> · {{ info.authorization.actor.id }}</span></p>
      <template v-if="info.routing"><p><strong>业务判断</strong> {{ info.routing.category ? info.routing.category+' · ' : '' }}{{ info.routing.reason }}</p>
        <p v-if="info.routing.orderNumber || info.routing.ticketNumber"><strong>已确认对象</strong> {{ info.routing.orderNumber }} {{ info.routing.ticketNumber }}</p>
        <p v-if="info.routing.choices?.length"><strong>待选择订单</strong> <span v-for="choice in info.routing.choices" :key="choice.orderNumber">{{ choice.orderNumber }}（{{ choice.product }}） </span></p>
        <div><strong>本轮候选</strong><span v-for="name in info.routing.candidates" :key="name" class="skill-chip" :title="name">{{ skillLabel(name) }}</span><span v-if="!info.routing.candidates.length" class="insight-muted">未提供业务技能</span></div>
      </template>
      <p v-if="info.selected?.length"><strong>加载 / 复用记录</strong> <span v-for="name in info.selected" :key="name" class="skill-chip" :title="name">{{ skillLabel(name) }}</span></p>
      <p v-if="info.missing" class="insight-muted">当前记录缺少可归属到本次尝试的身份或路由信息，不推测补全。</p>
    </section>`};
  window.ChatInsights=Object.freeze({component,roleLabel:role=>roles[role]||role||'身份未确认',skillLabel:name=>skills[name]||name,
    categoryLabel:name=>categories[name]||name,route,summarize});
})();
