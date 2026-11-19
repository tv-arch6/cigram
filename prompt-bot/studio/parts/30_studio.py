# ----------------------------------------------------------------------------
#  إعدادات الاستوديو العامة (من لوحة الأدمن) وتفاعلات الرسائل
# ----------------------------------------------------------------------------
_ST_DEFAULTS = {"st_on": "1", "st_router": "1", "st_critic": "1", "st_clarify": "1", "st_card": "1", "st_min": "78"}


def SST(key):
    v = D().get(key)
    return _ST_DEFAULTS[key] if v is None else v


def _int(s, default=-1):
    try:
        return int(s)
    except (TypeError, ValueError):
        return default


async def _react(bot, chat_id, message_id, emoji):
    """تفاعل صغير على رسالة المستخدم: 👀 بالانتظار، ✍ دا أشتغل، 👌 خلصت. يتخطى بصمت إن ما دعمته المكتبة."""
    if not (STUDIO_REACTIONS and ReactionTypeEmoji is not None and message_id):
        return
    try:
        await bot.set_message_reaction(chat_id=chat_id, message_id=message_id,
                                       reaction=[ReactionTypeEmoji(emoji)] if emoji else [])
    except Exception as e:                                   # noqa: BLE001
        logger.debug("reaction skipped: %s", e)


def studio_queue_mark(update, ctx):
    """يرجع True إن كان عند المستخدم طلب شغّال (فهذا الطلب بالانتظار): نعلّم رسالته ونعدّ الطابور."""
    ud = ctx.user_data
    lock = ud.get("lock")
    busy = bool(lock is not None and lock.locked())
    if not busy or not STUDIO_ON or SST("st_on") != "1":
        return busy
    ud["studio_queue"] = ud.get("studio_queue", 0) + 1
    run = ud.get("studio_run")
    if run is not None:
        run.queue = ud["studio_queue"]
    msg = getattr(update, "message", None)
    if msg is not None:
        spawn(ctx.application, _react(ctx.bot, msg.chat_id, msg.message_id, "👀"))
    return busy


async def studio_on_text(update, ctx, text):
    """إن كان البوت ينتظر جواب سؤال توضيح فكتابة المستخدم تُعتبر جوابه. يرجع True إن استهلكها."""
    run = ctx.user_data.get("studio_run")
    if run is None or not (run.status == "waiting" and run.asking):
        return False
    run.free_text = text[:600]
    run.release()
    return True


def studio_override_prefs(prefs, ud):
    """أزرار «أقصر/أعمق/English» تتجاوز تفضيلات المستخدم لهذا الطلب فقط."""
    ov = ud.pop("studio_pref_ov", None)
    if not ov:
        return prefs
    p = {k: prefs[k] for k in prefs.keys()}
    p.update(ov)
    return p


# ----------------------------------------------------------------------------
#  الاستوديو لطلب واحد: يربط المسار الحي بالتحليل والكتابة والمراجعة والتسليم
# ----------------------------------------------------------------------------
_STAGE_LABELS = {T_STAGE_DOWN: "تنزيل الملف", T_STAGE_SCAN: "فحص الملف", T_STAGE_AI: "رفع الملف للبيئة"}


class _StatusAdapter:
    """نفس واجهة Status القديمة (set/done) لكنها تغذّي مسار العمل الحيّ بدل رسالة نصية بسيطة."""

    def __init__(self, studio):
        self.s = studio

    async def set(self, text):
        label = _STAGE_LABELS.get(text) or re.sub(r"^جار\s+|\s*\.{2,}$", "", text or "").strip() or "معالجة"
        self.s.run.begin("stage:" + label, label)

    async def done(self):
        await self.s.vanish()


class Studio:
    def __init__(self, ctx, update, user, has_media, queued=False):
        self.ctx, self.bot, self.user, self.ud = ctx, ctx.bot, user, ctx.user_data
        self.sp = sprefs(user.id)
        self.mode = self.sp["mode"] if self.sp["mode"] in STUDIO_MODES else "smart"
        self.run = AgentRun(user.id, user.id)
        self.live = LivePath(ctx.bot, user.id, self.run, self._view, show=bool(self.sp["path"]))
        self.run.live = self.live
        self.brief, self.system_block, self.score, self.refined, self.router_how = None, "", None, False, ""
        self.msg = getattr(update, "message", None)
        self.folded = False
        self.status = _StatusAdapter(self)
        if queued:
            self.ud["studio_queue"] = max(0, self.ud.get("studio_queue", 0) - 1)
        self.run.queue = self.ud.get("studio_queue", 0)
        _RUNS[self.run.id] = self.run
        self.ud["studio_run"] = self.run
        self.live.start()
        self._react("✍")

    # --- أدوات داخلية ---
    def _react(self, emoji):
        if self.msg is not None:
            spawn(self.ctx.application, _react(self.bot, self.msg.chat_id, self.msg.message_id, emoji))

    def _view(self, tick):
        run = self.run
        if run.status == "waiting" and run.asking and run.questions:
            return render_ask(run), ask_kb(run)
        return render_live(run, tick=tick, cursor=bool(self.sp["ascii"])), stop_kb(run)

    async def _soft(self, factory, timeout):
        """استدعاء نموذج اختياري (موجّه/مدقق): أي فشل أو تأخير يرجع None، والإيقاف فقط يرمي RunStopped."""
        if self.run.stopped:
            raise RunStopped()
        try:
            return await self.run.guard(asyncio.wait_for(factory(), timeout))
        except RunStopped:
            raise
        except Exception:                                    # noqa: BLE001
            return None

    # --- 1) التحليل (+ سؤال التوضيح عند الحاجة) ---
    async def analyze(self, base, atts, history, prefs):
        run = self.run
        run.begin("understand", "تحليل الطلب")
        prev_req = next((m["content"] for m in reversed(history or []) if m.get("role") == "user"), "")
        followup = bool(history) and looks_like_edit(base)
        fb = heuristic_brief(base, atts, followup)
        brief, how = None, "heur"
        last = self.ud.get("studio_last_brief")
        if followup and last:                                # تعديل على برومبت سابق: نبقى على نفس المجال والقيود
            brief, how = {**fb, **last, "followup": True, "ambiguity": 0.1, "questions": []}, "memory"
        elif self.mode != "fast" and SST("st_router") == "1" and not followup and not is_smalltalk(base):
            brief = await self._soft(lambda: route_with_llm(base, atts, prev_req, fb), STUDIO_ROUTER_TIMEOUT)
            if brief:
                how = "llm"
            else:
                D().bump("studio_router_fail")
        self.brief, self.router_how = brief or fb, how
        label = DOMAIN_LABEL.get(self.brief["domain"], self.brief["domain"])
        run.finish("understand", label + (f" · {self.brief['target']}" if self.brief.get("target") else ""))
        answers = await self._ask() if self._should_ask(base, atts) else ""
        self.system_block = build_studio_block(self.brief, self.user.id, answers)
        return answers

    def _should_ask(self, base, atts):
        b = self.brief
        if self.mode == "fast" or not self.sp["clarify"] or SST("st_clarify") != "1":
            return False
        if atts or b.get("followup") or not b.get("questions") or len(base.split()) > 40:
            return False
        return b.get("ambiguity", 0) >= (0.6 if self.mode == "deep" else 0.86)

    async def _ask(self):
        """«طلب الإذن» بنمط الوكلاء: سؤال أو أكثر بأزرار سريعة. المستخدم يجاوب أو يتخطى أو ننتهي بمهلة ونكمل بافتراضات."""
        run = self.run
        run.questions, run.answers, run.ask_idx, run.asking, run.free_text = self.brief["questions"][:3], {}, 0, True, ""
        st = run.begin("clarify", "توضيح سريع")
        st.status = "waiting"
        run.status = "waiting"
        run.ask_event.clear()
        await self.live.push(force=True)
        try:
            await asyncio.wait_for(run.ask_event.wait(), STUDIO_CLARIFY_WAIT)
        except asyncio.TimeoutError:
            pass
        run.asking = False
        if run.stopped:
            raise RunStopped()
        run.status = "running"
        n = len(run.answers)
        st.status, st.t1 = "done", time.time()
        st.detail = "، ".join(x for x in ((f"{n} جواب" if n else ""), ("جواب نصي" if run.free_text else "")) if x) or "تخطّيت"
        lines = [f"- {run.questions[qi]['q']} ← {run.questions[qi]['options'][oi]}" for qi, oi in sorted(run.answers.items())]
        if run.free_text:
            lines.append("- ملاحظة بكلام المستخدم: " + run.free_text)
        await self.live.push(force=True)
        if not lines:
            return ""
        return "\n\n[تأكيدات المستخدم على أسئلة التوضيح (بيانات يجب أن يلتزم بها البرومبت)]\n" + "\n".join(lines)

    # --- 2) الكتابة ---
    async def write(self, job):
        run = self.run
        cur = run.running()
        if cur is None or cur.key not in ("write", "refine"):
            run.begin("write", "كتابة البرومبت")
        try:
            return await run.guard(job)
        except RunStopped:
            raise
        except Exception:
            cur = run.running()
            if cur is not None:
                run.fail(cur.key, "فشل")
            raise

    # --- 3) المراجعة الذاتية: درجة من 100، وإن ضعف البرومبت يُعاد بتعديلات محددة ---
    async def review(self, reply, regen, base):
        run, brief = self.run, self.brief or {}
        if reply.startswith(CHAT_MARK):
            return reply
        run.finish("write")
        if self.mode == "fast" or not self.sp["critic"] or SST("st_critic") != "1":
            return reply
        if time.time() - run.t0 > STUDIO_BUDGET * 0.6:
            run.skip("review", "مراجعة الجودة", "الوقت ضيّق")
            return reply
        run.begin("review", "مراجعة الجودة")
        verdict = await self._soft(lambda: critique(base, brief, reply), STUDIO_CRITIC_TIMEOUT)
        if verdict is None:
            run.skip("review", "مراجعة الجودة", "تعذّرت")
            D().bump("studio_critic_fail")
            return reply
        self.score = verdict["score"]
        run.finish("review", f"{verdict['score']}/100")
        threshold = _int(SST("st_min"), 78) + (8 if self.mode == "deep" else 0)
        if verdict["score"] >= threshold or time.time() - run.t0 > STUDIO_BUDGET * 0.8:
            return reply
        run.begin("refine", "تحسين حسب المراجعة")
        try:
            better = await regen(refine_note(verdict, reply))
        except RunStopped:
            raise
        except Exception as e:                               # noqa: BLE001
            logger.info("studio refine failed: %s", _snip(str(e), 120))
            run.fail("refine", "تعذّر")
            return reply
        if not better or better.startswith(CHAT_MARK) or len(better) < 0.5 * len(reply):
            run.fail("refine", "رجعت للمسودة الأولى")
            return reply
        second = None
        if time.time() - run.t0 < STUDIO_BUDGET * 0.9:
            run.begin("recheck", "إعادة التقييم")
            second = await self._soft(lambda: critique(base, brief, better), STUDIO_CRITIC_TIMEOUT)
            if second is None:
                run.fail("recheck", "تعذّرت")
            else:
                run.finish("recheck", f"{second['score']}/100")
        run.finish("refine")
        if second is not None and second["score"] < verdict["score"]:
            self.score = verdict["score"]                    # النسخة المحسّنة طلعت أضعف: نصدق ونبقي الأولى
            return reply
        self.refined = True
        self.score = second["score"] if second else None     # ما نعرض درجة ما قسناها
        D().bump("studio_refined")
        return better

    # --- 4) التسليم: طيّ المسار ← بطاقة النتيجة ← أجزاء البرومبت وتحتها أزرار الإجراءات ---
    async def deliver(self, reply, base, atts):
        run, bot, uid = self.run, self.bot, self.user.id
        if run.stopped:
            raise RunStopped()
        parts = split_parts(reply)
        brief = self.brief or {}
        run.status, run.t1 = "finished", time.time()
        secs, steps = run.elapsed(), run.n_steps()
        title = brief.get("title") or _auto_title(base, atts)
        rid = studio_save_result(uid, domain=brief.get("domain", "general"), target=brief.get("target", ""), title=title,
                                 request=base, prompt=reply, score=-1 if self.score is None else self.score, secs=secs,
                                 steps=steps, parts=len(parts), refined=self.refined, router=self.router_how, mode=self.mode)
        D().bump("studio_runs")
        self.ud["studio_last_brief"] = {k: brief.get(k, "") for k in ("domain", "target", "goal", "language", "constraints", "title")}
        head = f"اشتغلت {ar_secs(secs)} · {ar_steps(steps)}"
        head += f" · الجودة {self.score}/100" if self.score is not None else (" · حُسّن بعد المراجعة" if self.refined else "")
        if await self.live.fold(run, head, animate=bool(self.sp["ascii"])):
            self.folded = True
        if self.sp["card"] and SST("st_card") == "1" and card_supported():
            try:
                img = await _to_thread(make_result_card, title, DOMAIN_LABEL.get(brief.get("domain"), ""), brief.get("target", ""),
                                       self.score, self.refined, secs, steps, len(parts), len(reply), rid)
                if img:
                    await send_photo_html(bot, uid, img, "")
            except Exception as e:                           # noqa: BLE001
                logger.warning("result card failed: %s", e)
        await send_prompt(bot, uid, reply, markup=result_kb(rid, False))
        self._react("👌")

    async def on_stopped(self):
        run = self.run
        run.status, run.t1 = "stopped", time.time()
        for s in run.steps:
            if s.status in ("running", "waiting"):
                s.status, s.t1, s.detail = "skipped", time.time(), "أُوقف"
        if await self.live.fold(run, f"أوقفتها بعد {ar_secs(run.elapsed())}"):
            self.folded = True
        else:
            await safe_send(self.bot, self.user.id, esc_html("تمام، أوقفت الطلب."))
        self._react("")

    async def vanish(self):
        if not self.folded:
            await self.live.vanish()

    def close(self):
        self.live.close()
        _RUNS.pop(self.run.id, None)
        if self.ud.get("studio_run") is self.run:
            self.ud.pop("studio_run", None)
        if self.run.status not in ("finished", "stopped"):
            self._react("")                                  # فشل أو خروج مبكر: نشيل علامة «دا أشتغل»


def studio_begin(ctx, update, user, media_msgs, queued=False):
    """يرجع Studio للطلب، أو None إن كان متوقفاً أو تعذّر تشغيله (فيكمل البوت بالطريقة القديمة)."""
    if not STUDIO_ON or SST("st_on") != "1":
        return None
    try:
        return Studio(ctx, update, user, bool(media_msgs), queued)
    except Exception:                                        # noqa: BLE001
        logger.exception("studio init failed; continuing without it")
        return None


# ----------------------------------------------------------------------------
#  أزرار تحت البرومبت: أقصر / أعمق / نسخة مختلفة / English / تجربة سريعة / حفظ / تقييم
# ----------------------------------------------------------------------------
_TRANSFORMS = {
    "s": "اجعل البرومبت أقصر ومركّزاً (نحو نصف طوله) مع الحفاظ على كل الشروط الأساسية وبلا حذف أي قيد ذكرته.",
    "d": "اجعل البرومبت أعمق وأشمل: أضف تفاصيل ومعايير قبول وحالات حدّية وأمثلة تفيد المنفّذ، بدون تكرار.",
    "v": "اكتب نسخة مختلفة جذرياً من البرومبت في البنية والعناوين والصياغة مع الحفاظ على المقصد نفسه.",
    "e": "ترجم البرومبت كاملاً إلى الإنجليزية الاحترافية بصيغة prompt جاهزة للاستخدام مع الحفاظ على بنيته وكل شروطه.",
}
_TRANSFORM_PREFS = {"s": {"detail": "short"}, "d": {"detail": "deep"}, "e": {"dialect": "english"}}


def result_kb(rid, saved=False, fb=0):
    rows = [[btn("أقصر", f"u:rv:{rid}.s", "primary"), btn("أعمق", f"u:rv:{rid}.d", "primary"),
             btn("نسخة مختلفة", f"u:rv:{rid}.v", "success")],
            [btn("English", f"u:rv:{rid}.e", "primary"), btn("تجربة سريعة", f"u:rv:{rid}.t", "primary"),
             btn("إزالة من مكتبتي" if saved else "حفظ بمكتبتي", f"u:rv:{rid}.k", "danger" if saved else "success")]]
    if not fb:
        rows.append([btn("ممتاز", f"u:rv:{rid}.g", "success"), btn("ضعيف", f"u:rv:{rid}.b", "danger")])
    return kb(rows)


async def _refresh_kb(q, rid, saved, fb):
    try:
        await q.edit_message_reply_markup(reply_markup=result_kb(rid, saved, fb))
    except BadRequest as e:
        if _style_rejected(e, {"reply_markup": True}):
            try:
                await q.edit_message_reply_markup(reply_markup=plain_kb(result_kb(rid, saved, fb)))
            except Exception:                                # noqa: BLE001
                pass
    except Exception:                                        # noqa: BLE001
        pass


async def u_result(ctx, q, arg):
    rid_s, _, op = str(arg).partition(".")
    uid, ud = q.from_user.id, ctx.user_data
    row = studio_get_result(_int(rid_s), uid)
    if row is None:
        await safe_send(ctx.bot, uid, esc_html("هذا البرومبت ما عاد بالذاكرة. ابعث طلبك من جديد."))
        return
    d = _sdb()
    rid = row["id"]
    if op in _TRANSFORMS:
        ud["history"] = [{"role": "user", "content": row["request"][:3000]},
                         {"role": "assistant", "content": row["prompt"][:HISTORY_ITEM_CHARS]}]
        ud["studio_force_hist"] = True
        ud["studio_last_brief"] = {"domain": row["domain"], "target": row["target"], "title": row["title"]}
        if op in _TRANSFORM_PREFS:
            ud["studio_pref_ov"] = dict(_TRANSFORM_PREFS[op])
        await serve(SimpleNamespace(effective_user=q.from_user, callback_query=q), ctx, _TRANSFORMS[op], [], [])
    elif op == "k":
        if row["saved"]:
            d.x("UPDATE studio_results SET saved=0 WHERE id=?", (rid,))
            saved = False
        else:
            if d.scalar("SELECT COUNT(*) FROM studio_results WHERE uid=? AND saved=1", (uid,)) >= STUDIO_LIB_MAX:
                await safe_send(ctx.bot, uid, esc_html(f"مكتبتك وصلت الحد ({STUDIO_LIB_MAX}). احذف شي منها وارجع احفظ."))
                return
            d.x("UPDATE studio_results SET saved=1 WHERE id=?", (rid,))
            saved = True
        await _refresh_kb(q, rid, saved, row["feedback"])
    elif op in ("g", "b"):
        fb = 1 if op == "g" else -1
        d.x("UPDATE studio_results SET feedback=? WHERE id=?", (fb, rid))
        await _refresh_kb(q, rid, bool(row["saved"]), fb)
    elif op == "t":
        await studio_try(ctx, q, row)


async def studio_try(ctx, q, row):
    """«تجربة سريعة»: ينفّذ البرومبت مرة وحدة بنموذج البوت ويعرض بداية الناتج (يختلف عن أداتك الأصلية)."""
    uid, ud = q.from_user.id, ctx.user_data
    if time.time() - ud.get("studio_try_ts", 0) < STUDIO_TRY_COOLDOWN:
        await safe_send(ctx.bot, uid, esc_html("استنى شوية قبل التجربة الثانية."))
        return
    d = D()
    refusal = check_limit(uid, d.user(uid))
    if refusal:
        await safe_send(ctx.bot, uid, esc_html(refusal))
        return
    ud["studio_try_ts"] = time.time()
    status = Status(ctx.bot, uid)
    typing = asyncio.ensure_future(_typing_loop(ctx.bot, uid))
    try:
        await status.set("دا أجرّب البرومبت...")
        out = await asyncio.wait_for(omni_chat([{"role": "user", "content": row["prompt"][:12000]}]), 100)
        if SB("subs_on") and not d.is_admin(uid):
            d.usage_inc(uid)
        d.bump("studio_try")
        out = clean_llm_text(out)[:3200]
        await status.done()
        await send_html(ctx.bot, uid, "<b>نتيجة التجربة السريعة</b>\n<blockquote expandable>" + esc_html(out) + "</blockquote>\n"
                        + esc_html("هذي تجربة بنموذج البوت، وبأداتك الأصلية ممكن تطلع مختلفة."))
    except Exception as e:                                   # noqa: BLE001
        await status.done()
        logger.info("studio try failed: %s", _snip(str(e), 150))
        await safe_send(ctx.bot, uid, esc_html("ما كدرت أجرّب البرومبت هسه، جرّب بعد شوية."))
    finally:
        typing.cancel()


# ----------------------------------------------------------------------------
#  أزرار المسار: إيقاف، وأجوبة التوضيح
# ----------------------------------------------------------------------------
async def u_stop(ctx, q, arg):
    run = _RUNS.get(_int(arg))
    if run is None or run.uid != q.from_user.id:
        try:
            await q.edit_message_reply_markup(reply_markup=None)
        except Exception:                                    # noqa: BLE001
            pass
        return
    run.stop()


async def u_ask_answer(ctx, q, arg):
    try:
        rid, qi, oi = (int(x) for x in str(arg).split("."))
    except ValueError:
        return
    run = _RUNS.get(rid)
    if run is None or run.uid != q.from_user.id or not run.asking or qi != run.ask_idx:
        return
    if not (0 <= qi < len(run.questions) and 0 <= oi < len(run.questions[qi]["options"])):
        return
    run.answers[qi] = oi
    run.ask_idx += 1
    if run.ask_idx >= len(run.questions):
        run.release()
    elif run.live is not None:
        await run.live.push(force=True)


async def u_ask_skip(ctx, q, arg):
    run = _RUNS.get(_int(arg))
    if run is not None and run.uid == q.from_user.id:
        run.release()


# ----------------------------------------------------------------------------
#  مكتبتي: البرومبتات المحفوظة (/library)
# ----------------------------------------------------------------------------
_LIB_PAGE = 6


def lib_screen(uid, page=0):
    d = _sdb()
    total = d.scalar("SELECT COUNT(*) FROM studio_results WHERE uid=? AND saved=1", (uid,))
    pages = max(1, (total + _LIB_PAGE - 1) // _LIB_PAGE)
    page = max(0, min(page, pages - 1))
    items = d.q("SELECT id, title, domain FROM studio_results WHERE uid=? AND saved=1 ORDER BY id DESC LIMIT ? OFFSET ?",
                (uid, _LIB_PAGE, page * _LIB_PAGE))
    if not total:
        text = "<b>مكتبتي</b>\n<blockquote>" + esc_html("فارغة هسه. تحت كل برومبت دوس «حفظ بمكتبتي» وراح يجي هنا، تفتحه وتنسخه بأي وقت.") + "</blockquote>"
    else:
        text = f"<b>مكتبتي ({total})</b>\n<blockquote>" + esc_html("اختار برومبت حتى تفتحه.") + "</blockquote>"
    rows = [[btn(f"{r['title'][:24]} · {DOMAIN_LABEL.get(r['domain'], '')}", f"u:lv:{r['id']}", "primary")] for r in items]
    nav = ([btn("السابق", f"u:lib:{page - 1}")] if page > 0 else []) + ([btn("التالي", f"u:lib:{page + 1}")] if page < pages - 1 else [])
    if nav:
        rows.append(nav)
    rows.append(back_row())
    return text, kb(rows)


async def u_lib(ctx, q, arg):
    text, markup = lib_screen(q.from_user.id, _int(arg, 0))
    await edit_html(ctx.bot, q.message, text, markup)


async def cmd_library(update, ctx):
    user = update.effective_user
    text, markup = lib_screen(user.id)
    await send_html(ctx.bot, user.id, text, reply_markup=markup)


async def u_lib_view(ctx, q, arg):
    row = studio_get_result(_int(arg), q.from_user.id)
    if row is None or not row["saved"]:
        text, markup = lib_screen(q.from_user.id)
        await edit_html(ctx.bot, q.message, text, markup)
        return
    info = [f"المجال: {DOMAIN_LABEL.get(row['domain'], row['domain'])}"]
    if row["target"]:
        info.append(f"الأداة: {row['target']}")
    info.append(f"الطول: {len(row['prompt'])} حرف ({ar_parts(row['parts'])})")
    if row["score"] >= 0:
        info.append(f"الجودة: {row['score']}/100")
    info.append("التاريخ: " + fmt_ts(row["ts"]))
    text = f"<b>{esc_html(row['title'])}</b>\n<blockquote>{esc_html(chr(10).join(info))}</blockquote>\n<blockquote expandable>{esc_html(row['prompt'][:900])}...</blockquote>"
    await edit_html(ctx.bot, q.message, text, kb([[btn("إرسال البرومبت كامل", f"u:lo:{row['id']}", "success"), btn("حذف", f"u:ld:{row['id']}", "danger")],
                                                  back_row("u:lib:0", "رجوع لمكتبتي")]))


async def u_lib_open(ctx, q, arg):
    row = studio_get_result(_int(arg), q.from_user.id)
    if row is not None:
        await send_prompt(ctx.bot, q.from_user.id, row["prompt"], markup=result_kb(row["id"], bool(row["saved"]), row["feedback"]))


async def u_lib_del(ctx, q, arg):
    _sdb().x("UPDATE studio_results SET saved=0 WHERE id=? AND uid=?", (_int(arg), q.from_user.id))
    text, markup = lib_screen(q.from_user.id)
    await edit_html(ctx.bot, q.message, text, markup)


# ----------------------------------------------------------------------------
#  إعدادات المستخدم (تُلحق بشاشة «إعداداتي»)
# ----------------------------------------------------------------------------
_MODE_DESC = {"fast": "بلا موجّه ولا مراجعة (أسرع)", "smart": "يحلل الطلب ويراجع البرومبت", "deep": "تحليل + أسئلة توضيح + مراجعة صارمة"}


_FEMININE = {"critic", "clarify", "ascii", "card"}           # المراجعة، الأسئلة، الحركات، البطاقة: مؤنث بالعربي


def _sw(sp, key):
    fem = key in _FEMININE
    return _onoff(sp[key], "شغّالة" if fem else "شغّال", "مطفية" if fem else "مطفي")


def studio_settings_text(uid):
    sp = sprefs(uid)
    return ("<b>الاستوديو الذكي</b>\n<blockquote>" + esc_html(
        f"الوضع: {STUDIO_MODES[sp['mode']]} ({_MODE_DESC[sp['mode']]})\nالمسار الحي: {_sw(sp, 'path')} | المراجعة الذاتية: {_sw(sp, 'critic')}\n"
        f"أسئلة التوضيح: {_sw(sp, 'clarify')} | حركات ASCII: {_sw(sp, 'ascii')} | بطاقة النتيجة: {_sw(sp, 'card')}") + "</blockquote>")


def studio_settings_rows(uid):
    sp = sprefs(uid)
    flag = lambda k, label: btn(f"{label}: {_sw(sp, k)}", f"u:ss:{k}", "success" if sp[k] else "danger")   # noqa: E731
    return [[btn(f"الوضع: {STUDIO_MODES[sp['mode']]}", "u:ss:mode", "primary"), flag("path", "المسار الحي")],
            [flag("critic", "المراجعة"), flag("clarify", "أسئلة التوضيح")],
            [flag("ascii", "حركات ASCII"), flag("card", "بطاقة النتيجة")]]


async def u_studio_set(ctx, q, arg):
    uid = q.from_user.id
    sp = sprefs(uid)
    if arg == "mode":
        set_sprefs(uid, mode=cycle(STUDIO_MODES, sp["mode"]))
    elif arg in SP_DEFAULTS and arg != "mode":
        set_sprefs(uid, **{arg: 0 if sp[arg] else 1})
    text, markup = settings_screen(uid)
    await edit_html(ctx.bot, q.message, text, markup)


STUDIO_HELP = """

<b>الاستوديو الذكي</b>
<blockquote>• مسار العمل الحي: تشوف البوت شنو دا يسوي خطوة بخطوة (يحلل، يكتب، يراجع)، وتكدر توقفه بزر إيقاف.
• الموجّه: يعرف نوع طلبك (برمجة، صور، فيديو، بوت، وكيل...) ويشغّل وصفة متخصصة له.
• أسئلة التوضيح: إذا طلبك غامض يسألك سؤال أو سؤالين بأزرار سريعة، وتكدر تتخطاها.
• المراجعة الذاتية: يقيّم البرومبت بدرجة من 100 ويحسّنه إذا ضعيف.
• تحت كل برومبت: أقصر، أعمق، نسخة مختلفة، English، تجربة سريعة، وحفظ بمكتبتك (/library).
• كل شي تتحكم بيه من إعداداتي.</blockquote>"""


# ----------------------------------------------------------------------------
#  لوحة الأدمن: الاستوديو (مفاتيح عامة + إحصائيات الجودة)
# ----------------------------------------------------------------------------
async def a_studio(ctx, q, args, act):
    d = _sdb()
    sub = args[0] if args else ""
    flips = {"o": "st_on", "r": "st_router", "c": "st_critic", "q": "st_clarify", "k": "st_card"}
    if sub in flips:
        d.set(flips[sub], "0" if SST(flips[sub]) == "1" else "1")
    elif sub in ("m+", "m-"):
        d.set("st_min", str(max(50, min(95, _int(SST("st_min"), 78) + (5 if sub == "m+" else -5)))))
    week = now_ts() - 7 * 86400
    total = d.scalar("SELECT COUNT(*) FROM studio_results")
    today = d.scalar("SELECT COUNT(*) FROM studio_results WHERE ts>=?", (now_ts() - 86400,))
    avg_secs = d.scalar("SELECT AVG(secs) FROM studio_results WHERE ts>=?", (week,), 0)
    avg_score = d.scalar("SELECT AVG(score) FROM studio_results WHERE score>=0 AND ts>=?", (week,), 0)
    refined = d.scalar("SELECT COUNT(*) FROM studio_results WHERE refined=1 AND ts>=?", (week,))
    saved = d.scalar("SELECT COUNT(*) FROM studio_results WHERE saved=1")
    good = d.scalar("SELECT COUNT(*) FROM studio_results WHERE feedback>0 AND ts>=?", (week,))
    bad = d.scalar("SELECT COUNT(*) FROM studio_results WHERE feedback<0 AND ts>=?", (week,))
    rfail = d.counter_total("studio_router_fail", day_key(time.time() - 7 * 86400))
    cfail = d.counter_total("studio_critic_fail", day_key(time.time() - 7 * 86400))
    top = d.q("SELECT domain, COUNT(*) n FROM studio_results WHERE ts>=? GROUP BY domain ORDER BY n DESC LIMIT 5", (week,))
    lines = [f"الحالة: {_onoff(SST('st_on') == '1', 'شغّال', 'مطفي')} | الحد الأدنى للجودة: {SST('st_min')}",
             f"النتائج: {total} (اليوم {today}) | محفوظة بالمكتبات: {saved}",
             f"آخر 7 أيام: متوسط الوقت {avg_secs:.0f} ثانية، متوسط الجودة {avg_score:.0f}/100، تحسّن {refined} برومبت",
             f"التقييمات: {good} ممتاز / {bad} ضعيف | فشل الموجّه {rfail} مرة، فشل المدقق {cfail} مرة",
             "أكثر المجالات: " + ("، ".join(f"{DOMAIN_LABEL.get(r['domain'], r['domain'])} ({r['n']})" for r in top) or "ماكو بعد")]
    flag = lambda key, label, k: btn(f"{label}: {_onoff(SST(key) == '1', 'شغّال', 'مطفي')}", f"a:studio:{k}", "success" if SST(key) == "1" else "danger")   # noqa: E731
    rows = [[flag("st_on", "الاستوديو", "o"), flag("st_router", "الموجّه", "r")],
            [flag("st_critic", "المراجعة", "c"), flag("st_clarify", "التوضيح", "q")],
            [flag("st_card", "البطاقة", "k")],
            [btn("خفّض حد الجودة", "a:studio:m-", "primary"), btn("ارفع حد الجودة", "a:studio:m+", "primary")]]
    await view(ctx, q, "<b>الاستوديو الذكي</b>\n<blockquote>" + esc_html("\n".join(lines)) + "</blockquote>", rows)


# ----------------------------------------------------------------------------
#  بطاقة النتيجة (صورة): زجاج أبيض على أسود مثل بطاقة الترحيب + حلقة جودة + معلومات الطلب
# ----------------------------------------------------------------------------
def _pill(img, draw, right, top, text, size=40, height=84, pad=34):
    """يرسم حبة زجاجية بنص عربي ويرجع الحافة اليسرى (نرصّ الحبات من اليمين لليسار)."""
    font, shown, kw, w = _fit_font(draw, text, 420, size, False, 28)
    pw = int(w + pad * 2)
    left = right - pw
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rounded_rectangle((left, top, right, top + height), radius=height // 2, fill=(255, 255, 255, 26))
    for i in range(5):
        ld.rounded_rectangle((left + i, top + i, right - i, top + height - i), radius=max(height // 2 - i, 1), outline=(255, 255, 255, 110 - i * 20))
    img.alpha_composite(layer)
    ImageDraw.Draw(img).text((left + pad, top + (height - size) / 2 - 8), shown, font=font, fill=(255, 255, 255, 255), **kw)
    return left - 18


def _card_title(title):
    """عنوان البطاقة: بلا إيموجي ولا رموز يعجز عنها الخط، حتى 70 حرفاً (ثم …)."""
    t = "".join(ch for ch in (title or "") if ord(ch) <= 0xFFFF and unicodedata.category(ch)[0] in "LNPZM" and ch != "️")
    t = re.sub(r"\s+", " ", t).strip() or "برومبت"
    return t if len(t) <= 70 else t[:69].rstrip() + "…"


def make_result_card(title, domain, target, score, refined, secs, steps, parts, chars, seed=0, fmt="JPEG"):
    """بطاقة 1080×640: حلقة الجودة يسار، والعنوان والشارات والأرقام يمين. بلا ألوان (أبيض زجاجي) لتنسجم مع بطاقة الترحيب."""
    if not card_supported():
        return None
    W, H = 1080, 640
    rnd = random.Random(seed)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    cx, cy, R = 235, H // 2, 150
    text_box = (400, 70, 1040, 580)

    def clear_of_text(x, y, r):
        dx, dy = max(text_box[0] - x, 0, x - text_box[2]), max(text_box[1] - y, 0, y - text_box[3])
        return (dx * dx + dy * dy) ** 0.5 >= r * 1.05

    placed = []
    for _ in range(rnd.randint(7, 10)):
        r = max(36, int(round(rnd.choice([rnd.randint(60, 130), rnd.randint(34, 70), rnd.randint(130, 190)]) / float(CARD_R_STEP))) * CARD_R_STEP)
        for _try in range(60):
            x, y = rnd.randint(-40, W + 40), rnd.randint(-40, H + 40)
            if not clear_of_text(x, y, r) or ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 < R + r * 0.7:
                continue
            if all((x - px) ** 2 + (y - py) ** 2 > (r + pr) ** 2 * 0.28 for px, py, pr in placed):
                placed.append((x, y, r))
                tile = _glass_circle(r)
                img.alpha_composite(tile, (int(x - tile.width / 2), int(y - tile.height / 2)))
                break
    glass = _glass_circle(R)
    img.alpha_composite(glass, (int(cx - glass.width / 2), int(cy - glass.height / 2)))
    # حلقة الجودة (تُرسم بدقة مضاعفة ثم تُصغَّر لحواف ناعمة)
    ss, rr, rw = 3, R - 36, 16
    side = (rr + rw + 6) * 2
    layer = Image.new("RGBA", (side * ss, side * ss), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    c = side * ss // 2
    box = (c - rr * ss, c - rr * ss, c + rr * ss, c + rr * ss)
    ld.ellipse(box, outline=(255, 255, 255, 46), width=rw * ss)
    frac = 1.0 if score is None else max(0.02, min(1.0, score / 100.0))
    ld.arc(box, -90, -90 + 360 * frac, fill=(255, 255, 255, 255), width=rw * ss)
    layer = layer.resize((side, side), Image.LANCZOS)
    img.alpha_composite(layer, (cx - side // 2, cy - side // 2))
    draw = ImageDraw.Draw(img)

    def centered(text, x, y, start, color, max_w, bold=False):
        font, shown, kw, w = _fit_font(draw, text, max_w, start, bold, 24)
        draw.text((x - w / 2, y), shown, font=font, fill=color, **kw)

    if score is not None:
        centered(str(score), cx, cy - 78, 120, (255, 255, 255, 255), 170, True)
        centered("الجودة", cx, cy + 52, 40, (176, 178, 194, 255), 150)
    else:
        centered("جاهز", cx, cy - 62, 88, (255, 255, 255, 255), 170, True)
        centered("محسّن" if refined else "بلا تقييم", cx, cy + 46, 38, (176, 178, 194, 255), 150)
    # العنوان (سطر أو سطران) محاذى لليمين
    right = W - 70
    title = _card_title(title)
    lines = [title]
    font, shown, kw, w = _fit_font(draw, title, 570, 78, True, 54)
    if w > 570 and " " in title:
        words = title.split()
        best = min(range(1, len(words)), key=lambda i: abs(len(" ".join(words[:i])) - len(" ".join(words[i:]))))
        lines = [" ".join(words[:best]), " ".join(words[best:])]
    y = 96 if len(lines) == 1 else 76
    for ln in lines:
        font, shown, kw, w = _fit_font(draw, ln, 570, 78 if len(lines) == 1 else 64, True, 40)
        draw.text((right - w, y), shown, font=font, fill=(255, 255, 255, 255), **kw)
        y += 104 if len(lines) == 1 else 88
    # شارات: المجال والأداة
    chip_y, x = 278, right
    for label in [t for t in (domain, target) if t]:
        x = _pill(img, draw, x, chip_y, label)
        draw = ImageDraw.Draw(img)
    # خط فاصل ثم الأرقام
    ImageDraw.Draw(img).line((430, 410, right, 410), fill=(64, 64, 70, 255), width=2)
    stats = "  ·  ".join([ar_secs(secs), ar_steps(steps), ar_parts(parts), f"{chars:,} حرف"])
    font, shown, kw, w = _fit_font(draw, stats, 600, 42, False, 26)
    draw.text((right - w, 440), shown, font=font, fill=(176, 178, 194, 255), **kw)
    out = io.BytesIO()
    rgb = img.convert("RGB")
    if fmt == "JPEG":
        rgb.save(out, "JPEG", quality=90, subsampling=0)
    else:
        rgb.save(out, "PNG", compress_level=6)
    return out.getvalue()


# ----------------------------------------------------------------------------
#  التسجيل: أزرار المستخدم والأدمن والأوامر (يُستدعى من build_app) وتهيئة الإقلاع
# ----------------------------------------------------------------------------
def studio_register(app=None):
    DEFAULTS.update(_ST_DEFAULTS)
    USER_ACTIONS.update({
        "st": (u_stop, "دا أوقف الطلب"), "ca": (u_ask_answer, None), "cs": (u_ask_skip, "تمام، أبني مباشرة"),
        "rv": (u_result, None), "lib": (u_lib, None), "lv": (u_lib_view, None), "lo": (u_lib_open, "جار الإرسال"),
        "ld": (u_lib_del, "انحذف من مكتبتك"), "ss": (u_studio_set, None),
    })
    if not any(b[0] == "studio" for b in ADMIN_BUTTONS):
        ADMIN_BUTTONS.append(("studio", "الاستوديو الذكي", "success",
                              "الموجّه والمراجعة الذاتية وأسئلة التوضيح وبطاقة النتيجة، وإحصائيات الجودة.", True))
    ADMIN_ACTIONS["studio"] = a_studio
    if ("library", "مكتبتي") not in COMMANDS:
        COMMANDS.append(("library", "مكتبتي"))
    if app is not None:
        app.add_handler(CommandHandler("library", cmd_library, filters=filters.UpdateType.MESSAGE))


def studio_boot(app):
    """عند الإقلاع: ينشئ الجداول ويسجّل الحالة (لا يوقف البوت إن فشل)."""
    try:
        _sdb()
        logger.info("الاستوديو الذكي: %s | الموجّه=%s | المراجعة=%s | تفاعلات=%s", "شغّال" if STUDIO_ON and SST("st_on") == "1" else "متوقف",
                    SST("st_router"), SST("st_critic"), bool(ReactionTypeEmoji and STUDIO_REACTIONS))
    except Exception:                                        # noqa: BLE001
        logger.exception("studio boot failed")
