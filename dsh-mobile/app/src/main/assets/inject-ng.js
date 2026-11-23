/* dsh-mobile injection script — the dsh 0.2.x branch.

   In 0.2.x dsh owns its drawer and mask ([data-sidebar-drawer] frame plus
   [data-sidebar-mask]), so this script no longer intercepts taps or touches sidebar state.
   It only handles two small things dsh does not:
   1) providing AbortSignal.any / AbortSignal.timeout when an old WebView lacks them
      (early-polyfill.js already does this on page start; this is a second safety net);
   2) scrolling the focused field into view when the soft keyboard opens.
*/
(function () {
  'use strict';
  if (window.__dshMobileInjectedNg) return;
  window.__dshMobileInjectedNg = true;

  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var ctrl = new AbortController();
      for (var i = 0; i < signals.length; i++) {
        var s = signals[i];
        if (!s) continue;
        if (s.aborted) { ctrl.abort(s.reason); return ctrl.signal; }
        s.addEventListener('abort', (function (sig) {
          return function () { ctrl.abort(sig.reason); };
        })(s), { once: true });
      }
      return ctrl.signal;
    };
  }
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (ms) {
      var ctrl = new AbortController();
      setTimeout(function () {
        try { ctrl.abort(new DOMException('The operation timed out.', 'TimeoutError')); }
        catch (e) { ctrl.abort(); }
      }, ms);
      return ctrl.signal;
    };
  }

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () {
      var el = document.activeElement;
      if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
        setTimeout(function () {
          try { el.scrollIntoView({ block: 'nearest' }); } catch (e) {}
        }, 120);
      }
    });
  }
})();
