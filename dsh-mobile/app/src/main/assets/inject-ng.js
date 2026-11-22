/* dsh-mobile 注入脚本 —— dsh 0.2.x 分支。

   0.2.x 的抽屉/遮罩由 dsh 自己管（[data-sidebar-drawer] 框架 +
   [data-sidebar-mask]），这里不再拦截点击、不改侧栏状态，只做两件
   dsh 本身不管的小事：
   1) 老 WebView 缺 AbortSignal.any / AbortSignal.timeout 时补上
      （早注入的 early-polyfill.js 已经做了，这里兜底一次）；
   2) 软键盘弹出时把聚焦的输入框滚进可视区。
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
