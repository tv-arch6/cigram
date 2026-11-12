// أيقونات صفحة القنوات وشعار القناة (مثل CigramHubUi في التطبيق).
import { esc } from './util.js';

const S = 'viewBox="0 0 24 24" aria-hidden="true"';
const line = 'fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';

export const CH_ICONS = {
  tv: `<svg ${S} class="stroke"><rect x="2.8" y="4.2" width="18.4" height="12.6" rx="2"/><path d="M8.5 20.2h7M12 16.8v3.4"/><path d="M10.4 8.1v5.2l4.3-2.6z" fill="currentColor" stroke-width="1.2"/></svg>`,
  nav: `<svg ${S} class="stroke"><rect x="3" y="4.5" width="18" height="12.5" rx="2"/><path d="M8.5 20h7M12 17v3"/><path d="m10.5 8.3 4 2.45-4 2.45z"/></svg>`,
  heart: `<svg ${S} class="stroke"><path d="M12 19.6s-7.1-4.3-7.1-9.6a4 4 0 0 1 7.1-2.5A4 4 0 0 1 19.1 10c0 5.3-7.1 9.6-7.1 9.6z"/></svg>`,
  heartFill: `<svg ${S}><path d="M12 20.3S4 15.6 4 9.9a4.6 4.6 0 0 1 8-3.1 4.6 4.6 0 0 1 8 3.1c0 5.7-8 10.4-8 10.4z"/></svg>`,
  clock: `<svg ${S} class="stroke"><circle cx="12" cy="12" r="8"/><path d="M12 7.8v4.5l3 1.9"/></svg>`,
  grid: `<svg ${S} class="stroke"><rect x="4" y="4" width="6.5" height="6.5" rx="1.6"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.6"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.6"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.6"/></svg>`,
  chevL: `<svg ${S} class="stroke"><path d="m14.5 6-6 6 6 6"/></svg>`,
  search: `<svg ${S} class="stroke"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>`,
  close: `<svg ${S} class="stroke"><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/></svg>`,
  play: `<svg ${S}><path d="M8 5.5v13l11-6.5z"/></svg>`,
  pause: `<svg ${S}><rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/></svg>`,
  // «التالي» يتجه لليسار و«السابق» لليمين (اتجاه القراءة العربية، كما في التطبيق)
  next: `<svg ${S} class="stroke"><path d="M17 5.8 10.8 12l6.2 6.2M6.6 5.8v12.4"/></svg>`,
  prev: `<svg ${S} class="stroke"><path d="M7 5.8 13.2 12 7 18.2M17.4 5.8v12.4"/></svg>`,
  speaker: `<svg ${S}><path d="M4 9.3h3.4L12 5.6v12.8l-4.6-3.7H4z"/><path d="M15.4 9a4.2 4.2 0 0 1 0 6M17.9 6.5a7.8 7.8 0 0 1 0 11" ${line}/></svg>`,
  mute: `<svg ${S}><path d="M4 9.3h3.4L12 5.6v12.8l-4.6-3.7H4z"/><path d="m15.5 9.5 5 5M20.5 9.5l-5 5" ${line}/></svg>`,
  expand: `<svg ${S} class="stroke"><path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"/></svg>`,
  shrink: `<svg ${S} class="stroke"><path d="M8 4v4H4M16 4v4h4M8 20v-4H4M16 20v-4h4"/></svg>`,
};

/** أول حرف من الاسم (يظهر إن لم يكن للقناة شعار أو تعذر تحميله). */
const firstLetter = (name) => {
  const t = String(name ?? '').trim();
  return t ? Array.from(t)[0].toUpperCase() : '';
};

/**
 * شعار القناة داخل مربع (sq) أو دائرة (circle). الصورة تُصغَّر داخل المربع مثل التطبيق،
 * والحرف الأول تحتها. إن تعذر تحميل الصورة يبقى الحرف (js/app.js يجرب مضيف R2 الآخر أولاً).
 */
export function logoHtml(src, name, shape = 'sq', extra = '') {
  return `<span class="lg ${shape} ${extra}"><b aria-hidden="true">${esc(firstLetter(name))}</b>${src ? `<img src="${esc(src)}" alt="" loading="lazy" decoding="async">` : ''}</span>`;
}

/** أيقونة ثابتة بدل الشعار (المفضلة، شاهدتها مؤخراً، كل القنوات). */
export function glyphHtml(icon, color, shape = 'circle') {
  return `<span class="lg ${shape} glyph" style="color:${color}">${icon}</span>`;
}
