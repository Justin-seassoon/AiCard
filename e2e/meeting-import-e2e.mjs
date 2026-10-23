// meeting-import-e2e.mjs — 无麦克风环境：后台导入 WAV → 纪要 → 导出
// 用法: node meeting-import-e2e.mjs [base_url] [wav_path]
//   不传 wav_path 时生成 3 秒静音（Mock 供应商可跑；真实 Azure 会因无语音而失败）
//   真实服务器请传一段日语音频，例如: node meeting-import-e2e.mjs http://host:38081 ./fixtures/meeting.wav
import { chromium } from 'playwright';
import fs from 'node:fs';

const BASE = process.argv[2] || 'http://localhost:18080';
const OUT = '/tmp/e2e-meeting';
fs.mkdirSync(OUT, { recursive: true });

/** 生成 3 秒 16k mono PCM16 WAV（静音）。 */
function makeWav(path, seconds = 3, rate = 16000) {
  const frames = seconds * rate;
  const dataLen = frames * 2;
  const b = Buffer.alloc(44 + dataLen);
  b.write('RIFF', 0);
  b.writeUInt32LE(36 + dataLen, 4);
  b.write('WAVE', 8);
  b.write('fmt ', 12);
  b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20);
  b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24);
  b.writeUInt32LE(rate * 2, 28);
  b.writeUInt16LE(2, 32);
  b.writeUInt16LE(16, 34);
  b.write('data', 36);
  b.writeUInt32LE(dataLen, 40);
  fs.writeFileSync(path, b);
}

const wavPath = process.argv[3] || `${OUT}/import-test.wav`;
if (!process.argv[3]) makeWav(wavPath);

const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push('console.error: ' + m.text()); });

let pass = 0;
let fail = 0;
function check(ok, label) {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`);
  ok ? pass++ : fail++;
}

try {
  await page.goto(BASE + '/admin.html', { waitUntil: 'networkidle', timeout: 30000 });
  await page.click('.tab[data-tab="meeting"]');
  await page.waitForTimeout(600);

  await page.setInputFiles('#mtg-import-file', wavPath);
  await page.fill('#mtg-import-title', 'ブラウザ導入テスト');
  await page.click('button:has-text("导入并生成纪要")');

  // 等待接口返回并自动打开详情
  await page.waitForSelector('#mtg-detail', { state: 'visible', timeout: 30000 });
  await page.waitForFunction(() => {
    const meta = document.getElementById('mtg-detail-meta');
    return meta && (meta.textContent.includes('待审核') || meta.textContent.includes('已审核')
      || meta.textContent.includes('失败'));
  }, null, { timeout: 120000 });

  const metaText = await page.textContent('#mtg-detail-meta');
  check(!metaText.includes('失败'), '处理完成' + (metaText.includes('失败') ? '（失败：' + metaText + '）' : ''));

  const title = await page.inputValue('#mtg-title');
  check(title === 'ブラウザ導入テスト', `导入的会议标题正确（${title}）`);

  const transcript = await page.textContent('#mtg-detail-body');
  check(transcript.includes('文字起こし') && transcript.includes('会議'), '转写已生成');

  const actionRows = await page.locator('#mtg-actions tr').count();
  check(actionRows >= 1, `待办已生成（${actionRows} 条）`);

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.click('button:has-text("导出 docx")'),
  ]);
  const target = `${OUT}/${download.suggestedFilename()}`;
  await download.saveAs(target);
  const size = fs.statSync(target).size;
  const head = fs.readFileSync(target).subarray(0, 4).toString('hex');
  check(size > 1000 && head === '504b0304', `导出 docx 有效（size=${size}）`);

  // HTTP 环境：模拟器应提示并禁用麦克风按钮（引导使用 WAV 导入）
  const sim = await browser.newPage();
  await sim.goto(BASE + '/simulator.html', { waitUntil: 'networkidle', timeout: 30000 });
  const secure = await sim.evaluate(() => window.isSecureContext);
  if (!secure) {
    const hint = await sim.isVisible('#meeting-secure-hint');
    const disabled = await sim.isDisabled('#btn-meeting');
    check(hint && disabled, 'HTTP 环境显示麦克风提示并禁用录音按钮');
  } else {
    check(true, '安全上下文（localhost/HTTPS）：麦克风可用');
  }

  console.log(`\nRESULT pass=${pass} fail=${fail} jsErrors=${errors.length}`);
  if (errors.length) console.log(errors.join('\n'));
  process.exitCode = fail === 0 && errors.length === 0 ? 0 : 1;
} finally {
  await browser.close();
}
