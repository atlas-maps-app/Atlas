import Cocoa
import WebKit

// usage: runner <html> <width> <height> <light|dark> <test1.js> [test2.js ...]   (the page is reloaded between tests)
// Each test is the body of an async function run in the page, after prelude.js (found beside this binary,
// or at $PAGETEST_PRELUDE). A test returns a string: it is printed, or "SNAP <png path> <height>" saves a screenshot.
let args = CommandLine.arguments
if args.count < 6 {
  FileHandle.standardError.write("usage: runner <html> <width> <height> <light|dark> <test.js> [test.js ...] [--bg]\n".data(using: .utf8)!)
  exit(64)
}
let htmlPath = args[1]
let width = Double(args[2])!, height = Double(args[3])!
let dark = args[4] == "dark"
let tests = Array(args.dropFirst(5)).filter { $0 != "--bg" }.map { try! String(contentsOfFile: $0, encoding: .utf8) }
let preludePaths: [String] = [
  ProcessInfo.processInfo.environment["PAGETEST_PRELUDE"],
  Bundle.main.executableURL.map { $0.resolvingSymlinksInPath().deletingLastPathComponent().appendingPathComponent("prelude.js").path },
  (htmlPath as NSString).deletingLastPathComponent + "/../prelude.js",
].compactMap { $0 }
let prelude = preludePaths.lazy.compactMap { try? String(contentsOfFile: $0, encoding: .utf8) }.first ?? ""

class Runner: NSObject, WKNavigationDelegate {
  var idx = 0
  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    keepForeground()
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { self.runNext(webView) }
  }
  func runNext(_ webView: WKWebView) {
    if idx >= tests.count { exit(0) }
    let body = prelude + "\n" + tests[idx]
    idx += 1
    webView.callAsyncJavaScript(body, arguments: [:], in: nil, in: .page) { result in
      switch result {
      case .success(let v):
        let str = v as? String ?? String(describing: v)
        if str.hasPrefix("SNAP ") {
          let parts = str.dropFirst(5).split(separator: " ", maxSplits: 1)
          let path = String(parts[0]); let h = Double(parts[1]) ?? height
          webView.setFrameSize(NSSize(width: width, height: min(h, 16000)))
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            let finish = {
              webView.setFrameSize(NSSize(width: width, height: height))
              if self.idx >= tests.count { exit(0) }
              DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.runNext(webView) }
            }
            // r29: the window server may refuse a snapshot of an off-screen window; then the page is printed to PDF and rasterised
            let viaPDF = {
              let pc = WKPDFConfiguration(); pc.rect = NSRect(x: 0, y: 0, width: width, height: min(h, 16000))
              webView.createPDF(configuration: pc) { res in
                switch res {
                case .success(let data):
                  if let pdf = NSPDFImageRep(data: data) {
                    let scale: CGFloat = 2
                    let pw = Int(pdf.bounds.width * scale), ph = Int(pdf.bounds.height * scale)
                    if let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pw, pixelsHigh: ph, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) {
                      NSGraphicsContext.saveGraphicsState()
                      NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
                      pdf.draw(in: NSRect(x: 0, y: 0, width: CGFloat(pw), height: CGFloat(ph)))
                      NSGraphicsContext.restoreGraphicsState()
                      if let png = rep.representation(using: .png, properties: [:]) { try? png.write(to: URL(fileURLWithPath: path)); print("saved(pdf) \(path) \(Int(h))") }
                    }
                  } else { print("snapfail pdf decode") }
                case .failure(let e): print("snapfail pdf \(e)")
                }
                finish()
              }
            }
            let conf = WKSnapshotConfiguration()
            webView.takeSnapshot(with: conf) { img, err in
              if let img = img, let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) {
                try? png.write(to: URL(fileURLWithPath: path)); print("saved \(path) \(Int(h))")
                finish()
              } else { viaPDF() }
            }
          }
          return
        }
        print(str)
      case .failure(let e): print("JSERR \(e)")
      }
      if self.idx >= tests.count { exit(0) }
      webView.loadHTMLString(html, baseURL: URL(string: "https://atlas.test/")!)
    }
  }
}
let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let cfg = WKWebViewConfiguration()
cfg.websiteDataStore = .nonPersistent()
// r28 tests: no hidden-page timer throttling (the window is off screen)
for name in ["_setHiddenPageDOMTimerThrottlingEnabled:", "_setHiddenPageDOMTimerThrottlingAutoIncreases:", "_setPageVisibilityBasedProcessSuppressionEnabled:", "_setDOMTimersThrottlingEnabled:"] {
  let sel = NSSelectorFromString(name)
  if cfg.preferences.responds(to: sel) {
    typealias F = @convention(c) (AnyObject, Selector, Bool) -> Void
    let f = unsafeBitCast(cfg.preferences.method(for: sel), to: F.self)
    f(cfg.preferences, sel, false)
  } else { FileHandle.standardError.write("no \(name)\n".data(using: .utf8)!) }
}
// r29: the GPU process stalls for 15 s at a time on this machine (no display): the web process draws its own layers instead
do {
  let cls: AnyClass = WKPreferences.self, sel = NSSelectorFromString("_features")
  if let m = class_getClassMethod(cls, sel) {
    typealias F = @convention(c) (AnyClass, Selector) -> NSArray
    let arr = unsafeBitCast(method_getImplementation(m), to: F.self)(cls, sel)
    let off: Set<String> = ["UseGPUProcessForDOMRenderingEnabled", "UseGPUProcessForCanvasRenderingEnabled", "UseGPUProcessForWebGLEnabled", "UseGPUProcessForMediaEnabled"]
    let setSel = NSSelectorFromString("_setEnabled:forFeature:")
    typealias S = @convention(c) (AnyObject, Selector, Bool, AnyObject) -> Void
    if cfg.preferences.responds(to: setSel) {
      let setF = unsafeBitCast(cfg.preferences.method(for: setSel), to: S.self)
      for x in arr { if let o = x as? NSObject, let k = o.value(forKey: "key") as? String, off.contains(k) { setF(cfg.preferences, setSel, false, o) } }
    } else { FileHandle.standardError.write("no _setEnabled:forFeature:\n".data(using: .utf8)!) }
  }
}
let napActivity = ProcessInfo.processInfo.beginActivity(options: [.userInitiated, .latencyCritical], reason: "tests")
let web = WKWebView(frame: NSRect(x: 0, y: 0, width: width, height: height), configuration: cfg)
web.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
let win = NSWindow(contentRect: NSRect(x: -30000, y: -30000, width: width, height: height), styleMask: [.borderless], backing: .buffered, defer: false)
win.contentView = web
let runner = Runner()
web.navigationDelegate = runner
// r29: keep the page "visible" so WebKit does not throttle its timers: no occlusion detection, and the (off-screen) window ordered in
for name in ["_setWindowOcclusionDetectionEnabled:"] {
  let sel = NSSelectorFromString(name)
  if web.responds(to: sel) {
    typealias F = @convention(c) (AnyObject, Selector, Bool) -> Void
    let f = unsafeBitCast(web.method(for: sel), to: F.self)
    f(web, sel, false)
  } else { FileHandle.standardError.write("no \(name)\n".data(using: .utf8)!) }
}
win.alphaValue = 1
win.orderFrontRegardless()
// r29: the web process stays at foreground priority, so the OS does not stall it for seconds at a time
func keepForeground() {
  let sel = NSSelectorFromString("_setThrottleStateForTesting:")
  if web.responds(to: sel) {
    typealias F = @convention(c) (AnyObject, Selector, Int32) -> Void
    let f = unsafeBitCast(web.method(for: sel), to: F.self)
    f(web, sel, Int32(CommandLine.arguments.contains("--bg") ? 1 : 2))
  }
}
keepForeground()
let html = try! String(contentsOfFile: htmlPath, encoding: .utf8)
web.loadHTMLString(html, baseURL: URL(string: "https://atlas.test/")!)
DispatchQueue.main.asyncAfter(deadline: .now() + 900) { print("TIMEOUT"); exit(2) }
app.run()
