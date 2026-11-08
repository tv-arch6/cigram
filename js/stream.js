// مساعد البث: يمرّر الروابط التي لا يشغّلها المتصفح مباشرة عبر بروكسي البث (cigram-stream).
import { CONFIG } from './config.js';

export const proxyEnabled = () => !!CONFIG.streamProxy;

/** رابط عبر البروكسي. الرابط الأصلي يبقى كما هو (الخادم يطابقه مع كتالوج القنوات أو يوقّعه). */
export function viaProxy(url) {
  if (!CONFIG.streamProxy) return url;
  return `${CONFIG.streamProxy.replace(/\/+$/, '')}/hls?u=${encodeURIComponent(url)}`;
}

/** هل يمنع المتصفح هذا الرابط مباشرة؟ (http داخل صفحة https) */
export const mixedContent = (url) => typeof location !== 'undefined' && location.protocol === 'https:' && /^http:\/\//i.test(String(url || ''));

/** الرابط الذي يُعطى للمشغل: http داخل https يمر دائماً من البروكسي (إن كان مفعّلاً). */
export function playable(url) {
  return mixedContent(url) && proxyEnabled() ? viaProxy(url) : url;
}
