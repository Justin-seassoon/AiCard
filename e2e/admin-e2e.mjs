// admin-e2e.mjs — 后台控制台页签 E2E（Playwright / Chromium）
// 用法: node admin-e2e.mjs [base_url]
import { chromium } from 'playwright';

const BASE = process.argv[2] || 'http://120.253.215.108:38081';

const TABS = [
  { id: 'report', heading: '手动生成报表' },
  { id: 'notification', heading: '新建运营通知' },
  { id: 'order', heading: '新建维修单' },
  { id: 'inspection', heading: '新建点检任务' },
  { id: 'gap', heading: '知识缺口' },
  { id: 'docgen', heading: '社内文書 下書き作成' },
  { id: 'agent', heading: '多步任务执行' },
];

const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => {
  if (m.type() === 'error') errors.push('console.error: ' + m.text());
});
page.on('requestfailed', (r) => errors.push('requestfailed: ' + r.url()));

try {
  const resp = await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle', timeout: 30000 });
  console.log(`page.status=${resp.status()}`);

  let pass = 0;
  let fail = 0;

  for (const t of TABS) {
    await page.click(`.tab[data-tab="${t.id}"]`);
    await page.waitForTimeout(400);

    const activePanels = await page.locator('.panel.active').count();
    const panel = page.locator(`#panel-${t.id}`);
    const visible = await panel.isVisible();
    const text = visible ? await panel.innerText() : '';
    const headingOk = text.includes(t.heading);
    const ok = visible && activePanels === 1 && headingOk;

    await page.screenshot({ path: `/tmp/e2e-${t.id}.png`, fullPage: false });
    console.log(`${ok ? 'PASS' : 'FAIL'} tab=${t.id} visible=${visible} activePanels=${activePanels} heading=${headingOk}`);
    if (ok) pass++; else fail++;
  }

  // 数据页签：等待加载后确认「有行 或 显示空态」
  await page.click('.tab[data-tab="gap"]');
  await page.waitForTimeout(1200);
  const gapRows = await page.locator('#gap-tbody tr').count();
  const gapEmpty = await page.locator('#gap-empty').isVisible();
  console.log(`gap.data rows=${gapRows} emptyVisible=${gapEmpty} (${gapRows > 0 || gapEmpty ? 'PASS' : 'FAIL'})`);

  await page.click('.tab[data-tab="agent"]');
  await page.waitForTimeout(1200);
  const agentRows = await page.locator('#ag-tbody tr').count();
  const agentEmpty = await page.locator('#ag-empty').isVisible();
  console.log(`agent.data rows=${agentRows} emptyVisible=${agentEmpty} (${agentRows > 0 || agentEmpty ? 'PASS' : 'FAIL'})`);

  console.log(`\nRESULT pass=${pass} fail=${fail} jsErrors=${errors.length}`);
  if (errors.length) console.log(errors.join('\n'));
  process.exitCode = fail === 0 && errors.length === 0 ? 0 : 1;
} finally {
  await browser.close();
}
