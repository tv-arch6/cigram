// قلب نظام الترجمة: فك ترميز ملف الترجمة وقراءته (SRT أو VTT) وتحويله إلى أسطر جاهزة للعرض.
// لا يعتمد على الصفحة (DOM)، فنفس الكود يعمل في الموقع وفي Worker الترجمة الاحتياطي (cigram-subs/worker.js).

// ---------------------------------------------------------------- الترميز
// ترميز Windows-1256 (العربي القديم) للبايتات 0x80 إلى 0xFF. نستخدمه فقط إذا لم يدعمه TextDecoder.
const CP1256_HIGH =
  '€پ‚ƒ„…†‡ˆ‰ٹ‹Œچژڈ' +
  'گ‘’“”•–—ک™ڑ›œ‌‍ں' +
  ' ،¢£¤¥¦§¨©ھ«¬­®¯' +
  '°±²³´µ¶·¸¹؛»¼½¾؟' +
  'ہءآأؤإئابةتثجحخد' +
  'ذرزسشصض×طظعغـفقك' +
  'àلâمنهوçèéêëىيîï' +
  'ًٌٍَôُِ÷ّùْûü‎‏ے';

// Windows-1252 للبايتات 0x80 إلى 0x9F (الباقي يطابق رقمه). يلزم لإصلاح النص «المخربط».
const CP1252_C1 =
  '€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f' +
  '\u0090‘’“”•–—˜™š›œ\u009džŸ';

const ARABIC = /[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]/g;
const LETTER = /\p{L}/gu;

function countOf(text, re) {
  const m = text.match(re);
  return m ? m.length : 0;
}

function decodeWith(label, bytes, fatal = false) {
  return new TextDecoder(label, { fatal }).decode(bytes);
}

function decodeUtf16(bytes, littleEndian) {
  try {
    return decodeWith(littleEndian ? 'utf-16le' : 'utf-16be', bytes);
  } catch {
    let out = '';
    const chunk = [];
    for (let i = 0; i + 1 < bytes.length; i += 2) {
      chunk.push(littleEndian ? bytes[i] | (bytes[i + 1] << 8) : (bytes[i] << 8) | bytes[i + 1]);
      if (chunk.length === 8192) { out += String.fromCharCode(...chunk); chunk.length = 0; }
    }
    return out + String.fromCharCode(...chunk);
  }
}

function decode1256(bytes) {
  try {
    return decodeWith('windows-1256', bytes);
  } catch {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      out += b < 0x80 ? String.fromCharCode(b) : CP1256_HIGH[b - 0x80];
    }
    return out;
  }
}

/** يرجع البايتات الأصلية لنص قُرئ خطأً بترميز Windows-1252، أو null إن لم يكن ذلك ممكناً. */
function bytesAs1252(text) {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80 || (c >= 0xa0 && c <= 0xff)) { out[i] = c; continue; }
    const k = CP1252_C1.indexOf(text[i]);
    if (k < 0) return null;
    out[i] = 0x80 + k;
  }
  return out;
}

/**
 * نصوص عربية «مخربطة» شائعة في ملفات الترجمة:
 *  - «Ø§Ù„Ø³Ù„Ø§Ù…»: نص UTF-8 فُتح كـ Windows-1252 ثم حُفظ من جديد.
 *  - «ÇáÓáÇã»: نص Windows-1256 فُتح كـ Windows-1252 ثم حُفظ من جديد.
 * نصلحها فقط إذا كان الناتج عربياً بوضوح، حتى لا نلمس ترجمات فرنسية أو إسبانية سليمة.
 */
function repairMojibake(text) {
  if (countOf(text, ARABIC) > 0) return { text, repaired: '' };
  const letters = countOf(text, LETTER);
  const latin1 = countOf(text, /[À-ÿ]/g);
  if (!letters || latin1 / letters < 0.25) return { text, repaired: '' };
  const bytes = bytesAs1252(text);
  if (!bytes) return { text, repaired: '' };

  const pattern = countOf(text, /[ØÙÚÛ][\u0080-¿ŒœŠšŸŽžƒˆ˜–-›€™]/g);
  if (pattern > 3) {
    try {
      const fixed = decodeWith('utf-8', bytes, true);
      if (countOf(fixed, ARABIC) > letters * 0.3) return { text: fixed, repaired: 'utf-8-as-1252' };
    } catch { /* ليس هذا النوع */ }
  }
  const fixed = decode1256(bytes);
  if (countOf(fixed, ARABIC) > countOf(fixed, LETTER) * 0.6) return { text: fixed, repaired: '1256-as-1252' };
  return { text, repaired: '' };
}

/**
 * يحول بايتات ملف الترجمة إلى نص: UTF-8 (مع أو بدون BOM)، UTF-16، أو Windows-1256 العربي القديم،
 * ثم يصلح النص المخربط إن وُجد.
 * @param {Uint8Array} bytes
 * @returns {{ text: string, encoding: string }}
 */
export function decodeSubtitleBytes(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) return finish(decodeWith('utf-8', b.subarray(3)), 'utf-8');
  if (b[0] === 0xff && b[1] === 0xfe) return finish(decodeUtf16(b.subarray(2), true), 'utf-16le');
  if (b[0] === 0xfe && b[1] === 0xff) return finish(decodeUtf16(b.subarray(2), false), 'utf-16be');

  // UTF-16 بدون BOM: نصف البايتات تقريباً أصفار
  const sample = Math.min(b.length, 4000);
  let evenZero = 0;
  let oddZero = 0;
  for (let i = 0; i < sample; i++) if (b[i] === 0) (i % 2 ? oddZero++ : evenZero++);
  if (sample > 20 && oddZero > sample * 0.2 && evenZero < sample * 0.02) return finish(decodeUtf16(b, true), 'utf-16le');
  if (sample > 20 && evenZero > sample * 0.2 && oddZero < sample * 0.02) return finish(decodeUtf16(b, false), 'utf-16be');

  try {
    return finish(decodeWith('utf-8', b, true), 'utf-8');
  } catch {
    // ليس UTF-8 صالحاً: غالباً ملف عربي قديم بترميز Windows-1256
    return finish(decode1256(b), 'windows-1256');
  }

  function finish(text, encoding) {
    const r = repairMojibake(text.replace(/^﻿/, ''));
    return { text: r.text, encoding: r.repaired ? `${encoding}+${r.repaired}` : encoding };
  }
}

// ---------------------------------------------------------------- القراءة
const STAMP = '(?:\\d{1,3}:)?\\d{1,2}:\\d{1,2}(?:[,.:]\\d{1,3})?';
const TIMING = new RegExp(`^\\s*(${STAMP})\\s*-{1,2}>\\s*(${STAMP})(.*)$`);

function seconds(stamp) {
  const m = /^(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?$/.exec(stamp.trim());
  if (!m) return NaN;
  const frac = m[4] ? parseFloat('0.' + m[4]) : 0;
  return (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) + frac;
}

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', lrm: '‎', rlm: '‏' };

function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, name) => {
    if (name[0] === '#') {
      const code = name[1] === 'x' || name[1] === 'X' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : '';
    }
    const v = ENTITIES[name.toLowerCase()];
    return v === undefined ? all : v;
  });
}

const escText = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/**
 * ينظف سطراً واحداً: يحذف وسوم ASS مثل {\an8} وكل وسوم HTML ما عدا المائل والعريض والتسطير،
 * ويحول النص إلى شكل آمن يصلح للعرض في الصفحة ولـ WebVTT معاً.
 */
function cleanLine(raw) {
  const s = raw
    .replace(/\{\\[^}]*\}/g, '') // وسوم ASS
    .replace(/<\d{1,3}(?::\d{1,2}){1,2}(?:[.,]\d{1,3})?>/g, '') // توقيت كاريوكي في VTT
    .replace(/\\h/g, ' ');
  let out = '';
  const open = [];
  const re = /<\s*(\/?)\s*([a-z]+)[^>]*>/gi;
  let last = 0;
  let m;
  while ((m = re.exec(s))) {
    out += escText(decodeEntities(s.slice(last, m.index)));
    last = re.lastIndex;
    const tag = m[2].toLowerCase();
    if (tag !== 'i' && tag !== 'b' && tag !== 'u') continue;
    if (m[1]) {
      const at = open.lastIndexOf(tag);
      if (at < 0) continue;
      while (open.length > at) out += `</${open.pop()}>`;
    } else {
      out += `<${tag}>`;
      open.push(tag);
    }
  }
  out += escText(decodeEntities(s.slice(last)));
  while (open.length) out += `</${open.pop()}>`;
  out = out.replace(/[ \t ]+/g, ' ').trim();
  // سطر من وسوم فارغة فقط
  return out.replace(/<(i|b|u)>\s*<\/\1>/g, '').trim();
}

function isTop(raw, settings) {
  if (/\{\\an[789]\}/.test(raw)) return true;
  const line = /(?:^|\s)line:\s*(-?\d+(?:\.\d+)?)(%?)/.exec(settings || '');
  if (!line) return false;
  const v = parseFloat(line[1]);
  return line[2] ? v < 40 : v >= 0 && v < 4;
}

/**
 * يقرأ ملف SRT أو WebVTT (حتى لو كان بشكل غير قياسي: بدون أسطر فارغة، أو بأرقام زائدة، أو بوقت 00:01.5).
 * @param {string} text
 * @returns {{ start: number, end: number, lines: string[], top: boolean }[]}
 *   lines: أسطر جاهزة للعرض. النص فيها مُهرَّب، والوسوم الوحيدة الممكنة <i> و<b> و<u>.
 */
export function parseSubtitles(text) {
  const rows = String(text || '').replace(/^﻿/, '').replace(/\r\n?/g, '\n').split('\n');
  const cues = [];
  let cur = null;

  const close = (followedByCue) => {
    if (!cur) return;
    const body = [];
    for (const r of cur.raw) {
      if (!r.trim()) { if (body.length) break; continue; }
      body.push(r);
    }
    // رقم الترجمة التالية إذا لم يفصله سطر فارغ
    if (followedByCue && body.length > 1 && /^\s*\d+\s*$/.test(body[body.length - 1])) body.pop();
    const top = body.some((r) => isTop(r, '')) || isTop('', cur.settings);
    const lines = body
      .flatMap((r) => r.split(/\\N|\\n/))
      .map(cleanLine)
      .filter(Boolean);
    if (lines.length && cur.end > cur.start) cues.push({ start: cur.start, end: cur.end, lines, top });
    cur = null;
  };

  for (const row of rows) {
    const m = TIMING.exec(row);
    if (m) {
      const start = seconds(m[1]);
      const end = seconds(m[2]);
      close(true);
      if (Number.isFinite(start) && Number.isFinite(end)) cur = { start, end, settings: m[3] || '', raw: [] };
      continue;
    }
    if (cur) cur.raw.push(row);
  }
  close(false);

  cues.sort((a, b) => a.start - b.start || a.end - b.end);
  return cues;
}

/** يكتب الترجمة بصيغة WebVTT (للمشغل الأصلي في وضع ملء الشاشة على الآيفون، ولـ Worker الترجمة). */
export function toWebVtt(cues) {
  const stamp = (t) => {
    const ms = Math.max(0, Math.round(t * 1000));
    const h = Math.floor(ms / 3600000);
    const m = Math.floor((ms % 3600000) / 60000);
    const s = Math.floor((ms % 60000) / 1000);
    const f = ms % 1000;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}.${String(f).padStart(3, '0')}`;
  };
  let out = 'WEBVTT\n\n';
  cues.forEach((c, i) => {
    out += `${i + 1}\n${stamp(c.start)} --> ${stamp(c.end)}${c.top ? ' line:0' : ''}\n${c.lines.join('\n')}\n\n`;
  });
  return out;
}
