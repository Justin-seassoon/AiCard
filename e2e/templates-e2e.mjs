// templates-e2e.mjs — 6 类文书模板：浏览器逐个生成并下载，校验文件
// 用法: node templates-e2e.mjs [base_url]
import { chromium } from 'playwright';
import fs from 'node:fs';

const BASE = process.argv[2] || 'http://120.253.215.108:38081';
const OUT = '/tmp/e2e-templates';
fs.mkdirSync(OUT, { recursive: true });

const TEMPLATES = [
  { id: 'notice', label: '通知', format: 'docx' },
  { id: 'circular', label: '回覧', format: 'docx' },
  { id: 'business_report', label: '報告', format: 'docx' },
  { id: 'minutes', label: '議事録', format: 'docx' },
  { id: 'quote', label: '見積書', format: 'xlsx' },
  { id: 'invoice', label: '請求書', format: 'xlsx' },
];

const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });

let pass = 0;
let fail = 0;

try {
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle', timeout: 30000 });
  await page.click('.tab[data-tab="docgen"]');
  await page.waitForTimeout(1500);

  for (const t of TEMPLATES) {
    try {
      await page.selectOption('#tpl-select', t.id);
      await page.waitForTimeout(200);
      await page.fill('#tpl-subject', 'テスト ' + t.label);
      await page.fill('#tpl-to', '全社員');
      await page.fill('#tpl-points', 'テスト用の要点です。11/10 14:00 本社会議室。');
      if (t.format === 'xlsx') {
        await page.fill('#tpl-items', 'コピー用紙,10,500\nペン,20,100');
      }
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 30000 }),
        page.click('button:has-text("生成并下载")'),
      ]);
      const target = `${OUT}/${t.id}.${t.format}`;
      await download.saveAs(target);
      const size = fs.statSync(target).size;
      const head = fs.readFileSync(target).subarray(0, 4).toString('hex');
      const zipOk = head === '504b0304'; // PK\x03\x04
      const ok = size > 0 && zipOk;
      console.log(`${ok ? 'PASS' : 'FAIL'} ${t.id} (${t.label}) file=${download.suggestedFilename()} size=${size} zip=${zipOk}`);
      if (ok) pass++; else fail++;
    } catch (e) {
      console.log(`FAIL ${t.id} (${t.label}) -> ${e.message.split('\n')[0]}`);
      fail++;
    }
  }

  console.log(`\nRESULT pass=${pass} fail=${fail} jsErrors=${errors.length}`);
  if (errors.length) console.log(errors.join('\n'));
  process.exitCode = fail === 0 && errors.length === 0 ? 0 : 1;
} finally {
  await browser.close();
}
