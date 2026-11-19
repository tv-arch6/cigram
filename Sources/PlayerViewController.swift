import UIKit
import AVFoundation
import AVKit
import MediaPlayer

/// THE PLAYER. A native player (AVPlayer) with the same behaviour as the Android ExoPlayer player (PlayerActivity):
///  - an embed link (vidbolt, ...) is turned into the real stream first (EmbedResolver) and played here, with its own
///    controls: double-tap ±10 s, drag to seek, speed, quality, subtitles with a delay, audio tracks, sleep timer, skip intro,
///    next-episode card, resume, episode list, Picture in Picture, AirPlay (the Android «cast» button), lock screen controls;
///  - a broken playback is repaired in place (CigramRepairPlan: reopen / renew the link / lower the quality / wait for the
///    network) before the viewer is told anything;
///  - when no server gives a stream the browser player (WebPlayerViewController) takes over, exactly like the Android
///    CigramWebPlayerActivity fallback.
/// The iPhone app has no ads, so the ad hooks of the Android player do not exist here.
final class PlayerViewController: UIViewController, UIGestureRecognizerDelegate, AVPlayerItemLegibleOutputPushDelegate,
                                  AVPictureInPictureControllerDelegate, UIDocumentPickerDelegate {
    enum PanMode { case none, seek, brightness, volume }

    struct SubChoice {
        enum Kind {
            case external(String)
            case embedded(AVMediaSelectionOption)
        }
        let title: String
        let kind: Kind
    }

    // MARK: content
    let show: Show
    var episode: CatalogItem?
    var progressKey = ""
    var speedKey = ""

    // MARK: engine
    let player = AVPlayer()
    let surface = PlayerSurface()
    var item: AVPlayerItem?
    var timeObserver: Any?
    var observations: [NSKeyValueObservation] = []
    var legibleOutput: AVPlayerItemLegibleOutput?
    var pip: AVPictureInPictureController?
    var remoteTargets: [(MPRemoteCommand, Any)] = []

    // MARK: sources
    var sources: [String] = []
    var sourceIndex = 0
    var resolved: ResolvedStream?
    var currentStream = ""
    var originalStream = ""
    var triedStreams = Set<String>()
    var embedJob: EmbedJob?
    var loadGeneration = 0
    var nativeDeadline = Date()
    var everPlayed = false
    var inWebFallback = false
    var closing = false

    // MARK: repair
    var incidentAttempt = 0
    var repairTimer: Timer?
    var stallTimer: Timer?
    var audioCheckTimer: Timer?
    var loadingTimer: Timer?
    var playingSince: Date?
    var waitingForNetwork = false
    var pendingRepair: (kind: RepairPlan.Kind, http: Int)?
    var restartAfterNetwork = false
    var qualityCeiling = 0
    var manualHeight = 0
    var smartCap = 0
    var variants: [HLSVariant] = []
    var masterInfo: (text: String, base: String)?

    // MARK: playback state
    var speed: Float = 1
    var wantsPlay = true
    var userPaused = false
    var isReady = false
    var pendingResume: Double = 0
    var duration: Double = 0
    var bufferedEnd: Double = 0
    var sleepLeft: Double = 0
    var lastSave = Date.distantPast
    var lastSlowTick: Double = 0
    var lastPos: Double = 0
    var brightnessTouched = false
    var sceneSegments: [(start: Double, end: Double)] = []
    var lastSceneSkip: Double = -100
    var skipEngine = SkipIntroEngine()
    var skipTarget: Double = 0
    var panMode = PanMode.none
    var panStartValue: CGFloat = 0
    var panStartTime: Double = 0
    var panTarget: Double = 0
    var originalBrightness: CGFloat = 0.5
    var controlsVisible = true
    var controlsLocked = false
    var hideTimer: Timer?
    var statusTimer: Timer?
    var hudTimer: Timer?
    var flashTimer: Timer?
    var flashAccum = 0
    var flashForward = true
    var sheet: PlayerSheet?
    var resizeMode = 0

    // MARK: subtitles / audio
    var subChoices: [SubChoice] = []
    var subSelected = -1
    var subDoc: SubtitleDocument?
    var subDelay: Double = 0
    var subCache: [String: SubtitleDocument] = [:]
    var embeddedText = ""
    var lastSubText = "\u{0}"
    var legibleGroup: AVMediaSelectionGroup?
    var audioGroup: AVMediaSelectionGroup?

    // MARK: views
    let shadeTop = CAGradientLayer()
    let shadeBottom = CAGradientLayer()
    let controls = UIView()
    let subtitleLabel = UILabel()
    let spinner = UIActivityIndicatorView(style: .large)
    let statusLabel = UILabel()
    let hudLabel = UILabel()
    let flashLabel = UILabel()
    let bubble = UILabel()
    let closeButton = RoundIconButton(symbols: ["xmark"], size: 19, label: "إغلاق")
    let titleLabel = UILabel()
    let episodeLabel = UILabel()
    let routeView = AVRoutePickerView()
    let pipButton = RoundIconButton(symbols: ["pip.enter", "rectangle.on.rectangle"], size: 19, label: "صورة داخل صورة")
    let lockButton = RoundIconButton(symbols: ["lock.fill"], size: 18, label: "قفل الشاشة")
    let rotateButton = RoundIconButton(symbols: ["arrow.triangle.2.circlepath", "rotate.right"], size: 18, label: "تدوير الشاشة")
    let episodesButton = RoundIconButton(symbols: ["list.bullet"], size: 19, label: "الحلقات")
    let settingsButton = RoundIconButton(symbols: ["gearshape.fill", "gear"], size: 20, label: "إعدادات المشاهدة")
    let prevButton = RoundIconButton(symbols: ["backward.end.fill"], size: 24, label: "الحلقة السابقة")
    let nextButton = RoundIconButton(symbols: ["forward.end.fill"], size: 24, label: "الحلقة التالية")
    let backButton = RoundIconButton(symbols: ["gobackward.10", "gobackward"], size: 30, label: "رجوع 10 ثوانٍ")
    let forwardButton = RoundIconButton(symbols: ["goforward.10", "goforward"], size: 30, label: "تقديم 10 ثوانٍ")
    let playButton = RoundIconButton(symbols: ["play.fill"], size: 38, label: "تشغيل أو إيقاف")
    let timeLabel = UILabel()
    let leftLabel = UILabel()
    let seekBar = SeekBar()
    let chips = UIScrollView()
    let speedChip = GlassChip(frame: .zero)
    let qualityChip = GlassChip(frame: .zero)
    let subsChip = GlassChip(frame: .zero)
    let resizeChip = GlassChip(frame: .zero)
    let sourceChip = GlassChip(frame: .zero)
    let skipButton = GlassChip(frame: .zero)
    let nextCard = UIView()
    let nextTitle = UILabel()
    let nextCountdown = UILabel()
    let nextNow = GlassChip(frame: .zero)
    let nextCancel = GlassChip(frame: .zero)
    let resumeChip = UIView()
    let resumeLabel = UILabel()
    let resumeStartOver = GlassChip(frame: .zero)
    let errorPanel = UIView()
    let errorLabel = UILabel()
    let errorRetry = GlassChip(frame: .zero)
    let errorWeb = GlassChip(frame: .zero)
    let errorClose = GlassChip(frame: .zero)
    let webHint = GlassChip(frame: .zero)
    let unlockButton = RoundIconButton(symbols: ["lock.open.fill", "lock.fill"], size: 20, label: "فتح القفل")
    let volumeView = MPVolumeView(frame: CGRect(x: -3000, y: -3000, width: 10, height: 10))

    // MARK: init

    init(show: Show, episode: CatalogItem?) {
        self.show = show
        self.episode = episode
        super.init(nibName: nil, bundle: nil)
        modalPresentationCapturesStatusBarAppearance = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    deinit {
        NotificationCenter.default.removeObserver(self)
        hideTimer?.invalidate()
        repairTimer?.invalidate()
        stallTimer?.invalidate()
        audioCheckTimer?.invalidate()
        loadingTimer?.invalidate()
        statusTimer?.invalidate()
        hudTimer?.invalidate()
        flashTimer?.invalidate()
    }

    override var prefersStatusBarHidden: Bool { return true }
    override var prefersHomeIndicatorAutoHidden: Bool { return true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { return .allButUpsideDown }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { return .landscapeRight }

    var isPortrait: Bool { return view.bounds.height >= view.bounds.width }

    // MARK: view

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.semanticContentAttribute = .forceLeftToRight
        NetworkMonitor.shared.start()
        originalBrightness = UIScreen.main.brightness
        resizeMode = PlayerPrefs.resize

        // activating the audio session can block for a moment (it interrupts other apps): never on the main thread
        let session = AVAudioSession.sharedInstance()
        DispatchQueue.global(qos: .userInitiated).async {
            try? session.setCategory(.playback, mode: .moviePlayback, options: [])
            try? session.setActive(true)
        }

        surface.backgroundColor = .black
        surface.playerLayer.player = player
        surface.playerLayer.videoGravity = AVLayerVideoGravity.resizeAspect
        view.addSubview(surface)
        player.automaticallyWaitsToMinimizeStalling = true
        player.allowsExternalPlayback = true
        player.usesExternalPlaybackWhileExternalScreenIsActive = true
        applyResize()

        configureLabels()
        view.addSubview(subtitleLabel)

        shadeTop.colors = [UIColor.black.withAlphaComponent(0.72).cgColor, UIColor.clear.cgColor]
        shadeBottom.colors = [UIColor.clear.cgColor, UIColor.black.withAlphaComponent(0.78).cgColor]
        controls.layer.addSublayer(shadeTop)
        controls.layer.addSublayer(shadeBottom)
        controls.backgroundColor = .clear
        view.addSubview(controls)

        configureButtons()
        configureOverlays()
        configureGestures()
        view.addSubview(volumeView)

        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(audioInterrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        nc.addObserver(self, selector: #selector(routeChanged(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
        nc.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        nc.addObserver(self, selector: #selector(networkChanged), name: NetworkMonitor.changed, object: nil)

        addTimeObserver()
        setupRemoteCommands()
        setupPictureInPicture()
        start()
        showControls()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UIApplication.shared.isIdleTimerDisabled = true
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isBeingDismissed || closing { UIApplication.shared.isIdleTimerDisabled = false }
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            self?.showControls()
        }
    }

    // MARK: building the controls

    private func configureLabels() {
        subtitleLabel.numberOfLines = 0
        subtitleLabel.textAlignment = .center
        subtitleLabel.isUserInteractionEnabled = false
        subtitleLabel.isHidden = true

        titleLabel.font = Theme.font(17, .bold)
        titleLabel.textColor = .white
        titleLabel.textAlignment = .right
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.7
        episodeLabel.font = Theme.font(13, .medium)
        episodeLabel.textColor = Theme.text2
        episodeLabel.textAlignment = .right
        episodeLabel.adjustsFontSizeToFitWidth = true
        episodeLabel.minimumScaleFactor = 0.7

        for l in [timeLabel, leftLabel] {
            l.font = UIFont.monospacedDigitSystemFont(ofSize: 13, weight: .medium)
            l.textColor = .white
            l.text = "0:00"
        }
        timeLabel.textAlignment = .left
        leftLabel.textAlignment = .right

        spinner.color = .white
        spinner.hidesWhenStopped = true

        statusLabel.font = Theme.font(14, .semibold)
        statusLabel.textColor = .white
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 2
        statusLabel.backgroundColor = UIColor(red: 0x14 / 255.0, green: 0x14 / 255.0, blue: 0x16 / 255.0, alpha: 0.92)
        statusLabel.layer.cornerRadius = 18
        statusLabel.layer.borderWidth = 1
        statusLabel.layer.borderColor = Theme.red.withAlphaComponent(0.35).cgColor
        statusLabel.clipsToBounds = true
        statusLabel.alpha = 0

        hudLabel.font = Theme.font(18, .bold)
        hudLabel.textColor = .white
        hudLabel.textAlignment = .center
        hudLabel.backgroundColor = UIColor(white: 0.05, alpha: 0.78)
        hudLabel.layer.cornerRadius = 16
        hudLabel.clipsToBounds = true
        hudLabel.alpha = 0

        flashLabel.font = Theme.font(20, .heavy)
        flashLabel.textColor = .white
        flashLabel.textAlignment = .center
        flashLabel.backgroundColor = UIColor(white: 1, alpha: 0.18)
        flashLabel.clipsToBounds = true
        flashLabel.alpha = 0

        bubble.font = UIFont.monospacedDigitSystemFont(ofSize: 15, weight: .bold)
        bubble.textColor = .white
        bubble.textAlignment = .center
        bubble.backgroundColor = UIColor(white: 0.05, alpha: 0.82)
        bubble.layer.cornerRadius = 10
        bubble.clipsToBounds = true
        bubble.alpha = 0
    }

    private func configureButtons() {
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        routeView.tintColor = .white
        routeView.activeTintColor = Theme.red
        routeView.prioritizesVideoDevices = true
        routeView.accessibilityLabel = "البث على التلفاز (AirPlay)"
        pipButton.addTarget(self, action: #selector(pipTapped), for: .touchUpInside)
        lockButton.addTarget(self, action: #selector(lockTapped), for: .touchUpInside)
        rotateButton.addTarget(self, action: #selector(rotateTapped), for: .touchUpInside)
        episodesButton.addTarget(self, action: #selector(episodesTapped), for: .touchUpInside)
        settingsButton.addTarget(self, action: #selector(settingsTapped), for: .touchUpInside)
        prevButton.addTarget(self, action: #selector(prevEpisodeTapped), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(nextEpisodeTapped), for: .touchUpInside)
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)
        forwardButton.addTarget(self, action: #selector(forwardTapped), for: .touchUpInside)
        playButton.addTarget(self, action: #selector(playTapped), for: .touchUpInside)
        unlockButton.addTarget(self, action: #selector(unlockTapped), for: .touchUpInside)
        unlockButton.backgroundColor = UIColor(white: 0.07, alpha: 0.8)
        unlockButton.layer.cornerRadius = 24
        unlockButton.alpha = 0
        unlockButton.isHidden = true

        let top: [UIView] = [closeButton, titleLabel, episodeLabel, routeView, pipButton, lockButton, rotateButton, episodesButton, settingsButton]
        for v in top { controls.addSubview(v) }
        for v in [prevButton, backButton, playButton, forwardButton, nextButton] { controls.addSubview(v) }
        for v in [timeLabel, seekBar, leftLabel, chips] { controls.addSubview(v) }
        pipButton.isHidden = !AVPictureInPictureController.isPictureInPictureSupported()

        seekBar.onBegan = { [weak self] in
            self?.hideTimer?.invalidate()
            self?.bubble.alpha = 1
        }
        seekBar.onChanged = { [weak self] s in self?.scrubbing(s) }
        seekBar.onEnded = { [weak self] s in self?.scrubEnded(s) }

        chips.showsHorizontalScrollIndicator = false
        chips.semanticContentAttribute = .forceLeftToRight
        speedChip.addTarget(self, action: #selector(speedChipTapped), for: .touchUpInside)
        qualityChip.addTarget(self, action: #selector(qualityChipTapped), for: .touchUpInside)
        subsChip.addTarget(self, action: #selector(subsChipTapped), for: .touchUpInside)
        resizeChip.addTarget(self, action: #selector(resizeChipTapped), for: .touchUpInside)
        sourceChip.addTarget(self, action: #selector(sourceChipTapped), for: .touchUpInside)
        for c in [speedChip, qualityChip, subsChip, resizeChip, sourceChip] { chips.addSubview(c) }
        refreshChips()
    }

    private func configureOverlays() {
        view.addSubview(spinner)
        view.addSubview(statusLabel)
        view.addSubview(hudLabel)
        view.addSubview(flashLabel)
        view.addSubview(bubble)

        skipButton.setText("تخطي المقدمة  ⏭")
        skipButton.backgroundColor = UIColor(red: 0x0B / 255.0, green: 0x1F / 255.0, blue: 0x29 / 255.0, alpha: 0.9)
        skipButton.layer.borderColor = UIColor.white.cgColor
        skipButton.layer.borderWidth = 1.5
        skipButton.layer.cornerRadius = 22
        skipButton.titleLabel?.font = Theme.font(15, .bold)
        skipButton.alpha = 0
        skipButton.isHidden = true
        skipButton.addTarget(self, action: #selector(skipTapped), for: .touchUpInside)
        view.addSubview(skipButton)

        nextCard.backgroundColor = UIColor(red: 0x0B / 255.0, green: 0x1F / 255.0, blue: 0x29 / 255.0, alpha: 0.92)
        nextCard.layer.cornerRadius = 20
        nextCard.layer.borderWidth = 1
        nextCard.layer.borderColor = UIColor(white: 1, alpha: 0.25).cgColor
        nextCard.alpha = 0
        nextCard.isHidden = true
        nextTitle.text = "الحلقة التالية"
        nextTitle.font = Theme.font(16, .bold)
        nextTitle.textColor = .white
        nextTitle.textAlignment = .right
        nextCountdown.font = Theme.font(12.5)
        nextCountdown.textColor = Theme.text2
        nextCountdown.textAlignment = .right
        nextNow.setText("شاهد الآن")
        nextNow.backgroundColor = Theme.red
        nextNow.addTarget(self, action: #selector(nextNowTapped), for: .touchUpInside)
        nextCancel.setText("إلغاء")
        nextCancel.addTarget(self, action: #selector(nextCancelTapped), for: .touchUpInside)
        for v in [nextTitle, nextCountdown, nextNow, nextCancel] { nextCard.addSubview(v) }
        view.addSubview(nextCard)

        resumeChip.backgroundColor = UIColor(red: 0x14 / 255.0, green: 0x14 / 255.0, blue: 0x16 / 255.0, alpha: 0.94)
        resumeChip.layer.cornerRadius = 20
        resumeChip.layer.borderWidth = 1
        resumeChip.layer.borderColor = UIColor(white: 1, alpha: 0.2).cgColor
        resumeChip.alpha = 0
        resumeChip.isHidden = true
        resumeLabel.font = Theme.font(14, .semibold)
        resumeLabel.textColor = .white
        resumeLabel.textAlignment = .right
        resumeStartOver.setText("من البداية")
        resumeStartOver.addTarget(self, action: #selector(startOverTapped), for: .touchUpInside)
        resumeChip.addSubview(resumeLabel)
        resumeChip.addSubview(resumeStartOver)
        view.addSubview(resumeChip)

        errorPanel.backgroundColor = UIColor(white: 0, alpha: 0.82)
        errorPanel.isHidden = true
        errorLabel.font = Theme.font(16, .semibold)
        errorLabel.textColor = .white
        errorLabel.textAlignment = .center
        errorLabel.numberOfLines = 0
        errorRetry.setText("إعادة المحاولة")
        errorRetry.backgroundColor = Theme.red
        errorRetry.addTarget(self, action: #selector(retryTapped), for: .touchUpInside)
        errorWeb.setText("المشغّل البديل")
        errorWeb.addTarget(self, action: #selector(webTapped), for: .touchUpInside)
        errorClose.setText("رجوع")
        errorClose.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        for v in [errorLabel, errorRetry, errorWeb, errorClose] { errorPanel.addSubview(v) }
        view.addSubview(errorPanel)

        // shown while the video is being prepared, so the viewer is never stuck behind a spinner
        webHint.setText("المشغّل البديل")
        webHint.alpha = 0
        webHint.isHidden = true
        webHint.addTarget(self, action: #selector(webTapped), for: .touchUpInside)
        view.addSubview(webHint)
        view.addSubview(unlockButton)
    }

    private func configureGestures() {
        let single = UITapGestureRecognizer(target: self, action: #selector(singleTapped(_:)))
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        single.require(toFail: double)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.maximumNumberOfTouches = 1
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:)))
        for g in [single, double, pan, pinch] as [UIGestureRecognizer] {
            g.delegate = self
            view.addGestureRecognizer(g)
        }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        if sheet?.isOpen == true { return false }
        if !errorPanel.isHidden { return false }
        var v: UIView? = touch.view
        while let x = v {
            if x is UIControl || x === routeView || x === nextCard || x === resumeChip { return false }
            v = x.superview
        }
        return true
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        return g is UIPinchGestureRecognizer || other is UIPinchGestureRecognizer
    }

    // MARK: layout

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let b = view.bounds
        let s = view.safeAreaInsets
        surface.frame = b
        controls.frame = b
        shadeTop.frame = CGRect(x: 0, y: 0, width: b.width, height: s.top + 110)
        shadeBottom.frame = CGRect(x: 0, y: b.height - (s.bottom + 170), width: b.width, height: s.bottom + 170)

        let left = max(12, s.left + 4)
        let right = b.width - max(12, s.right + 4)
        let topY = max(s.top, 8)
        let portrait = isPortrait

        // top bar: close · title … buttons
        closeButton.frame = CGRect(x: left, y: topY, width: 44, height: 44)
        var bx = right
        func place(_ v: UIView, _ w: CGFloat) {
            if v.isHidden { return }
            bx -= w
            v.frame = CGRect(x: bx, y: topY + 2, width: w, height: 40)
        }
        place(settingsButton, 44)
        place(episodesButton, 44)
        place(rotateButton, 44)
        place(lockButton, 44)
        place(pipButton, 44)
        routeView.frame = CGRect(x: bx - 44, y: topY + 2, width: 44, height: 40)
        bx -= 44
        let titleX = left + 52
        let titleW = max(60, bx - titleX - 8)
        titleLabel.frame = CGRect(x: titleX, y: topY + (episodeLabel.text?.isEmpty == false ? 0 : 6), width: titleW, height: 26)
        episodeLabel.frame = CGRect(x: titleX, y: topY + 24, width: titleW, height: 20)

        // bottom: chips · seek row
        let bottom = b.height - max(s.bottom, 10) - 6
        let chipsY = bottom - 36
        chips.frame = CGRect(x: left, y: chipsY, width: right - left, height: 36)
        layoutChips()
        let seekY = chipsY - 38
        timeLabel.frame = CGRect(x: left, y: seekY, width: 56, height: 32)
        leftLabel.frame = CGRect(x: right - 64, y: seekY, width: 64, height: 32)
        seekBar.frame = CGRect(x: left + 54, y: seekY, width: max(40, right - left - 54 - 62), height: 32)

        // centre cluster
        let midY = (topY + 48 + seekY) / 2
        let cx = b.width / 2
        let gap: CGFloat = portrait ? 8 : 20
        playButton.frame = CGRect(x: cx - 36, y: midY - 36, width: 72, height: 72)
        backButton.frame = CGRect(x: playButton.frame.minX - gap - 56, y: midY - 28, width: 56, height: 56)
        forwardButton.frame = CGRect(x: playButton.frame.maxX + gap, y: midY - 28, width: 56, height: 56)
        prevButton.frame = CGRect(x: backButton.frame.minX - gap - 48, y: midY - 24, width: 48, height: 48)
        nextButton.frame = CGRect(x: forwardButton.frame.maxX + gap, y: midY - 24, width: 48, height: 48)

        spinner.center = CGPoint(x: cx, y: b.height / 2)
        statusLabel.frame = CGRect(x: (b.width - min(b.width - 40, 380)) / 2, y: topY + 52, width: min(b.width - 40, 380), height: 38)
        hudLabel.frame = CGRect(x: cx - 90, y: b.height * 0.22, width: 180, height: 44)
        bubble.frame = CGRect(x: 0, y: seekY - 40, width: 84, height: 32)
        webHint.frame = CGRect(x: cx - 80, y: b.height / 2 + 36, width: 160, height: 36)
        unlockButton.frame = CGRect(x: left + 6, y: b.height / 2 - 24, width: 48, height: 48)

        let cornerRight = right
        let cornerY = seekY - 56
        skipButton.frame = CGRect(x: cornerRight - skipButton.neededWidth - 8, y: cornerY, width: skipButton.neededWidth + 8, height: 44)
        let cardW: CGFloat = 236
        nextCard.frame = CGRect(x: cornerRight - cardW, y: seekY - 112, width: cardW, height: 104)
        nextTitle.frame = CGRect(x: 14, y: 10, width: cardW - 28, height: 22)
        nextCountdown.frame = CGRect(x: 14, y: 32, width: cardW - 28, height: 18)
        nextNow.frame = CGRect(x: cardW - 14 - 100, y: 58, width: 100, height: 36)
        nextCancel.frame = CGRect(x: cardW - 14 - 100 - 8 - 80, y: 58, width: 80, height: 36)
        let rw = min(b.width - 40, 340)
        resumeChip.frame = CGRect(x: cx - rw / 2, y: topY + 100, width: rw, height: 44)
        resumeLabel.frame = CGRect(x: 90, y: 0, width: rw - 106, height: 44)
        resumeStartOver.frame = CGRect(x: 8, y: 6, width: 78, height: 32)

        errorPanel.frame = b
        errorLabel.frame = CGRect(x: 30, y: b.height / 2 - 110, width: b.width - 60, height: 100)
        let bw: CGFloat = 130
        let by = b.height / 2 + 10
        errorRetry.frame = CGRect(x: cx - bw * 1.5 - 8, y: by, width: bw, height: 40)
        errorWeb.frame = CGRect(x: cx - bw / 2, y: by, width: bw, height: 40)
        errorClose.frame = CGRect(x: cx + bw / 2 + 8, y: by, width: bw, height: 40)

        layoutSubtitle()
    }

    func layoutChips() {
        var x: CGFloat = 0
        let all: [GlassChip] = [speedChip, qualityChip, subsChip, resizeChip, sourceChip]
        for c in all {
            if c.isHidden { continue }
            let w = c.neededWidth
            c.frame = CGRect(x: x, y: 0, width: w, height: 34)
            x += w + 8
        }
        chips.contentSize = CGSize(width: x, height: 36)
    }

    func layoutSubtitle() {
        let b = view.bounds
        let s = view.safeAreaInsets
        let side = max(24, max(s.left, s.right) + 12)
        let w = b.width - side * 2
        let size = subtitleLabel.sizeThatFits(CGSize(width: w, height: 400))
        let h = min(size.height, 200)
        let bottomInset: CGFloat = controlsVisible ? (max(s.bottom, 10) + 6 + 36 + 38 + 16) : (max(s.bottom, 10) + 22)
        subtitleLabel.frame = CGRect(x: side, y: b.height - bottomInset - h, width: w, height: h)
    }

    // MARK: controls

    func showControls(autoHide: Bool = true) {
        if controlsLocked { return }
        hideTimer?.invalidate()
        controlsVisible = true
        UIView.animate(withDuration: 0.2) { self.controls.alpha = 1 }
        layoutSubtitle()
        if autoHide { scheduleHide() }
    }

    func scheduleHide() {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 4, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            if self.sheet?.isOpen == true || self.seekBar.isScrubbing || !self.player.rate.isNormalPositive { return }
            self.hideControls()
        }
    }

    func hideControls() {
        hideTimer?.invalidate()
        controlsVisible = false
        UIView.animate(withDuration: 0.25) { self.controls.alpha = 0 }
        layoutSubtitle()
    }

    @objc func singleTapped(_ g: UITapGestureRecognizer) {
        if controlsLocked {
            showUnlock()
            return
        }
        if controlsVisible { hideControls() } else { showControls() }
    }

    @objc func doubleTapped(_ g: UITapGestureRecognizer) {
        if controlsLocked { showUnlock(); return }
        let x = g.location(in: view).x
        let w = view.bounds.width
        if x < w * 0.35 { skip(by: -Double(PlayerPrefs.seekStep)) }
        else if x > w * 0.65 { skip(by: Double(PlayerPrefs.seekStep)) }
        else { togglePlay() }
    }

    @objc func pinched(_ g: UIPinchGestureRecognizer) {
        if g.state != .ended || controlsLocked { return }
        if g.scale > 1.12 && resizeMode != 1 { setResize(1) }
        else if g.scale < 0.88 && resizeMode != 0 { setResize(0) }
    }

    @objc func panned(_ g: UIPanGestureRecognizer) {
        if controlsLocked { return }
        let t = g.translation(in: view)
        switch g.state {
        case .began:
            let v = g.velocity(in: view)
            let x = g.location(in: view).x
            if abs(v.x) > abs(v.y) {
                panMode = .seek
                panStartTime = currentSeconds()
                panTarget = panStartTime
            } else if x < view.bounds.width / 2 {
                panMode = .brightness
                brightnessTouched = true
                panStartValue = UIScreen.main.brightness
            } else {
                panMode = .volume
                panStartValue = CGFloat(AVAudioSession.sharedInstance().outputVolume)
            }
        case .changed:
            switch panMode {
            case .seek:
                if duration <= 0 { return }
                let delta = Double(t.x / max(1, view.bounds.width)) * 120
                panTarget = min(max(0, panStartTime + delta), max(0, duration - 1))
                showHUD((delta >= 0 ? "+" : "−") + ClockText.format(abs(panTarget - panStartTime)) + "   " + ClockText.format(panTarget))
            case .brightness:
                let v = min(1, max(0, panStartValue - t.y / (view.bounds.height * 0.7)))
                UIScreen.main.brightness = v
                showHUD("السطوع  " + String(Int(v * 100)) + "%")
            case .volume:
                let v = min(1, max(0, panStartValue - t.y / (view.bounds.height * 0.7)))
                setSystemVolume(Float(v))
                showHUD("الصوت  " + String(Int(v * 100)) + "%")
            case .none:
                break
            }
        case .ended:
            if panMode == .seek && duration > 0 { seek(to: panTarget) }
            panMode = .none
            hideHUD(after: 0.6)
        default:
            panMode = .none
            hideHUD(after: 0.2)
        }
    }

    func setSystemVolume(_ v: Float) {
        for sub in volumeView.subviews {
            if let slider = sub as? UISlider { slider.value = v; return }
        }
    }

    func showHUD(_ text: String) {
        hudTimer?.invalidate()
        hudLabel.text = text
        hudLabel.alpha = 1
    }

    func hideHUD(after: TimeInterval) {
        hudTimer?.invalidate()
        hudTimer = Timer.scheduledTimer(withTimeInterval: after, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.25) { self?.hudLabel.alpha = 0 }
        }
    }

    // a ripple at the side that was double-tapped («رجوع 10 ثوانٍ» / «تقديم 10 ثوانٍ»), adding up while the viewer keeps tapping
    func flashSeek(forward: Bool, seconds: Int) {
        if flashForward != forward { flashAccum = 0 }
        flashForward = forward
        flashAccum += seconds
        let b = view.bounds
        let w: CGFloat = min(160, b.width * 0.3)
        flashLabel.frame = CGRect(x: forward ? b.width - w - 20 : 20, y: b.height / 2 - w / 2, width: w, height: w)
        flashLabel.layer.cornerRadius = w / 2
        flashLabel.text = (forward ? "+" : "−") + String(flashAccum) + " ث"
        flashLabel.alpha = 1
        flashTimer?.invalidate()
        flashTimer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: false) { [weak self] _ in
            self?.flashAccum = 0
            UIView.animate(withDuration: 0.25) { self?.flashLabel.alpha = 0 }
        }
    }

    // MARK: seek bar

    func scrubbing(_ seconds: Double) {
        timeLabel.text = ClockText.format(seconds)
        leftLabel.text = "-" + ClockText.format(max(0, duration - seconds))
        bubble.text = ClockText.format(seconds)
        let x = seekBar.convert(CGPoint(x: seekBar.x(for: seconds), y: 0), to: view).x
        bubble.frame = CGRect(x: min(max(8, x - 42), view.bounds.width - 92), y: bubble.frame.minY, width: 84, height: 32)
    }

    func scrubEnded(_ seconds: Double?) {
        UIView.animate(withDuration: 0.2) { self.bubble.alpha = 0 }
        if let s = seconds { seek(to: s) }
        scheduleHide()
    }

    func updateBubbleY() {
        bubble.frame = CGRect(x: bubble.frame.minX, y: controls.convert(seekBar.frame, to: view).minY - 40, width: 84, height: 32)
    }

    // MARK: visual state helpers

    func refreshPlayButton() {
        let playing = player.timeControlStatus != .paused && wantsPlay
        playButton.set(symbols: [playing ? "pause.fill" : "play.fill"], size: 38)
    }

    func refreshChips() {
        speedChip.setText(speed == 1 ? "1x" : String(format: "%gx", Double(speed)))
        if manualHeight > 0 {
            qualityChip.setText("الجودة · " + QualityLabel.label(height: manualHeight))
        } else {
            qualityChip.setText("الجودة · تلقائي")
        }
        if subSelected >= 0 && subSelected < subChoices.count { subsChip.setText("الترجمة · " + shortTitle(subChoices[subSelected].title)) }
        else { subsChip.setText("الترجمة · إيقاف") }
        resizeChip.setText(["ملاءمة", "ملء الشاشة", "تمديد"][min(2, max(0, resizeMode))])
        sourceChip.setText(sources.count > 1 ? "المصدر " + String(sourceIndex + 1) + "/" + String(sources.count) : "إعادة التحميل")
        view.setNeedsLayout()
    }

    func shortTitle(_ t: String) -> String { return t.count > 12 ? String(t.prefix(11)) + "…" : t }

    func setResize(_ mode: Int) {
        resizeMode = mode
        PlayerPrefs.resize = mode
        applyResize()
        refreshChips()
        showHUD(["ملاءمة الشاشة", "ملء الشاشة", "تمديد"][min(2, max(0, mode))])
        hideHUD(after: 0.9)
    }

    func applyResize() {
        switch resizeMode {
        case 1: surface.playerLayer.videoGravity = AVLayerVideoGravity.resizeAspectFill
        case 2: surface.playerLayer.videoGravity = AVLayerVideoGravity.resize
        default: surface.playerLayer.videoGravity = AVLayerVideoGravity.resizeAspect
        }
    }

    /// A short line at the top (repair progress, a smart-quality notice ...). nil hides it.
    func showStatus(_ text: String?, autoHide: TimeInterval = 0) {
        statusTimer?.invalidate()
        guard let t = text else {
            UIView.animate(withDuration: 0.2) { self.statusLabel.alpha = 0 }
            return
        }
        statusLabel.text = t
        statusLabel.alpha = 1
        if autoHide > 0 {
            statusTimer = Timer.scheduledTimer(withTimeInterval: autoHide, repeats: false) { [weak self] _ in
                UIView.animate(withDuration: 0.25) { self?.statusLabel.alpha = 0 }
            }
        }
    }

    // MARK: lock

    @objc func lockTapped() {
        controlsLocked = true
        hideControls()
        showUnlock()
        showHUD("تم قفل الشاشة")
        hideHUD(after: 1.2)
    }

    func showUnlock() {
        unlockButton.isHidden = false
        UIView.animate(withDuration: 0.2) { self.unlockButton.alpha = 1 }
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.25, animations: { self?.unlockButton.alpha = 0 }, completion: { _ in self?.unlockButton.isHidden = true })
        }
    }

    @objc func unlockTapped() {
        controlsLocked = false
        unlockButton.alpha = 0
        unlockButton.isHidden = true
        showControls()
    }

    @objc func rotateTapped() {
        let toLandscape = view.bounds.height > view.bounds.width
        if #available(iOS 16.0, *) {
            guard let scene = view.window?.windowScene else { return }
            let mask: UIInterfaceOrientationMask = toLandscape ? .landscapeRight : .portrait
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask), errorHandler: { _ in })
            setNeedsUpdateOfSupportedInterfaceOrientations()
        } else {
            let o: UIInterfaceOrientation = toLandscape ? .landscapeRight : .portrait
            UIDevice.current.setValue(o.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
        showControls()
    }
}

private extension Float {
    /// A rate above zero (the video is moving).
    var isNormalPositive: Bool { return self > 0.01 }
}
