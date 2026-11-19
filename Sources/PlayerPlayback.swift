import UIKit
import AVFoundation
import AVKit
import MediaPlayer

// The playback half of the native player: sources → resolver → AVPlayer, the repair ladder, subtitles, qualities,
// progress, episodes, lock screen controls, Picture in Picture and the browser fallback.

extension PlayerViewController {

    // MARK: - start of a film / an episode

    func start() {
        closing = false
        updateTitles()
        progressKey = PlaybackStore.key(show: show, episode: episode)
        speedKey = SmartPlayback.seriesKey(isSeries: show.isSeries, id: show.key, title: show.title)
        speed = SmartPlayback.speed(forKey: speedKey, fallback: PlayerPrefs.speed)
        sources = buildSources()
        sourceIndex = 0
        resolved = nil
        masterInfo = nil
        variants = []
        manualHeight = 0
        qualityCeiling = 0
        incidentAttempt = 0
        everPlayed = false
        wantsPlay = true
        userPaused = false
        lastPos = 0
        nativeDeadline = Date().addingTimeInterval(45)
        errorPanel.isHidden = true
        let d = SmartPlayback.decide(NetworkMonitor.shared.conditions())
        smartCap = d.maxHeight
        if !d.reason.isEmpty { showStatus(d.reason, autoHide: 4) }
        resetSubtitles()
        configureSkip()
        sleepLeft = 0
        let resume = PlaybackStore.shared.resume(progressKey)
        refreshChips()
        refreshEpisodeButtons()
        if resume > 0 { showResumeChip(resume) }
        startSource(resume: resume)
    }

    func updateTitles() {
        titleLabel.text = show.title
        if let e = episode {
            var t = "الموسم " + String(e.season) + " · الحلقة " + String(e.episode)
            if !e.title.isEmpty && e.title != show.title { t += " · " + e.title }
            episodeLabel.text = t
        } else {
            episodeLabel.text = ""
        }
        view.setNeedsLayout()
    }

    func refreshEpisodeButtons() {
        let series = episode != nil
        episodesButton.isHidden = !series
        let hasPrev = previousEpisode() != nil, hasNext = nextEpisode() != nil
        prevButton.isHidden = !series
        nextButton.isHidden = !series
        prevButton.isEnabled = hasPrev
        nextButton.isEnabled = hasNext
        prevButton.alpha = hasPrev ? 1 : 0.35
        nextButton.alpha = hasNext ? 1 : 0.35
        view.setNeedsLayout()
    }

    /// The published link first, then the same title on the other embed servers (TMDB id).
    func buildSources() -> [String] {
        var out: [String] = []
        let own = (episode?.video ?? (show.isSeries ? "" : show.head.video)).replacingOccurrences(of: "vidbolt.xyz", with: "vidbolt.pro")
        if own.hasPrefix("http") { out.append(own) }
        let tmdb = show.head.tmdb.isEmpty ? (episode?.tmdb ?? "") : show.head.tmdb
        if !tmdb.isEmpty {
            if let e = episode {
                for f in Backend.tvServers {
                    let u = String(format: f, tmdb, e.season, e.episode)
                    if !out.contains(u) { out.append(u) }
                }
            } else if !show.isSeries {
                for f in Backend.movieServers {
                    let u = String(format: f, tmdb)
                    if !out.contains(u) { out.append(u) }
                }
            }
        }
        return out
    }

    func previousEpisode() -> CatalogItem? {
        guard let e = episode, let i = show.episodes.firstIndex(where: { $0.season == e.season && $0.episode == e.episode }), i > 0 else { return nil }
        return show.episodes[i - 1]
    }

    func nextEpisode() -> CatalogItem? {
        guard let e = episode, let i = show.episodes.firstIndex(where: { $0.season == e.season && $0.episode == e.episode }),
              i + 1 < show.episodes.count else { return nil }
        return show.episodes[i + 1]
    }

    func switchEpisode(_ e: CatalogItem) {
        saveProgress()
        episode = e
        Library.shared.record(show, episode: e)
        sheet?.close(animated: false)
        start()
    }

    @objc func prevEpisodeTapped() { if let e = previousEpisode() { switchEpisode(e) } }
    @objc func nextEpisodeTapped() { playNextEpisode() }

    func playNextEpisode() {
        guard let e = nextEpisode() else { return }
        PlaybackStore.shared.markFinished(progressKey)
        switchEpisode(e)
    }

    // MARK: - loading a source

    func showLoading() {
        spinner.startAnimating()
        showStatus("جارٍ تجهيز الفيديو…")
        loadingTimer?.invalidate()
        loadingTimer = Timer.scheduledTimer(withTimeInterval: 8, repeats: false) { [weak self] _ in
            guard let self = self, !self.isReady else { return }
            self.webHint.isHidden = false
            UIView.animate(withDuration: 0.2) { self.webHint.alpha = 1 }
        }
    }

    func hideLoading() {
        loadingTimer?.invalidate()
        spinner.stopAnimating()
        showStatus(nil)
        UIView.animate(withDuration: 0.2, animations: { self.webHint.alpha = 0 }, completion: { _ in self.webHint.isHidden = true })
    }

    /// Stops everything that is going on for the current source (a resolver, timers, the item).
    func cancelWork() {
        embedJob?.cancel()
        embedJob = nil
        repairTimer?.invalidate()
        stallTimer?.invalidate()
        audioCheckTimer?.invalidate()
        detachItem()
    }

    func startSource(resume: Double) {
        cancelWork()
        loadGeneration += 1
        let gen = loadGeneration
        guard sourceIndex < sources.count else { allSourcesFailed(.missingStream); return }
        let link = sources[sourceIndex]
        errorPanel.isHidden = true
        pendingResume = resume
        masterInfo = nil
        variants = []
        showLoading()
        refreshChips()
        if PlaybackProblem.validate(link) == .invalidURL { sourceFailed(reason: "invalid link"); return }
        if let k = StreamLink.kind(of: link), !k.playableOnIOS { sourceFailed(reason: "unsupported format " + k.rawValue); return }
        if !NetworkMonitor.shared.isOnline {
            restartAfterNetwork = true
            hideLoading()
            showStatus(PlaybackProblem.offline.message + " — يبدأ التشغيل تلقائياً عند عودته")
            return
        }
        if StreamLink.kind(of: link) == nil && StreamLink.isEmbed(link) {
            let t: TimeInterval = sourceIndex == 0 ? 28 : 18
            embedJob = EmbedResolver.shared.resolve(page: link, host: view, timeout: t) { [weak self] stream, reason in
                guard let self = self, gen == self.loadGeneration, !self.closing else { return }
                self.embedJob = nil
                guard let s = stream else { self.sourceFailed(reason: reason); return }
                self.resolved = s
                self.triedStreams = []
                self.originalStream = s.url
                self.mergeSniffedSubtitles(s.subtitles)
                self.openStream(s.url, resume: self.pendingResume)
            }
        } else {
            resolved = nil
            triedStreams = []
            originalStream = link
            openStream(link, resume: resume)
        }
    }

    func sourceFailed(reason: String) {
        NSLog("CIGRAM_CHECK player source %d failed: %@", sourceIndex, reason)
        advanceSource(problem: PlaybackProblem.fromResolver(reason, online: NetworkMonitor.shared.isOnline))
    }

    func advanceSource(problem: PlaybackProblem) {
        let resumeAt = pendingResume > 0 ? pendingResume : lastPos
        if !NetworkMonitor.shared.isOnline {
            restartAfterNetwork = true
            pendingResume = resumeAt
            hideLoading()
            showStatus(PlaybackProblem.offline.message + " — يكمل التشغيل تلقائياً عند عودته")
            return
        }
        sourceIndex += 1
        if sourceIndex < sources.count && Date() < nativeDeadline {
            showStatus("المصدر غير متاح — جارٍ تجربة مصدر آخر…", autoHide: 3)
            startSource(resume: resumeAt)
            return
        }
        sourceIndex = max(0, min(sourceIndex, sources.count - 1))
        allSourcesFailed(problem)
    }

    /// No server gave a stream the native player could open: the browser player takes over (nothing was playing yet), or the
    /// viewer is told why and can retry / switch (the film was running and broke for good).
    func allSourcesFailed(_ problem: PlaybackProblem) {
        hideLoading()
        if !everPlayed && !inWebFallback {
            openWebPlayer(auto: true)
            return
        }
        showError(problem.message)
    }

    func showError(_ text: String) {
        hideLoading()
        errorLabel.text = text
        errorPanel.isHidden = false
        view.bringSubviewToFront(errorPanel)
        controls.alpha = 0
        controlsVisible = false
        player.pause()
        refreshPlayButton()
    }

    @objc func retryTapped() {
        errorPanel.isHidden = true
        sourceIndex = 0
        incidentAttempt = 0
        nativeDeadline = Date().addingTimeInterval(45)
        wantsPlay = true
        userPaused = false
        if let r = resolved { EmbedResolver.shared.invalidate(page: r.page) }
        for s in sources { EmbedResolver.shared.invalidate(page: s) }
        startSource(resume: lastPos)
        showControls()
    }

    @objc func webTapped() { openWebPlayer(auto: false) }

    func openWebPlayer(auto: Bool) {
        if inWebFallback { return }
        inWebFallback = true
        saveProgress()
        player.pause()
        cancelWork()
        hideLoading()
        sheet?.close(animated: false)
        if auto { NSLog("CIGRAM_CHECK player: no stream from any server, opening the browser player") }
        let w = WebPlayerViewController(show: show, episode: episode)
        w.modalPresentationStyle = .fullScreen
        w.onClose = { [weak self] in self?.closeNow(animated: false) }
        present(w, animated: true)
    }

    // MARK: - opening a stream in AVPlayer

    func openStream(_ stream: String, resume: Double) {
        detachItem()
        currentStream = stream
        triedStreams.insert(StreamLink.clean(stream))
        guard let asset = PlayerAsset.make(stream) else { sourceFailed(reason: "bad link"); return }
        let it = AVPlayerItem(asset: asset)
        it.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithm.spectral
        applyQualityLimits(to: it)
        let out = AVPlayerItemLegibleOutput()
        out.suppressesPlayerRendering = true
        out.setDelegate(self, queue: DispatchQueue.main)
        it.add(out)
        legibleOutput = out
        item = it
        isReady = false
        pendingResume = resume
        observe(it)
        player.replaceCurrentItem(with: it)
        if everPlayed || resume > 0 { spinner.startAnimating() }
    }

    func observe(_ it: AVPlayerItem) {
        observations = []
        observations.append(it.observe(\.status, options: [.new]) { [weak self] i, _ in
            DispatchQueue.main.async { self?.itemStatusChanged(i) }
        })
        observations.append(it.observe(\.loadedTimeRanges, options: [.new]) { [weak self] i, _ in
            DispatchQueue.main.async { self?.updateBuffered(i) }
        })
        observations.append(it.observe(\.presentationSize, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshChips() }
        })
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.timeControlChanged() }
        })
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(itemEnded(_:)), name: NSNotification.Name.AVPlayerItemDidPlayToEndTime, object: it)
        nc.addObserver(self, selector: #selector(itemFailedToEnd(_:)), name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime, object: it)
    }

    func detachItem() {
        observations = []
        if let it = item {
            let nc = NotificationCenter.default
            nc.removeObserver(self, name: NSNotification.Name.AVPlayerItemDidPlayToEndTime, object: it)
            nc.removeObserver(self, name: NSNotification.Name.AVPlayerItemFailedToPlayToEndTime, object: it)
        }
        item = nil
        legibleOutput = nil
        isReady = false
        bufferedEnd = 0
        player.replaceCurrentItem(with: nil)
    }

    func itemStatusChanged(_ i: AVPlayerItem) {
        guard i === item else { return }
        if i.status == .readyToPlay { becameReady() }
        else if i.status == .failed { itemFailed(i) }
    }

    func becameReady() {
        guard let it = item, !isReady else { return }
        isReady = true
        let d = it.duration.seconds
        duration = d.isFinite && d > 0 ? d : 0
        seekBar.duration = duration
        loadSelectionGroups(it)
        fetchQualities()
        applyQualityLimits(to: it)
        let target = pendingResume
        pendingResume = 0
        if target > 1 && duration > 0 && target < duration - 5 {
            it.seek(to: CMTime(seconds: target, preferredTimescale: 600), completionHandler: { [weak self] _ in
                DispatchQueue.main.async {
                    guard let self = self, self.item === it else { return }
                    self.beginPlayback()
                }
            })
        } else {
            beginPlayback()
        }
        updateNowPlaying()
    }

    func beginPlayback() {
        hideLoading()
        errorPanel.isHidden = true
        if wantsPlay { startPlay() }
        let m = PlayerPrefs.sleepMinutes
        if m > 0 && sleepLeft <= 0 { sleepLeft = Double(m) * 60 }
        refreshPlayButton()
        audioCheckTimer?.invalidate()
        audioCheckTimer = Timer.scheduledTimer(withTimeInterval: 7, repeats: false) { [weak self] _ in self?.checkAudio() }
        refreshChips()
    }

    func startPlay() {
        player.play()
        if speed != 1 { player.rate = speed }
    }

    func applySpeed() {
        if player.rate > 0.01 { player.rate = speed }
    }

    func updateBuffered(_ i: AVPlayerItem) {
        guard i === item else { return }
        let t = player.currentTime().seconds
        var end = 0.0
        for v in i.loadedTimeRanges {
            let r = v.timeRangeValue
            let a = r.start.seconds, b = CMTimeRangeGetEnd(r).seconds
            if a.isFinite && b.isFinite && a <= t + 0.5 && b >= t { end = max(end, b) }
        }
        bufferedEnd = end
        seekBar.buffered = end
    }

    func timeControlChanged() {
        switch player.timeControlStatus {
        case .playing:
            everPlayed = true
            stallTimer?.invalidate()
            spinner.stopAnimating()
            if playingSince == nil { playingSince = Date() }
            hideLoading()
        case .waitingToPlayAtSpecifiedRate:
            playingSince = nil
            if isReady && wantsPlay {
                spinner.startAnimating()
                startStallTimer()
            }
        case .paused:
            stallTimer?.invalidate()
            playingSince = nil
            spinner.stopAnimating()
        @unknown default:
            break
        }
        refreshPlayButton()
        updateNowPlayingRate()
    }

    /// A stream that waits 15 s with nothing arriving is an incident of its own (CigramRepairPlan.STALL).
    func startStallTimer() {
        stallTimer?.invalidate()
        stallTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            guard let self = self, self.isReady, self.wantsPlay, self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate else { return }
            NSLog("CIGRAM_CHECK player stalled for 15 s")
            self.repairIncident(kind: .stall, http: 0)
        }
    }

    // MARK: - time (10 times a second)

    func addTimeObserver() {
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: DispatchQueue.main) { [weak self] t in
            self?.timeTick(t.seconds)
        }
    }

    func currentSeconds() -> Double {
        let t = player.currentTime().seconds
        return t.isFinite && t >= 0 ? t : lastPos
    }

    func timeTick(_ t: Double) {
        guard t.isFinite, isReady else { return }
        lastPos = t
        updateSubtitle(at: t)
        let now = CACurrentMediaTime()
        if now - lastSlowTick < 0.25 { return }
        let dt = min(1, now - lastSlowTick)
        lastSlowTick = now
        if duration <= 0, let it = item {
            let d = it.duration.seconds
            if d.isFinite && d > 0 { duration = d; seekBar.duration = d }
        }
        if !seekBar.isScrubbing {
            seekBar.position = t
            timeLabel.text = ClockText.format(t)
            leftLabel.text = duration > 0 ? "-" + ClockText.format(max(0, duration - t)) : ""
        }
        let playing = player.rate > 0.01
        // the repair ladder starts again from the top after 8 s of steady playing
        if let since = playingSince, Date().timeIntervalSince(since) > 8, incidentAttempt > 0 {
            incidentAttempt = 0
            showStatus(nil)
        }
        // «موقّت النوم»: only counts while the player is on screen
        if sleepLeft > 0 && playing && UIApplication.shared.applicationState == .active {
            sleepLeft -= dt
            if sleepLeft <= 0 { sleepFired() }
        }
        // the admin's scenes to skip
        if PlayerPrefs.skipScenes && playing && now - lastSceneSkip > 3 {
            for seg in sceneSegments where t >= seg.start && t < seg.end - 0.5 {
                lastSceneSkip = now
                seek(to: seg.end)
                showHUD("تم تخطي مشهد")
                hideHUD(after: 1.1)
                break
            }
        }
        let st = skipEngine.tick(pos: t, dur: duration, playing: playing, dragging: seekBar.isScrubbing, isSeries: episode != nil,
                                 hasNext: nextEpisode() != nil)
        applySkipState(st)
        if Date().timeIntervalSince(lastSave) > 5 { saveProgress() }
        if Int(now) % 5 == 0 { updateNowPlayingElapsed() }
    }

    // MARK: - seeking / play / pause

    func seek(to seconds: Double) {
        guard player.currentItem != nil else { return }
        var s = max(0, seconds)
        if duration > 0 { s = min(s, max(0, duration - 0.5)) }
        lastPos = s
        seekBar.position = s
        timeLabel.text = ClockText.format(s)
        leftLabel.text = duration > 0 ? "-" + ClockText.format(max(0, duration - s)) : ""
        let t = CMTime(seconds: s, preferredTimescale: 600)
        player.seek(to: t, toleranceBefore: CMTime.zero, toleranceAfter: CMTime.zero) { [weak self] _ in
            DispatchQueue.main.async { self?.updateNowPlayingElapsed() }
        }
    }

    func skip(by delta: Double) {
        seek(to: currentSeconds() + delta)
        flashSeek(forward: delta > 0, seconds: Int(abs(delta)))
        if controlsVisible { showControls() }
    }

    @objc func backTapped() { skip(by: -Double(PlayerPrefs.seekStep)); showControls() }
    @objc func forwardTapped() { skip(by: Double(PlayerPrefs.seekStep)); showControls() }
    @objc func playTapped() { togglePlay(); showControls() }

    func togglePlay() {
        if wantsPlay && (player.rate > 0.01 || player.timeControlStatus == .waitingToPlayAtSpecifiedRate) { userPause() } else { userPlay() }
    }

    func userPause() {
        userPaused = true
        wantsPlay = false
        player.pause()
        refreshPlayButton()
        showControls(autoHide: false)
        saveProgress()
    }

    func userPlay() {
        userPaused = false
        wantsPlay = true
        if duration > 0 && currentSeconds() >= duration - 1 { seek(to: 0) }
        if isReady { startPlay() }
        refreshPlayButton()
        if controlsVisible { scheduleHide() }
    }

    @objc func skipTapped() {
        guard skipTarget > 0 else { return }
        skipEngine.skipped()
        seek(to: skipTarget)
        setVisible(skipButton, false)
    }

    @objc func nextNowTapped() { setVisible(nextCard, false); playNextEpisode() }

    @objc func nextCancelTapped() {
        skipEngine.dismissNext()
        setVisible(nextCard, false)
    }

    @objc func startOverTapped() {
        setVisible(resumeChip, false)
        pendingResume = 0
        if isReady { seek(to: 0) }
    }

    func setVisible(_ v: UIView, _ on: Bool) {
        if on {
            if !v.isHidden && v.alpha > 0.99 { return }
            v.isHidden = false
            UIView.animate(withDuration: 0.2) { v.alpha = 1 }
        } else {
            if v.isHidden { return }
            UIView.animate(withDuration: 0.2, animations: { v.alpha = 0 }, completion: { _ in v.isHidden = true })
        }
    }

    func showResumeChip(_ seconds: Double) {
        resumeLabel.text = "استئناف من " + ClockText.format(seconds)
        view.bringSubviewToFront(resumeChip)
        setVisible(resumeChip, true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self = self else { return }
            self.setVisible(self.resumeChip, false)
        }
    }

    func configureSkip() {
        sceneSegments = []
        guard let e = episode else {
            skipEngine.reset(key: "", introStart: -1, introEnd: -1, credits: -1)
            let film = ClockText.segments(show.head.skipSegments)
            sceneSegments = film
            seekBar.marks = film
            return
        }
        var s = e.introStart, en = e.introEnd, cr = e.creditsStart
        if en <= 0 { s = show.head.introStart; en = show.head.introEnd }
        if cr <= 0 { cr = show.head.creditsStart }
        skipEngine.reset(key: show.key + "|" + String(max(1, e.season)), introStart: s, introEnd: en, credits: cr)
        sceneSegments = ClockText.segments(e.skipSegments)
        seekBar.marks = skipEngine.marks + sceneSegments
        setVisible(skipButton, false)
        setVisible(nextCard, false)
    }

    func applySkipState(_ st: SkipIntroEngine.State) {
        if st.autoSkip {
            skipEngine.skipped()
            seek(to: st.skipTarget)
            showHUD("تم تخطي المقدمة")
            hideHUD(after: 1.2)
            return
        }
        skipTarget = st.skipTarget
        setVisible(skipButton, st.showSkip && !st.showNext)
        if st.showNext {
            nextCountdown.text = st.autoNext ? "تبدأ خلال " + String(st.countdownLeft) : "الحلقة " + String((nextEpisode()?.episode) ?? 0)
            view.bringSubviewToFront(nextCard)
            setVisible(nextCard, true)
        } else {
            setVisible(nextCard, false)
        }
        if st.goNext { playNextEpisode() }
    }

    func sleepFired() {
        PlayerPrefs.sleepMinutes = 0
        sleepLeft = 0
        saveProgress()
        showHUD("انتهى موقّت النوم · تصبح على خير")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in self?.closeNow(animated: true) }
    }

    // MARK: - saving where the viewer stopped

    func saveProgress() {
        lastSave = Date()
        guard isReady, duration > 0, !progressKey.isEmpty, lastPos > 0 else { return }
        PlaybackStore.shared.save(progressKey, pos: lastPos, dur: duration)
    }

    @objc func itemEnded(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === item else { return }
        PlaybackStore.shared.markFinished(progressKey)
        if episode != nil, nextEpisode() != nil, PlayerPrefs.autoNext {
            playNextEpisode()
            return
        }
        // the end of a film / the last episode: the last frame stays, play starts it again
        wantsPlay = false
        userPaused = true
        refreshPlayButton()
        showControls(autoHide: false)
    }

    @objc func itemFailedToEnd(_ n: Notification) {
        guard (n.object as? AVPlayerItem) === item else { return }
        let e = n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
        handleFailure(domain: e?.domain ?? "", code: e?.code ?? 0, http: item.map { httpStatus($0) } ?? 0)
    }

    // MARK: - the repair ladder (CigramAutoRepair)

    func itemFailed(_ it: AVPlayerItem) {
        guard it === item else { return }
        let ns = it.error as NSError?
        var domain = ns?.domain ?? ""
        var code = ns?.code ?? 0
        if let u = ns?.userInfo[NSUnderlyingErrorKey] as? NSError, u.domain == NSURLErrorDomain || u.domain == "CoreMediaErrorDomain" {
            domain = u.domain
            code = u.code
        }
        handleFailure(domain: domain, code: code, http: httpStatus(it))
    }

    func httpStatus(_ it: AVPlayerItem) -> Int {
        guard let events = it.errorLog()?.events else { return 0 }
        for e in events.reversed() where e.errorStatusCode >= 400 && e.errorStatusCode < 600 { return e.errorStatusCode }
        return 0
    }

    func handleFailure(domain: String, code: Int, http: Int) {
        NSLog("CIGRAM_CHECK player failed: %@ %d http=%d", domain, code, http)
        let kind = RepairPlan.kind(domain: domain, code: code, http: http)
        repairIncident(kind: kind, http: http)
    }

    var currentIsHLS: Bool {
        return StreamLink.kind(of: currentStream) == .hls || currentStream.hasPrefix("data:application/vnd.apple.mpegurl")
    }

    func repairIncident(kind: RepairPlan.Kind, http: Int) {
        repairTimer?.invalidate()
        stallTimer?.invalidate()
        let online = NetworkMonitor.shared.isOnline
        let canLower = currentIsHLS && RepairPlan.lowerCeiling(qualityCeiling) > 0
        let action = RepairPlan.next(kind: kind, attempt: incidentAttempt, online: online, httpStatus: http,
                                     hasSeparateSource: resolved != nil, canLowerQuality: canLower)
        switch action {
        case .waitNetwork:
            waitingForNetwork = true
            pendingRepair = (kind: kind, http: http)
            showStatus(RepairPlan.statusText(action, attempt: incidentAttempt))
            return
        case .giveUp:
            giveUp(kind: kind, http: http)
            return
        default:
            break
        }
        showStatus(RepairPlan.statusText(action, attempt: incidentAttempt))
        spinner.startAnimating()
        let attempt = incidentAttempt
        incidentAttempt += 1
        let pos = lastPos
        let delay = RepairPlan.delay(kind: kind, attempt: attempt, httpStatus: http)
        repairTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            self?.performRepair(action, at: pos)
        }
    }

    func performRepair(_ action: RepairPlan.Action, at pos: Double) {
        guard !closing, !inWebFallback else { return }
        switch action {
        case .reopen, .liveEdge:
            openStream(currentStream, resume: pos)
        case .reopenSource:
            if let r = resolved { EmbedResolver.shared.invalidate(page: r.page) }
            else if sourceIndex < sources.count { EmbedResolver.shared.invalidate(page: sources[sourceIndex]) }
            startSource(resume: pos)
        case .lowerQualityReopen:
            qualityCeiling = RepairPlan.lowerCeiling(qualityCeiling)
            openStream(currentStream, resume: pos)
        default:
            break
        }
    }

    /// The ladder is exhausted for this stream: another way to play the same title, then another server, then the truth.
    func giveUp(kind: RepairPlan.Kind, http: Int) {
        let pos = lastPos
        if let r = resolved, let alt = r.alternatives.first(where: { !triedStreams.contains(StreamLink.clean($0)) }) {
            incidentAttempt = 0
            showStatus("تجربة طريقة تشغيل أخرى…", autoHide: 3)
            openStream(alt, resume: pos)
            return
        }
        if sourceIndex + 1 < sources.count {
            sourceIndex += 1
            incidentAttempt = 0
            showStatus("جارٍ تجربة مصدر آخر…", autoHide: 3)
            startSource(resume: pos)
            return
        }
        if !everPlayed && !inWebFallback {
            openWebPlayer(auto: true)
            return
        }
        showError(RepairPlan.giveUpText(kind, httpStatus: http))
    }

    /// After 7 s: a stream that has a picture but no sound is replaced by the next way to play the same title.
    func checkAudio() {
        guard let it = item, isReady, player.rate > 0.01, let r = resolved else { return }
        if it.tracks.isEmpty { return }
        var hasAudio = false
        for t in it.tracks {
            let at: AVAssetTrack? = t.assetTrack
            if let m = at?.mediaType, m == AVMediaType.audio { hasAudio = true }
        }
        if hasAudio { return }
        guard let alt = r.alternatives.first(where: { !triedStreams.contains(StreamLink.clean($0)) }) else { return }
        NSLog("CIGRAM_CHECK player: no sound in this stream, trying the next way to play it")
        showStatus("لم يُعثر على صوت — تجربة طريقة أخرى…", autoHide: 3)
        openStream(alt, resume: lastPos)
    }

    @objc func networkChanged() {
        guard NetworkMonitor.shared.isOnline else { return }
        if restartAfterNetwork {
            restartAfterNetwork = false
            showStatus(nil)
            startSource(resume: pendingResume > 0 ? pendingResume : lastPos)
            return
        }
        if waitingForNetwork, let p = pendingRepair {
            waitingForNetwork = false
            pendingRepair = nil
            repairIncident(kind: p.kind, http: p.http)
        }
    }

    // MARK: - interruptions (Siri, a call, the headphones)

    @objc func audioInterrupted(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw), type == .ended else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.resumeAfterInterruption() }
    }

    @objc func becameActive() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in self?.resumeAfterInterruption() }
    }

    /// A pause the viewer did not make is undone when the interruption ends and when the app comes back to the front.
    func resumeAfterInterruption() {
        guard wantsPlay, !userPaused, !inWebFallback, !closing, isReady, player.timeControlStatus == .paused else { return }
        DispatchQueue.global(qos: .userInitiated).async { try? AVAudioSession.sharedInstance().setActive(true) }
        startPlay()
    }

    @objc func routeChanged(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: raw), reason == .oldDeviceUnavailable else { return }
        DispatchQueue.main.async { [weak self] in self?.userPause() }      // the headphones came out: stop, like every player
    }

    // MARK: - qualities

    func fetchQualities() {
        if masterInfo != nil { return }
        let stream = originalStream
        let k = StreamLink.kind(of: stream)
        guard k == .hls || k == nil || stream.hasPrefix("data:application/vnd.apple.mpegurl") else { refreshChips(); return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let m = PlayerAsset.masterText(stream)
            DispatchQueue.main.async {
                guard let self = self, self.originalStream == stream else { return }
                if let m = m {
                    self.masterInfo = m
                    self.variants = HLS.variants(master: m.text, base: m.base)
                }
                self.refreshChips()
                if self.sheet?.isOpen == true { self.sheet?.reload() }
            }
        }
    }

    /// 4K / 2K / 1080p / 720p only (the app names no smaller quality), the best bandwidth of each.
    func offeredQualities() -> [(height: Int, variant: HLSVariant)] {
        var best: [Int: HLSVariant] = [:]
        for v in variants {
            guard QualityLabel.offered(height: v.height, width: v.width) else { continue }
            let h = QualityLabel.classify(height: v.height, width: v.width)
            if let old = best[h], old.bandwidth >= v.bandwidth { continue }
            best[h] = v
        }
        return best.keys.sorted(by: >).map { (height: $0, variant: best[$0]!) }
    }

    /// 0 = automatic. A fixed quality plays a copy of the master playlist that holds only that quality.
    func chooseQuality(_ height: Int) {
        let pos = lastPos
        manualHeight = height
        if height == 0 {
            currentStream = originalStream
        } else if let m = masterInfo, let pick = offeredQualities().first(where: { $0.height == height })?.variant {
            let text = HLS.filteredMaster(m.text, base: m.base, keepURL: pick.url)
            var suffix = ""
            if let bar = originalStream.firstIndex(of: "|") { suffix = String(originalStream[bar...]) }
            currentStream = StreamSniffer.dataPlaylist(text) + suffix
        } else {
            manualHeight = 0
            return
        }
        showStatus("تغيير الجودة…", autoHide: 2)
        openStream(currentStream, resume: pos)
        refreshChips()
    }

    /// The smart cap (battery, heat, data saver) and the repair ceiling limit the AUTOMATIC quality; a quality the viewer picks
    /// by hand wins over the smart cap (not over the repair ceiling: a decoder that gave up cannot be forced).
    func applyQualityLimits(to it: AVPlayerItem) {
        var cap = manualHeight > 0 ? manualHeight : smartCap
        if qualityCeiling > 0 { cap = cap > 0 ? min(cap, qualityCeiling) : qualityCeiling }
        if cap > 0 { it.preferredMaximumResolution = CGSize(width: CGFloat(cap) * 2.4, height: CGFloat(cap)) }
        else { it.preferredMaximumResolution = CGSize.zero }
    }

    // MARK: - subtitles and audio tracks

    static func hashKey(_ s: String) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return String(h, radix: 16)
    }

    func resetSubtitles() {
        subChoices = []
        subSelected = -1
        subDoc = nil
        subDelay = 0
        embeddedText = ""
        lastSubText = "\u{0}"
        legibleGroup = nil
        audioGroup = nil
        setSubtitleText("")
        // the admin's own subtitle file of this film / episode
        var url = ""
        if let e = episode, !e.subtitle.isEmpty { url = e.subtitle } else if !show.isSeries && !show.head.subtitle.isEmpty { url = show.head.subtitle }
        if url.hasPrefix("http") {
            subChoices.append(SubChoice(title: "العربية", kind: .external(url)))
            if PlayerPrefs.subtitlesOn { selectSubtitle(0) }
        }
    }

    /// Subtitle files the embed page itself asked for (.vtt / .srt).
    func mergeSniffedSubtitles(_ subs: [SniffedSubtitle]) {
        var added = false
        for s in subs {
            var exists = false
            for c in subChoices {
                if case .external(let u) = c.kind, u == s.url { exists = true }
            }
            if exists { continue }
            subChoices.append(SubChoice(title: s.label, kind: .external(s.url)))
            added = true
        }
        if added && subSelected < 0 && PlayerPrefs.subtitlesOn {
            if let i = subChoices.firstIndex(where: { $0.title == "العربية" }) { selectSubtitle(i) }
        }
        refreshChips()
    }

    func loadSelectionGroups(_ it: AVPlayerItem) {
        let asset = it.asset
        asset.loadValuesAsynchronously(forKeys: ["availableMediaCharacteristicsWithMediaSelectionOptions"]) { [weak self] in
            DispatchQueue.main.async {
                guard let self = self, self.item === it else { return }
                self.legibleGroup = asset.mediaSelectionGroup(forMediaCharacteristic: AVMediaCharacteristic.legible)
                self.audioGroup = asset.mediaSelectionGroup(forMediaCharacteristic: AVMediaCharacteristic.audible)
                self.addEmbeddedSubtitleChoices()
                if self.sheet?.isOpen == true { self.sheet?.reload() }
            }
        }
    }

    func addEmbeddedSubtitleChoices() {
        guard let g = legibleGroup else { return }
        // the first run after a reopen keeps the choices already there
        var kept: [SubChoice] = []
        for c in subChoices {
            if case .embedded = c.kind { continue }
            kept.append(c)
        }
        subChoices = kept
        let selectedExternal = subSelected
        for o in g.options {
            subChoices.append(SubChoice(title: o.displayName, kind: .embedded(o)))
        }
        subSelected = selectedExternal
        if subSelected < 0 && PlayerPrefs.subtitlesOn {
            var arabic = -1
            for (i, c) in subChoices.enumerated() {
                if case .embedded(let o) = c.kind, o.locale?.languageCode == "ar", arabic < 0 { arabic = i }
            }
            if arabic >= 0 { selectSubtitle(arabic) }
        }
        refreshChips()
    }

    func selectSubtitle(_ index: Int) {
        guard index >= 0, index < subChoices.count else { disableSubtitles(); return }
        subSelected = index
        PlayerPrefs.subtitlesOn = true
        lastSubText = "\u{0}"
        switch subChoices[index].kind {
        case .external(let u):
            clearEmbedded()
            loadExternalSubtitle(u, index: index)
        case .embedded(let o):
            subDoc = nil
            embeddedText = ""
            if let g = legibleGroup, let it = item { it.select(o, in: g) }
            setSubtitleText("")
        }
        refreshChips()
    }

    func disableSubtitles() {
        subSelected = -1
        subDoc = nil
        PlayerPrefs.subtitlesOn = false
        clearEmbedded()
        setSubtitleText("")
        lastSubText = "\u{0}"
        refreshChips()
    }

    func clearEmbedded() {
        if let g = legibleGroup, let it = item { it.select(nil, in: g) }
        embeddedText = ""
    }

    func loadExternalSubtitle(_ url: String, index: Int) {
        if let cached = subCache[url] { applySubtitleDocument(cached); return }
        showStatus("جارٍ تحميل الترجمة…", autoHide: 2)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var doc: SubtitleDocument?
            if let r = StreamNet.get(url, maxBytes: 3_000_000, timeout: 15), r.status >= 200, r.status < 300 {
                let parsed = SubtitleParser.parse(SubtitleParser.decode(r.data), key: PlayerViewController.hashKey(url))
                if !parsed.cues.isEmpty { doc = parsed }
            }
            DispatchQueue.main.async {
                guard let self = self, self.subSelected == index else { return }
                if let d = doc {
                    self.subCache[url] = d
                    self.applySubtitleDocument(d)
                } else {
                    self.showStatus("تعذّر تحميل الترجمة", autoHide: 3)
                }
            }
        }
    }

    func applySubtitleDocument(_ d: SubtitleDocument) {
        subDoc = d
        subDelay = PlayerPrefs.subtitleDelay(forKey: d.key)
        lastSubText = "\u{0}"
        updateSubtitle(at: lastPos)
    }

    /// A subtitle file the viewer picked from the Files app.
    func importSubtitle(data: Data, name: String) {
        let key = "file:" + PlayerViewController.hashKey(name + String(data.count))
        let doc = SubtitleParser.parse(SubtitleParser.decode(data), key: key)
        if doc.cues.isEmpty {
            showStatus("هذا الملف لا يحتوي ترجمة مقروءة", autoHide: 3)
            return
        }
        subCache[key] = doc
        subChoices.append(SubChoice(title: name.count > 20 ? String(name.prefix(19)) + "…" : name, kind: .external(key)))
        selectSubtitle(subChoices.count - 1)
    }

    func updateSubtitle(at t: Double) {
        var text = ""
        if let d = subDoc {
            text = d.text(at: t - subDelay)
        } else if subSelected >= 0 && subSelected < subChoices.count, case .embedded = subChoices[subSelected].kind {
            text = embeddedText
        }
        if text != lastSubText {
            lastSubText = text
            setSubtitleText(text)
        }
    }

    func setSubtitleText(_ t: String) {
        if t.isEmpty || !PlayerPrefs.subtitlesOn {
            subtitleLabel.isHidden = true
            subtitleLabel.attributedText = nil
            return
        }
        let sizes: [CGFloat] = [16, 20, 25, 31]
        let size = sizes[min(3, max(0, PlayerPrefs.subtitleSize))] * (isPortrait ? 0.8 : 1)
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key.font: Theme.font(size, .semibold),
            NSAttributedString.Key.foregroundColor: UIColor.white,
            NSAttributedString.Key.strokeColor: UIColor.black,
            NSAttributedString.Key.strokeWidth: -3.0,
            NSAttributedString.Key.paragraphStyle: style,
        ]
        subtitleLabel.attributedText = NSAttributedString(string: t, attributes: attrs)
        subtitleLabel.isHidden = false
        layoutSubtitle()
    }

    /// Text that an embedded (HLS) subtitle track delivers.
    func legibleOutput(_ output: AVPlayerItemLegibleOutput, didOutputAttributedStrings strings: [NSAttributedString],
                       nativeSampleBuffers nativeSamples: [Any], forItemTime itemTime: CMTime) {
        embeddedText = strings.map { $0.string }.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        if subSelected >= 0 && subSelected < subChoices.count, case .embedded = subChoices[subSelected].kind {
            if embeddedText != lastSubText {
                lastSubText = embeddedText
                setSubtitleText(embeddedText)
            }
        }
    }

    func adjustSubtitleDelay(by d: Double) {
        guard let doc = subDoc else { return }
        subDelay = (subDelay + d).rounded(toPlaces: 2)
        PlayerPrefs.setSubtitleDelay(subDelay, forKey: doc.key)
        lastSubText = "\u{0}"
        updateSubtitle(at: lastPos)
    }

    func resetSubtitleDelay() {
        guard let doc = subDoc else { return }
        subDelay = 0
        PlayerPrefs.setSubtitleDelay(0, forKey: doc.key)
        lastSubText = "\u{0}"
        updateSubtitle(at: lastPos)
    }

    // MARK: - lock screen / Control Center

    func setupRemoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        func add(_ cmd: MPRemoteCommand, _ h: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) {
            cmd.isEnabled = true
            remoteTargets.append((cmd, cmd.addTarget(handler: h)))
        }
        add(c.playCommand) { [weak self] _ in self?.userPlay(); return .success }
        add(c.pauseCommand) { [weak self] _ in self?.userPause(); return .success }
        add(c.togglePlayPauseCommand) { [weak self] _ in self?.togglePlay(); return .success }
        c.skipForwardCommand.preferredIntervals = [NSNumber(value: PlayerPrefs.seekStep)]
        c.skipBackwardCommand.preferredIntervals = [NSNumber(value: PlayerPrefs.seekStep)]
        add(c.skipForwardCommand) { [weak self] e in
            let v = (e as? MPSkipIntervalCommandEvent)?.interval ?? 10
            self?.skip(by: v)
            return .success
        }
        add(c.skipBackwardCommand) { [weak self] e in
            let v = (e as? MPSkipIntervalCommandEvent)?.interval ?? 10
            self?.skip(by: -v)
            return .success
        }
        add(c.changePlaybackPositionCommand) { [weak self] e in
            if let p = e as? MPChangePlaybackPositionCommandEvent { self?.seek(to: p.positionTime) }
            return .success
        }
        add(c.nextTrackCommand) { [weak self] _ in
            guard let self = self, self.nextEpisode() != nil else { return .noActionableNowPlayingItem }
            self.playNextEpisode()
            return .success
        }
        add(c.previousTrackCommand) { [weak self] _ in
            guard let self = self, let e = self.previousEpisode() else { return .noActionableNowPlayingItem }
            self.switchEpisode(e)
            return .success
        }
    }

    func updateNowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: show.title,
            MPNowPlayingInfoPropertyPlaybackRate: Double(player.rate),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: lastPos,
        ]
        if let e = episode { info[MPMediaItemPropertyArtist] = "الموسم " + String(e.season) + " · الحلقة " + String(e.episode) }
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let img = ImageLoader.shared.cached(show.posterURL) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func updateNowPlayingRate() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyPlaybackRate] = Double(player.rate)
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = lastPos
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func updateNowPlayingElapsed() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = lastPos
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Picture in Picture

    func setupPictureInPicture() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        pip = AVPictureInPictureController(playerLayer: surface.playerLayer)
        pip?.delegate = self
        if #available(iOS 14.2, *) { pip?.canStartPictureInPictureAutomaticallyFromInline = true }
    }

    @objc func pipTapped() {
        guard let p = pip else { return }
        if p.isPictureInPictureActive { p.stopPictureInPicture() } else { p.startPictureInPicture() }
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true)
    }

    // MARK: - closing

    @objc func closeTapped() { closeNow(animated: true) }

    func closeNow(animated: Bool) {
        if closing { return }
        closing = true
        saveProgress()
        cancelWork()
        player.pause()
        if let t = timeObserver {
            player.removeTimeObserver(t)
            timeObserver = nil
        }
        for (cmd, token) in remoteTargets { cmd.removeTarget(token) }
        remoteTargets = []
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        if brightnessTouched { UIScreen.main.brightness = originalBrightness }
        UIApplication.shared.isIdleTimerDisabled = false
        sheet?.close(animated: false)
        dismiss(animated: animated)
    }
}

private extension Double {
    func rounded(toPlaces p: Int) -> Double {
        let m = pow(10.0, Double(p))
        return (self * m).rounded() / m
    }
}
