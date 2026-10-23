// meeting-e2e.mjs — 会议纪要 P0 端到端：模拟器录音 → 后台列表/详情/导出
// 用法: node meeting-e2e.mjs [base_url]
import { chromium } from 'playwright';
import fs from 'node:fs';

const BASE = process.argv[2] || 'http://localhost:18080';
const OUT = '/tmp/e2e-meeting';
fs.mkdirSync(OUT, { recursive: true });

const errors = [];
const browser = await chromium.launch({
  args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
});
const context = await browser.newContext({ permissions: ['microphone'] });
const page = await context.newPage();
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });

let pass = 0;
let fail = 0;
function check(ok, label) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`);
  ok ? pass++ : fail++;
}

try {
  // 1) 模拟器：开始会议录音
  await page.goto(BASE + '/simulator.html', { waitUntil: 'networkidle', timeout: 30000 });
  await page.click('#btn-meeting');
  await page.waitForFunction(() => {
    const s = document.querySelector('#meeting-status');
    return s && s.textContent.includes('录音中');
  }, null, { timeout: 15000 });
  check(true, '模拟器开始会议录音');

  // 录 3 秒（会形成一个不足 30 秒的分片）
  await page.waitForTimeout(3000);

  // 2) 结束 → 等待上传并结束
  await page.click('#btn-meeting');
  await page.waitForFunction(() => {
    const s = document.querySelector('#meeting-status');
    return s && (s.textContent.includes('已结束') || s.textContent.includes('结束失败'));
  }, null, { timeout: 30000 });
  const endStatus = await page.textContent('#meeting-status');
  check(endStatus.includes('已结束'), '模拟器结束并提交处理：' + endStatus);

  // 3) 后台：会议纪要页签
  const admin = await context.newPage();
  admin.on('pageerror', (e) => errors.push('admin pageerror: ' + e.message));
  await admin.goto(BASE + '/admin.html', { waitUntil: 'networkidle', timeout: 30000 });
  await admin.click('.tab[data-tab="meeting"]');
  await admin.waitForFunction(() => document.querySelectorAll('#mtg-tbody tr').length > 0, null, { timeout: 30000 });
  const rows = await admin.locator('#mtg-tbody tr').count();
  check(rows > 0, `后台会议列表有数据（${rows} 行）`);

  // 4) 打开最新一条详情
  await admin.locator('#mtg-tbody tr').first().locator('button:has-text("详情")').click();
  await admin.waitForSelector('#mtg-detail', { state: 'visible', timeout: 15000 });
  await admin.waitForFunction(() => {
    const meta = document.getElementById('mtg-detail-meta');
    return meta && (meta.textContent.includes('待审核') || meta.textContent.includes('已审核'));
  }, null, { timeout: 60000 });
  await admin.waitForFunction(() => {
    const el = document.getElementById('mtg-detail-body');
    return el && (el.textContent.includes('【決定事項】') || el.textContent.includes('文字起こし'));
  }, null, { timeout: 60000 });
  const detailText = await admin.textContent('#mtg-detail-body');
  check(detailText.includes('文字起こし'), '详情显示转写');
  check(detailText.includes('アクション'), '详情显示待办');

  // 5) 导出 docx
  const [download] = await Promise.all([
    admin.waitForEvent('download', { timeout: 30000 }),
    admin.click('button:has-text("导出 docx")'),
  ]);
  const target = `${OUT}/${download.suggestedFilename()}`;
  await download.saveAs(target);
  const size = fs.statSync(target).size;
  const head = fs.readFileSync(target).subarray(0, 4).toString('hex');
  check(size > 1000 && head === '504b0304', `导出 docx 有效（${download.suggestedFilename()} size=${size} zip=${head === '504b0304'}）`);

  console.log(`\nRESULT pass=${pass} fail=${fail} jsErrors=${errors.length}`);
  if (errors.length) console.log(errors.join('\n'));
  process.exitCode = fail === 0 && errors.length === 0 ? 0 : 1;
} finally {
  await browser.close();
}
