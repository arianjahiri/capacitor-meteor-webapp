//
// CapacitorMeteorWebAppPlugin.swift
//
// Main plugin class that bridges the Meteor webapp functionality
// to Capacitor, providing hot code push capabilities for iOS.
//

import Capacitor
import Foundation
import UIKit
import WebKit

// Bridge adapter to implement our protocol with Capacitor
class CapacitorBridgeAdapter: CapacitorBridge {
  weak var bridge: CAPBridgeProtocol?

  init(bridge: CAPBridgeProtocol?) {
    self.bridge = bridge
  }

  func setServerBasePath(_ path: String) {
    bridge?.setServerBasePath(path)
  }

  func getWebView() -> AnyObject? {
    return bridge?.webView
  }

  var webView: WKWebView? {
    return bridge?.webView
  }

  func reload() {
    // CAPBridgeProtocol doesn't have a reload method, so we reload the webView directly
    bridge?.webView?.reload()
  }
}

/// Capacitor MeteorWebApp Plugin
/// Enables hot code push functionality for Meteor apps
@objc(CapacitorMeteorWebAppPlugin)
public class CapacitorMeteorWebAppPlugin: CAPPlugin, CAPBridgedPlugin {
  public let identifier = "CapacitorMeteorWebAppPlugin"
  public let jsName = "CapacitorMeteorWebApp"
  public let pluginMethods: [CAPPluginMethod] = [
    CAPPluginMethod(name: "checkForUpdates", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "startupDidComplete", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "getCurrentVersion", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "isUpdateAvailable", returnType: CAPPluginReturnPromise),
    CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
  ]
  private var implementation: CapacitorMeteorWebApp!
  private var bridgeAdapter: CapacitorBridgeAdapter!
  private var activeUpdatePromptVersion: String?
  private var pendingUpdateVersion: String?
  private var shouldDeferAutomaticReloads = false
  private var pendingReloadCall: CAPPluginCall?

  override public func load() {
    bridgeAdapter = CapacitorBridgeAdapter(bridge: self.bridge)
    implementation = CapacitorMeteorWebApp(capacitorBridge: bridgeAdapter)

    // Listen for update notifications from the implementation
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleUpdateAvailable(_:)),
      name: .meteorWebappUpdateAvailable,
      object: nil
    )

    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleUpdateFailed(_:)),
      name: .meteorWebappUpdateFailed,
      object: nil
    )
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  @objc private func handleUpdateAvailable(_ notification: Notification) {
    guard let userInfo = notification.userInfo,
      let version = userInfo["version"] as? String
    else {
      NSLog("❌ CapacitorMeteorWebAppPlugin: No version in notification userInfo")
      return
    }

    pendingUpdateVersion = version
    shouldDeferAutomaticReloads = true

    NSLog("🔔 CapacitorMeteorWebAppPlugin: Notifying JS listeners about version: \(version)")
    notifyListeners("updateAvailable", data: ["version": version])
    presentUpdatePromptIfNeeded(forVersion: version)
  }

  @objc private func handleUpdateFailed(_ notification: Notification) {
    guard let userInfo = notification.userInfo,
      let errorMessage = userInfo["error"] as? String
    else { return }

    NSLog(
      "🔔 CapacitorMeteorWebAppPlugin: Notifying JS listeners about error: \(errorMessage)"
    )
    notifyListeners("error", data: ["message": errorMessage])
  }

  private func presentUpdatePromptIfNeeded(forVersion version: String) {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      if self.activeUpdatePromptVersion == version {
        return
      }

      guard let presenter = self.bridge?.viewController else {
        NSLog("❌ CapacitorMeteorWebAppPlugin: Unable to present update prompt")
        return
      }

      self.activeUpdatePromptVersion = version

      let alert = UIAlertController(
        title: "Update Available",
        message: "A new version is ready to install. Would you like to update now?",
        preferredStyle: .alert
      )

      alert.addAction(
        UIAlertAction(
          title: "Later", style: .cancel,
          handler: { _ in
            self.activeUpdatePromptVersion = nil
            self.handleUserDeferredReloadRequest()
          }))

      alert.addAction(
        UIAlertAction(
          title: "Update", style: .default,
          handler: { _ in
            self.activeUpdatePromptVersion = nil
            self.triggerReloadAfterPrompt()
          }))

      presenter.present(alert, animated: true)
    }
  }

  private func handleUserDeferredReloadRequest() {
    pendingReloadCall?.reject("Update deferred by user")
    pendingReloadCall = nil
  }

  private func triggerReloadAfterPrompt() {
    shouldDeferAutomaticReloads = false
    implementation.reload { [weak self] error in
      guard let self = self else { return }

      if let error = error {
        self.shouldDeferAutomaticReloads = true
        self.pendingReloadCall?.reject(error.localizedDescription)
        self.pendingReloadCall = nil
        self.presentReloadError(message: error.localizedDescription)
      } else {
        self.pendingReloadCall?.resolve()
        self.pendingReloadCall = nil
        self.pendingUpdateVersion = nil
      }
    }
  }

  private func presentReloadError(message: String) {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }

      guard let presenter = self.bridge?.viewController else {
        NSLog(
          "❌ CapacitorMeteorWebAppPlugin: Update failed but no presenter available: \(message)"
        )
        return
      }

      let alert = UIAlertController(
        title: "Update Failed",
        message: message,
        preferredStyle: .alert
      )
      alert.addAction(UIAlertAction(title: "OK", style: .default))
      presenter.present(alert, animated: true)
    }
  }

  @objc func checkForUpdates(_ call: CAPPluginCall) {
    implementation.checkForUpdates { error in
      if let error = error {
        call.reject(error.localizedDescription)
      } else {
        call.resolve()
      }
    }
  }

  @objc func startupDidComplete(_ call: CAPPluginCall) {
    implementation.startupDidComplete { error in
      if let error = error {
        call.reject(error.localizedDescription)
      } else {
        call.resolve()
      }
    }
  }

  @objc func getCurrentVersion(_ call: CAPPluginCall) {
    let version = implementation.getCurrentVersion()
    call.resolve(["version": version])
  }

  @objc func isUpdateAvailable(_ call: CAPPluginCall) {
    let available = implementation.isUpdateAvailable()
    call.resolve(["available": available])
  }

  @objc func reload(_ call: CAPPluginCall) {
    if shouldDeferAutomaticReloads && implementation.isUpdateAvailable() {
      guard pendingReloadCall == nil else {
        call.reject("Reload already pending user confirmation")
        return
      }

      pendingReloadCall = call

      let version = pendingUpdateVersion ?? implementation.getCurrentVersion()
      presentUpdatePromptIfNeeded(forVersion: version)
      return
    }

    implementation.reload { error in
      if let error = error {
        call.reject(error.localizedDescription)
      } else {
        call.resolve()
      }
    }
  }
}
