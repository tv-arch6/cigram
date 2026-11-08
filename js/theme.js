// الوضع الفاتح والداكن مثل التطبيق (v10.70): يتبع إعداد الجهاز، إلا إذا فرض تطبيق الإدارة وضعاً واحداً
// (/home-layout → theme.mode). الألوان نفسها: الصفحة #F4F5F7 / #0E0E10، البطاقات #FFFFFF / #1B1B1F، الأحمر #E21B14.
// السطر الأول في index.html يضبط الوضع قبل أول رسم (بدون وميض)، وهذا الملف يتابع التغييرات بعد ذلك.
import * as layout from './layout.js';

const root = document.documentElement;
const media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
const PAGE = { light: '#F4F5F7', dark: '#0E0E10' };

export function resolved() {
  const mode = layout.themeMode();
  if (mode === 'light' || mode === 'dark') return mode;
  return media && media.matches ? 'light' : 'dark';
}

/** الصفحة الآن فاتحة؟ */
export const isLight = () => root.dataset.theme === 'light';

export function apply(animate = false) {
  const want = resolved();
  if (root.dataset.theme === want) return;
  if (animate) {
    // انتقال ناعم بين الوضعين (مثل تلاشي التطبيق عند تغيير الوضع)
    root.classList.add('theme-anim');
    setTimeout(() => root.classList.remove('theme-anim'), 420);
  }
  root.dataset.theme = want;
  try { localStorage.setItem('cigram_theme_mode', layout.themeMode()); } catch { /* ignore */ }
  paintMeta();
}

export function paintMeta() {
  const want = root.dataset.theme === 'light' ? 'light' : 'dark';
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta && !root.classList.contains('landing-on')) {
    meta.setAttribute('content', document.body && document.body.classList.contains('watching') ? '#000000' : PAGE[want]);
  }
  const scheme = document.querySelector('meta[name="color-scheme"]');
  if (scheme) scheme.setAttribute('content', want);
}

if (media) {
  const on = () => apply(true);
  if (media.addEventListener) media.addEventListener('change', on); else if (media.addListener) media.addListener(on);
}
layout.onChange(() => apply(true));
apply(false);
paintMeta();
