// شارة «جديد»: تظهر بجانب القسم أو الزر الجديد مرة واحدة فقط لكل مستخدم ثم تختفي (يُحفظ العَلَم في المتصفح لكل حساب).
import * as auth from './auth.js';

const KEY = 'cigram_new_badges_v1';

function seen() { try { return JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { return {}; } }
const owner = () => { const u = auth.currentUser(); return u ? String(u.id || u.email || 'user') : 'guest'; };

export function pending(feature) { return !seen()[owner() + '|' + feature]; }

/** HTML الشارة إن لم تُعرض بعد لهذا المستخدم (ويُسجَّل عرضها الآن)، وإلا ''. */
export function badge(feature) {
  if (!pending(feature)) return '';
  const s = seen();
  s[owner() + '|' + feature] = 1;
  try { localStorage.setItem(KEY, JSON.stringify(s)); } catch { /* ignore */ }
  return '<span class="new-badge" aria-label="جديد">جديد</span>';
}
