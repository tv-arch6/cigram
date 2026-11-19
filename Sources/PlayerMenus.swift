import UIKit
import AVFoundation
import AVKit

// The menus of the native player: the settings panel (الجودة · السرعة · الترجمة · توقيت الترجمة · حجم الترجمة · مسار الصوت ·
// أبعاد الشاشة · تخطي · مؤقت الإيقاف · المصدر · معلومات الفيديو), the episode list, and the shortcuts on the bottom chips.
// Same rows and wording as the Android CigramPlayerSheet.

extension PlayerViewController {

    // MARK: - opening the panel

    func openSheet(_ page: SheetPage) {
        if let s = sheet, s.isOpen {
            s.push(page)
            return
        }
        let s = PlayerSheet(frame: view.bounds)
        s.onClose = { [weak self] in
            self?.sheet = nil
            self?.showControls()
        }
        sheet = s
        hideTimer?.invalidate()
        s.show(page, in: view)
    }

    @objc func settingsTapped() { openSheet(mainPage()) }
    @objc func speedChipTapped() { openSheet(speedPage()) }
    @objc func qualityChipTapped() { openSheet(qualityPage()) }
    @objc func subsChipTapped() { openSheet(subtitlesPage()) }
    @objc func sourceChipTapped() { openSheet(sourcesPage()) }
    @objc func episodesTapped() { openSheet(episodesPage()) }

    @objc func resizeChipTapped() {
        setResize((resizeMode + 1) % 3)
        showControls()
    }

    // MARK: - values shown on the rows

    func qualityValue() -> String {
        if manualHeight > 0 { return QualityLabel.label(height: manualHeight) }
        if PlayerPrefs.preferredHeight > 0 { return "تلقائي · حتى " + PlayerPrefs.qualityName(PlayerPrefs.preferredHeight) }
        return "تلقائي"
    }

    func subtitleValue() -> String {
        if subSelected >= 0 && subSelected < subChoices.count { return subChoices[subSelected].title }
        return "إيقاف"
    }

    func delayText(_ d: Double) -> String {
        if abs(d) < 0.001 { return "0 (متزامنة)" }
        return (d > 0 ? "+" : "−") + String(format: "%g", abs(d)) + " ث"
    }

    func speedText(_ v: Float) -> String { return v == 1 ? "1x (عادي)" : String(format: "%gx", Double(v)) }

    func sleepText() -> String {
        let m = PlayerPrefs.sleepMinutes
        return m <= 0 ? "متوقف" : String(m) + " دقيقة"
    }

    func sizeNames() -> [String] { return ["صغير", "متوسط", "كبير", "أكبر"] }

    // MARK: - main page

    func mainPage() -> SheetPage {
        return SheetPage(title: "إعدادات المشاهدة") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            items.append(.section("الصورة"))
            items.append(.action(title: "الجودة", value: self.qualityValue(), handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.qualityPage())
            }))
            items.append(.action(title: "سرعة العرض", value: self.speedText(self.speed), handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.speedPage())
            }))
            items.append(.segments(title: "أبعاد الشاشة", labels: ["ملاءمة", "ملء الشاشة", "تمديد"], selected: self.resizeMode, handler: { [weak self] i in
                self?.setResize(i)
            }))
            items.append(.section("الترجمة"))
            items.append(.action(title: "الترجمة", value: self.subtitleValue(), handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.subtitlesPage())
            }))
            items.append(.action(title: "توقيت الترجمة", value: self.subDoc == nil ? "—" : self.delayText(self.subDelay), handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.delayPage())
            }))
            items.append(.action(title: "حجم الترجمة", value: self.sizeNames()[min(3, max(0, PlayerPrefs.subtitleSize))], handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.subtitleSizePage())
            }))
            items.append(.section("التشغيل"))
            if self.episode != nil {
                items.append(.toggle(title: "بدء الحلقة التالية تلقائياً", isOn: PlayerPrefs.autoNext, handler: { on in PlayerPrefs.autoNext = on }))
                items.append(.toggle(title: "تخطي المقدمة تلقائياً", isOn: PlayerPrefs.autoSkipIntro, handler: { on in PlayerPrefs.autoSkipIntro = on }))
            }
            if !self.sceneSegments.isEmpty {
                items.append(.toggle(title: "تخطي المشاهد المحددة", isOn: PlayerPrefs.skipScenes, handler: { on in PlayerPrefs.skipScenes = on }))
            }
            items.append(.action(title: "مدة التقديم والتأخير", value: String(PlayerPrefs.seekStep) + " ث", handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.seekStepPage())
            }))
            items.append(.action(title: "مؤقت الإيقاف", value: self.sleepText(), handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.push(self.sleepPage())
            }))
            return items
        }
    }

    // MARK: - quality

    func qualityPage() -> SheetPage {
        return SheetPage(title: "الجودة") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            let offered = self.offeredQualities()
            items.append(.choice(title: "تلقائي · موصى به (حسب سرعة الإنترنت)", selected: self.manualHeight == 0, handler: { [weak self] in
                self?.chooseQuality(0)
                self?.sheet?.pop()
            }))
            for q in offered {
                let h = q.height
                items.append(.choice(title: QualityLabel.label(height: h), selected: self.manualHeight == h, handler: { [weak self] in
                    self?.chooseQuality(h)
                    self?.sheet?.pop()
                }))
            }
            if offered.isEmpty {
                let k = StreamLink.kind(of: self.currentStream)
                if self.masterInfo == nil && (k == .hls || k == nil) && self.isReady && self.variants.isEmpty {
                    items.append(.note("جارٍ البحث عن دقات أخرى لـ" + (self.episode != nil ? "هذه الحلقة" : "هذا الفيلم") + "… أعد فتح القائمة بعد لحظات"))
                } else {
                    items.append(.note((self.episode != nil ? "هذه الحلقة متوفرة" : "هذا الفيلم متوفر") + " بدقة واحدة فقط"))
                }
            }
            if self.smartCap > 0 {
                items.append(.note("الجودة التلقائية محدودة بـ " + String(self.smartCap) + "p حالياً (البطارية / الحرارة / توفير البيانات). اختيارك اليدوي يتجاوز هذا الحد."))
            }
            items.append(.section("الجودة المفضلة"))
            for h in [0, 720, 1080, 1440, 2160] {
                let title = h == 0 ? PlayerPrefs.qualityName(0) : "حتى " + PlayerPrefs.qualityName(h)
                items.append(.choice(title: title, selected: PlayerPrefs.preferredHeight == h, handler: { [weak self] in
                    PlayerPrefs.preferredHeight = h
                    if let it = self?.item { self?.applyQualityLimits(to: it) }
                    self?.refreshChips()
                    self?.sheet?.reload()
                }))
            }
            items.append(.note("هي الحد الأعلى: يختار المشغّل أفضل جودة تتحمّلها سرعة إنترنتك من 720p حتى 4K دون أن يتجاوز اختيارك، وتُطبَّق على كل الأفلام والمسلسلات."))
            return items
        }
    }

    // MARK: - speed

    func speedPage() -> SheetPage {
        return SheetPage(title: "سرعة العرض") { [weak self] in
            guard let self = self else { return [] }
            let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
            var items: [SheetItem] = []
            for v in speeds {
                items.append(.choice(title: self.speedText(v), selected: abs(self.speed - v) < 0.01, handler: { [weak self] in
                    self?.setSpeed(v)
                    self?.sheet?.pop()
                }))
            }
            if self.episode != nil { items.append(.note("السرعة تُحفظ لهذا المسلسل وتُطبَّق على حلقاته القادمة.")) }
            return items
        }
    }

    func setSpeed(_ v: Float) {
        speed = v
        applySpeed()
        if speedKey.isEmpty { PlayerPrefs.speed = v } else { SmartPlayback.rememberSpeed(key: speedKey, speed: v) }
        refreshChips()
    }

    // MARK: - subtitles

    func subtitlesPage() -> SheetPage {
        return SheetPage(title: "الترجمة") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            items.append(.choice(title: "بدون ترجمة", selected: self.subSelected < 0, handler: { [weak self] in
                self?.disableSubtitles()
                self?.sheet?.pop()
            }))
            for (i, c) in self.subChoices.enumerated() {
                var title = c.title
                if case .embedded = c.kind { title += " (مدمجة)" }
                items.append(.choice(title: title, selected: self.subSelected == i, handler: { [weak self] in
                    self?.selectSubtitle(i)
                    self?.sheet?.pop()
                }))
            }
            items.append(.section("إضافة ترجمة"))
            items.append(.action(title: "من ملف (.srt / .vtt)…", value: "", handler: { [weak self] in
                self?.pickSubtitleFile()
            }))
            if self.subChoices.isEmpty {
                items.append(.note("لا توجد ترجمة لهذا العنوان. يمكنك اختيار ملف ترجمة (.srt أو .vtt) من تطبيق «الملفات» ليُعرض على الشاشة، ويُحفظ توقيته لهذا الملف فقط."))
            }
            return items
        }
    }

    func delayPage() -> SheetPage {
        return SheetPage(title: "توقيت الترجمة") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            if self.subDoc == nil {
                items.append(.note("اختر ترجمة من ملف أو من الإنترنت أولاً. التوقيت يعمل مع ملفات الترجمة الخارجية."))
                return items
            }
            items.append(.info(title: "التوقيت الحالي", value: self.delayText(self.subDelay)))
            items.append(.section("تأخير النص (+)"))
            for d in [0.25, 0.5, 1.0, 2.0] {
                items.append(.action(title: "+" + String(format: "%g", d) + " ث", value: "", handler: { [weak self] in
                    self?.adjustSubtitleDelay(by: d)
                    self?.sheet?.reload()
                }))
            }
            items.append(.section("تبكير النص (−)"))
            for d in [0.25, 0.5, 1.0, 2.0] {
                items.append(.action(title: "\u{2212}" + String(format: "%g", d) + " ث", value: "", handler: { [weak self] in
                    self?.adjustSubtitleDelay(by: -d)
                    self?.sheet?.reload()
                }))
            }
            items.append(.action(title: "0 (متزامنة)", value: "", handler: { [weak self] in
                self?.resetSubtitleDelay()
                self?.sheet?.reload()
            }))
            items.append(.note("إذا ظهر النص قبل الكلام اختر «+». يُطبَّق فوراً ويُحفظ لهذا الملف فقط."))
            return items
        }
    }

    func subtitleSizePage() -> SheetPage {
        return SheetPage(title: "حجم الترجمة") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            for (i, n) in self.sizeNames().enumerated() {
                items.append(.choice(title: n, selected: PlayerPrefs.subtitleSize == i, handler: { [weak self] in
                    PlayerPrefs.subtitleSize = i
                    self?.lastSubText = "\u{0}"
                    self?.updateSubtitle(at: self?.lastPos ?? 0)
                    self?.sheet?.pop()
                }))
            }
            return items
        }
    }

    // MARK: - audio track

    func audioValue() -> String {
        guard let g = audioGroup, let it = item, let o = it.currentMediaSelection.selectedMediaOption(in: g) else { return "افتراضي" }
        return o.displayName
    }

    func audioPage() -> SheetPage {
        return SheetPage(title: "مسار الصوت") { [weak self] in
            guard let self = self else { return [] }
            guard let g = self.audioGroup, let it = self.item, g.options.count > 0 else {
                return [.note("فيه مسار صوت واحد فقط.")]
            }
            let current = it.currentMediaSelection.selectedMediaOption(in: g)
            var items: [SheetItem] = []
            for o in g.options {
                items.append(.choice(title: o.displayName, selected: current === o, handler: { [weak self] in
                    if let it = self?.item { it.select(o, in: g) }
                    self?.sheet?.pop()
                }))
            }
            return items
        }
    }

    // MARK: - smaller pages

    func seekStepPage() -> SheetPage {
        return SheetPage(title: "مدة التقديم والتأخير") { [weak self] in
            var items: [SheetItem] = []
            for v in [5, 10, 15, 30] {
                items.append(.choice(title: String(v) + " ثوانٍ", selected: PlayerPrefs.seekStep == v, handler: { [weak self] in
                    PlayerPrefs.seekStep = v
                    self?.sheet?.pop()
                }))
            }
            items.append(.note("تُستخدم عند الضغط المزدوج على جانبي الشاشة وفي أزرار التقديم والتأخير."))
            return items
        }
    }

    func sleepPage() -> SheetPage {
        return SheetPage(title: "مؤقت الإيقاف") { [weak self] in
            var items: [SheetItem] = []
            for m in [0, 15, 30, 45, 60, 90] {
                items.append(.choice(title: m == 0 ? "متوقف" : String(m) + " دقيقة", selected: PlayerPrefs.sleepMinutes == m, handler: { [weak self] in
                    PlayerPrefs.sleepMinutes = m
                    self?.sleepLeft = m > 0 && self?.isReady == true ? Double(m) * 60 : 0
                    self?.sheet?.pop()
                }))
            }
            items.append(.note("يُغلق المشغّل نفسه بعد المدة المختارة من التشغيل ويُحفظ موضعك، فيكمل العنوان من مكانه غداً. يعمل لليلة واحدة ثم يتوقف."))
            return items
        }
    }

    func sourcesPage() -> SheetPage {
        return SheetPage(title: "المصدر") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            for (i, s) in self.sources.enumerated() {
                let host = StreamLink.host(of: s)
                items.append(.choice(title: String(i + 1) + " · " + (host.isEmpty ? "مصدر" : host), selected: self.sourceIndex == i, handler: { [weak self] in
                    guard let self = self else { return }
                    self.sheet?.close(animated: false)
                    self.switchSource(i)
                }))
            }
            items.append(.action(title: "إعادة تحميل المصدر الحالي", value: "", handler: { [weak self] in
                guard let self = self else { return }
                self.sheet?.close(animated: false)
                if let r = self.resolved { EmbedResolver.shared.invalidate(page: r.page) }
                self.switchSource(self.sourceIndex)
            }))
            items.append(.note("إذا لم يعمل مصدر أو انقطع، جرّب مصدراً آخر لنفس العنوان. التطبيق يجرّب المصادر تلقائياً أيضاً."))
            return items
        }
    }

    func switchSource(_ i: Int) {
        guard i >= 0, i < sources.count else { return }
        sourceIndex = i
        incidentAttempt = 0
        nativeDeadline = Date().addingTimeInterval(32)
        errorPanel.isHidden = true
        wantsPlay = true
        userPaused = false
        startSource(resume: lastPos)
    }

    func infoPage() -> SheetPage {
        return SheetPage(title: "معلومات العرض") { [weak self] in
            guard let self = self else { return [] }
            var items: [SheetItem] = []
            let link = self.currentStream.hasPrefix("data:") ? (self.resolved?.page ?? "") : self.currentStream
            let host = StreamLink.host(of: link)
            items.append(.info(title: "الخادم", value: host.isEmpty ? "—" : host))
            if let it = self.item {
                let s = it.presentationSize
                if s.width > 0 {
                    let h = Int(s.height), w = Int(s.width)
                    items.append(.info(title: "الدقة", value: String(w) + "×" + String(h) + " (" + QualityLabel.label(height: h, width: w) + ")"))
                }
                if let ev = it.accessLog()?.events.last {
                    if ev.indicatedBitrate > 0 { items.append(.info(title: "معدل البث", value: String(Int(ev.indicatedBitrate / 1000)) + " kbps")) }
                    if ev.observedBitrate > 0 { items.append(.info(title: "السرعة الفعلية", value: String(Int(ev.observedBitrate / 1000)) + " kbps")) }
                    if ev.numberOfDroppedVideoFrames > 0 { items.append(.info(title: "إطارات مفقودة", value: String(ev.numberOfDroppedVideoFrames))) }
                }
            }
            let k = self.currentIsHLS ? "HLS" : (StreamLink.kind(of: self.currentStream)?.rawValue.uppercased() ?? "—")
            items.append(.info(title: "نوع البث", value: k))
            items.append(.info(title: "المدة", value: self.duration > 0 ? ClockText.format(self.duration) : "—"))
            items.append(.info(title: "المصدر", value: String(self.sourceIndex + 1) + " / " + String(max(1, self.sources.count))))
            if self.resolved != nil { items.append(.info(title: "طرق التشغيل المتاحة", value: String(self.resolved?.alternatives.count ?? 0))) }
            return items
        }
    }

    // MARK: - episodes

    func episodesPage() -> SheetPage {
        return SheetPage(title: "الحلقات") { [weak self] in
            guard let self = self, let cur = self.episode else { return [] }
            var items: [SheetItem] = []
            let seasons = self.show.seasons
            if seasons.count > 1 {
                items.append(.action(title: "الموسم", value: "الموسم " + String(cur.season), handler: { [weak self] in
                    guard let self = self else { return }
                    self.sheet?.push(self.seasonsPage())
                }))
            }
            items.append(.section("الموسم " + String(cur.season)))
            let list = self.show.episodes.filter { $0.season == cur.season }
            if list.isEmpty { items.append(.note("لا توجد حلقات في هذا الموسم")) }
            for e in list {
                var title = "الحلقة " + String(e.episode)
                if !e.title.isEmpty && e.title != self.show.title { title += " · " + e.title }
                let key = PlaybackStore.key(show: self.show, episode: e)
                if PlaybackStore.shared.isFinished(key) { title += "  ✓ تمت مشاهدتها" }
                else if PlaybackStore.shared.resume(key) > 0 { title += "  • قيد المشاهدة" }
                let selected = e.season == cur.season && e.episode == cur.episode
                items.append(.choice(title: title, selected: selected, handler: { [weak self] in
                    guard let self = self else { return }
                    if selected { self.sheet?.close(); return }
                    self.switchEpisode(e)
                }))
            }
            return items
        }
    }

    func seasonsPage() -> SheetPage {
        return SheetPage(title: "الموسم") { [weak self] in
            guard let self = self, let cur = self.episode else { return [] }
            var items: [SheetItem] = []
            for s in self.show.seasons {
                items.append(.choice(title: "الموسم " + String(s), selected: s == cur.season, handler: { [weak self] in
                    guard let self = self else { return }
                    if let first = self.show.episodes.first(where: { $0.season == s }) { self.switchEpisode(first) }
                }))
            }
            return items
        }
    }

    // MARK: - subtitle file from the Files app

    func pickSubtitleFile() {
        let types = ["public.text", "public.plain-text", "public.data", "org.matroska.subtitle"]
        let picker = UIDocumentPickerViewController(documentTypes: types, in: .import)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        sheet?.close(animated: false)
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        let name = u.deletingPathExtension().lastPathComponent
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let data = (try? Data(contentsOf: u)) ?? Data()
            DispatchQueue.main.async {
                if data.isEmpty || data.count > 5_000_000 {
                    self?.showStatus("تعذّر قراءة الملف", autoHide: 3)
                    return
                }
                self?.importSubtitle(data: data, name: name)
            }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {}
}
