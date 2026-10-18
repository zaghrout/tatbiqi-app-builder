import UIKit
import WebKit

/// The store in a full-screen WKWebView.
///
/// Mirrors the Android shell (builder/template/MainActivity.java):
///  - store pages and checkout/payment pages stay inside the app;
///  - WhatsApp links and other schemes (tel:, mailto:) open the matching app;
///  - the WhatsApp button is injected into the store's own pages with JavaScript.
/// Adds what iOS users expect: pull to refresh, swipe back, and an offline screen.
final class StoreViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private let config: StoreConfig
    private var webView: WKWebView!
    private let progress = UIProgressView(progressViewStyle: .bar)
    private var progressObservation: NSKeyValueObservation?
    private let offlineView = OfflineView()

    init(config: StoreConfig) {
        self.config = config
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        config.primaryColor.isLight ? .darkContent : .lightContent
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // Store color behind the status bar; the page starts below it.
        view.backgroundColor = config.primaryColor

        let contentController = WKUserContentController()
        if !config.whatsappNumber.isEmpty {
            contentController.addUserScript(WKUserScript(
                source: whatsappButtonScript(),
                injectionTime: .atDocumentEnd,
                forMainFrameOnly: true
            ))
            contentController.add(WeakScriptHandler(self), name: "tatbiqi")
        }

        let wkConfig = WKWebViewConfiguration()
        wkConfig.userContentController = contentController
        wkConfig.allowsInlineMediaPlayback = true
        wkConfig.websiteDataStore = .default()

        webView = WKWebView(frame: .zero, configuration: wkConfig)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.backgroundColor = .white
        webView.isOpaque = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        let refresh = UIRefreshControl()
        refresh.addTarget(self, action: #selector(reload(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refresh

        progress.progressTintColor = config.primaryColor.isLight ? .darkGray : config.primaryColor
        progress.trackTintColor = .clear
        progressObservation = webView.observe(\.estimatedProgress) { [weak self] wv, _ in
            self?.progress.setProgress(Float(wv.estimatedProgress), animated: true)
            self?.progress.isHidden = wv.estimatedProgress >= 1
        }

        offlineView.isHidden = true
        offlineView.onRetry = { [weak self] in self?.retry() }

        for v in [webView!, progress, offlineView] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: safe.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            progress.topAnchor.constraint(equalTo: safe.topAnchor),
            progress.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            offlineView.topAnchor.constraint(equalTo: safe.topAnchor),
            offlineView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            offlineView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            offlineView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        webView.load(URLRequest(url: config.storeURL))
    }

    @objc private func reload(_ sender: UIRefreshControl) {
        webView.reload()
    }

    private func retry() {
        offlineView.isHidden = true
        if webView.url == nil {
            webView.load(URLRequest(url: config.storeURL))
        } else {
            webView.reload()
        }
    }

    // MARK: - Navigation

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else { return decisionHandler(.cancel) }
        let scheme = url.scheme?.lowercased() ?? ""
        let host = url.host?.lowercased() ?? ""

        if host == "wa.me" || host.hasSuffix("whatsapp.com") {
            openExternal(url)
            return decisionHandler(.cancel)
        }
        if scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data" {
            return decisionHandler(.allow)
        }
        openExternal(url)
        decisionHandler(.cancel)
    }

    /// Links with target="_blank" (e.g. some payment pages) open in the same view.
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.scrollView.refreshControl?.endRefreshing()
        offlineView.isHidden = true
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleLoadError(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleLoadError(error)
    }

    private func handleLoadError(_ error: Error) {
        webView.scrollView.refreshControl?.endRefreshing()
        let code = (error as NSError).code
        // Cancelled loads (a new link tapped mid-load) are not failures.
        if code == NSURLErrorCancelled || code == 102 /* frame load interrupted */ { return }
        offlineView.isHidden = false
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }

    // MARK: - JavaScript alerts (some stores use confirm() at checkout)

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "حسناً", style: .default) { _ in completionHandler() })
        present(alert, animated: true)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "إلغاء", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "حسناً", style: .default) { _ in completionHandler(true) })
        present(alert, animated: true)
    }

    // MARK: - WhatsApp

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "tatbiqi", (message.body as? String) == "whatsapp",
              config.isStoreHost(message.frameInfo.request.url?.host) || config.isStoreHost(webView.url?.host)
        else { return }
        var link = "https://wa.me/" + config.whatsappNumber
        if !config.whatsappMessage.isEmpty,
           let text = config.whatsappMessage.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            link += "?text=" + text
        }
        if let url = URL(string: link) { openExternal(url) }
    }

    private func whatsappButtonScript() -> String {
        // The host check runs in the page so the button only shows on the store's own pages.
        let host = config.storeHost.replacingOccurrences(of: "'", with: "")
        return """
        (function(){
          var b='\(host)';
          var a=location.hostname.toLowerCase().replace(/^www\\./,'');
          if(!b||!(a===b||a.endsWith('.'+b)||b.endsWith('.'+a)))return;
          if(document.getElementById('tatbiqi-wa'))return;
          var e=document.createElement('div');
          e.id='tatbiqi-wa';
          e.setAttribute('role','button');
          e.setAttribute('aria-label','WhatsApp');
          e.innerHTML='<svg viewBox="0 0 32 32" width="28" height="28" fill="#fff"><path d="M16 3C9 3 3.3 8.6 3.3 15.6c0 2.5.7 4.8 1.9 6.8L3 29l6.8-2.1c1.9 1 4 1.6 6.2 1.6 7 0 12.7-5.6 12.7-12.6S23 3 16 3zm0 23.1c-2 0-3.9-.6-5.5-1.6l-.4-.2-4 1.2 1.3-3.9-.3-.4a10.4 10.4 0 0 1-1.7-5.6C5.4 9.8 10.2 5.1 16 5.1s10.6 4.7 10.6 10.5S21.8 26.1 16 26.1zm5.8-7.8c-.3-.2-1.9-.9-2.2-1-.3-.1-.5-.2-.7.2-.2.3-.8 1-1 1.2-.2.2-.4.2-.7.1-.3-.2-1.3-.5-2.5-1.5-.9-.8-1.6-1.8-1.7-2.1-.2-.3 0-.5.1-.6l.5-.6c.2-.2.2-.3.3-.5.1-.2 0-.4 0-.5l-1-2.4c-.3-.6-.5-.5-.7-.5h-.6c-.2 0-.5.1-.8.4-.3.3-1 1-1 2.5s1.1 2.9 1.2 3.1c.2.2 2.1 3.2 5.1 4.5 2.5 1 3 .8 3.6.7.6-.1 1.9-.8 2.2-1.5.3-.7.3-1.4.2-1.5-.1-.1-.3-.2-.6-.3z"/></svg>';
          e.style.cssText='position:fixed;bottom:calc(96px + env(safe-area-inset-bottom));left:16px;width:56px;height:56px;border-radius:50%;background:#25D366;display:flex;align-items:center;justify-content:center;z-index:2147483646;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.3);';
          e.onclick=function(){window.webkit.messageHandlers.tatbiqi.postMessage('whatsapp');};
          (document.body||document.documentElement).appendChild(e);
        })();
        """
    }

    private func openExternal(_ url: URL) {
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
}

/// Avoids the retain cycle WKUserContentController creates with its handlers.
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(c, didReceive: message)
    }
}

/// Shown when the store can't be reached.
private final class OfflineView: UIView {
    var onRetry: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .systemBackground

        let icon = UIImageView(image: UIImage(systemName: "wifi.slash"))
        icon.tintColor = .secondaryLabel
        icon.contentMode = .scaleAspectFit
        icon.heightAnchor.constraint(equalToConstant: 48).isActive = true

        let title = UILabel()
        title.text = "ما في اتصال بالإنترنت"
        title.font = .preferredFont(forTextStyle: .headline)
        title.textAlignment = .center

        let subtitle = UILabel()
        subtitle.text = "تأكد من الاتصال وحاول مرة ثانية."
        subtitle.font = .preferredFont(forTextStyle: .subheadline)
        subtitle.textColor = .secondaryLabel
        subtitle.textAlignment = .center
        subtitle.numberOfLines = 0

        let button = UIButton(type: .system)
        button.setTitle("إعادة المحاولة", for: .normal)
        button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        button.addTarget(self, action: #selector(retryTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [icon, title, subtitle, button])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -32),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    @objc private func retryTapped() { onRetry?() }
}
