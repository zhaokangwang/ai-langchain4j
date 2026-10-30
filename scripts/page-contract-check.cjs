const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../src/main/resources/static');
const pages = {'index.html':'app', 'reports.html':'reports', 'login.html':'auth', 'account.html':'auth', 'admin-accounts.html':'account-admin'};

function template(html, id) {
  const opening = new RegExp('<([a-z][a-z0-9-]*)\\b[^>]*\\bid="' + id + '"[^>]*>', 'i').exec(html);
  assert.ok(opening, '页面缺少应用根节点：' + id);
  const tags = new RegExp('<\\/?' + opening[1] + '\\b[^>]*>', 'gi');
  const begin = opening.index + opening[0].length;
  tags.lastIndex = begin;
  let depth = 1, tag;
  while ((tag = tags.exec(html))) {
    depth += tag[0].startsWith('</') ? -1 : 1;
    if (!depth) return html.slice(begin, tag.index);
  }
  assert.fail('应用根节点未闭合：' + id);
}

test('五个页面使用实际本地 Vue 编译器编译，无模板错误', () => {
  const context = vm.createContext({console});
  vm.runInContext(fs.readFileSync(path.join(root, 'vendor/vue.global.prod.js'), 'utf8'), context);
  for (const [file, id] of Object.entries(pages)) {
    const errors = [];
    const render = context.Vue.compile(template(fs.readFileSync(path.join(root, file), 'utf8'), id), {
      decodeEntities:value=>value, onError:error=>errors.push(error.code || error.message)
    });
    assert.deepEqual(errors, [], file + ' 存在模板编译错误');
    assert.equal(typeof render, 'function');
  }
});

test('HTML 原生表单 pattern 能按浏览器 Unicode Sets 规则编译并约束账号格式', () => {
  let patterns = 0;
  for (const file of Object.keys(pages)) {
    const html = fs.readFileSync(path.join(root, file), 'utf8');
    for (const match of html.matchAll(/\bpattern="([^"]+)"/g)) {
      patterns++;
      const pattern = new RegExp('^(?:' + match[1] + ')$', 'v');
      assert.equal(pattern.test('buyer-01'), true, file);
      assert.equal(pattern.test('service_01'), true, file);
      assert.equal(pattern.test('bad name'), false, file);
      assert.equal(pattern.test('-bad'), false, file);
    }
  }
  assert.ok(patterns > 0, '没有找到需要验证的账号表单约束');
});
