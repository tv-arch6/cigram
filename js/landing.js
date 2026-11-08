// صفحة التحميل: زر «تحميل التطبيق» يفتح CONFIG.downloadUrl، و«تصفح موقع التطبيق» يدخل الموقع.
import { CONFIG } from './config.js';
import { paintMeta } from './theme.js';

const root = document.documentElement;
const meta = document.querySelector('meta[name="theme-color"]');

const dl = document.getElementById('landingDownload');
if (dl && CONFIG.downloadUrl) dl.setAttribute('href', CONFIG.downloadUrl);

document.getElementById('landingBrowse')?.addEventListener('click', () => {
  try { sessionStorage.setItem('cigram_landing_seen', '1'); } catch { /* ignore */ }
  root.classList.remove('landing-on');
  paintMeta();
  window.scrollTo(0, 0);
  document.getElementById('view')?.focus({ preventScroll: true });
});
