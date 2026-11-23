/* dsh-mobile injection script: handles SPA re-renders and the soft keyboard.
   Idempotent: running it twice adds no duplicate side effects. */
(function () {
  'use strict';
  if (window.__dshMobileInjected) return;
  window.__dshMobileInjected = true;

  // Older WebViews (Chrome < 116, common as the system WebView on Android 12 and older) have
  // no AbortSignal.any/timeout and dsh's workspace picker throws
  // "AbortSignal.any is not a function" straight away (issues #2/#4). Provide a minimal one.
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

  // Keep the viewport from snapping back to the desktop width after an SPA route or theme change
  function ensureViewport() {
    var v = document.querySelector('meta[name=viewport]');
    var want = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';
    if (v && v.getAttribute('content') !== want) {
      v.setAttribute('content', want);
    }
  }

  // Details panel (_detailsCol), three-state gating:
  //  - real content -> .dsh-mobile-open, a full-screen overlay
  //  - placeholder text only ("Click a tool row...") -> .dsh-mobile-empty, hidden entirely.
  //    Otherwise it sits in the grid and eats most of the width, squeezing the message flow
  //    into a narrow strip with word-by-word wrapping and every card a screen tall - the other half of the missing messages.
  function updateDetailsCol() {
    var mobile = window.innerWidth <= 700;
    var cols = document.querySelectorAll('div[class*="_detailsCol"]');
    for (var i = 0; i < cols.length; i++) {
      var dc = cols[i];
      var text = (dc.innerText || '').trim();
      var placeholder = /click a tool row/i.test(text) || text.length < 30;
      var has = !placeholder && (text.length > 150 ||
        !!dc.querySelector('pre, code, table, img, video, canvas, textarea, [class*="_code"], [class*="_terminal"]'));
      dc.classList.toggle('dsh-mobile-open', mobile && has);
      dc.classList.toggle('dsh-mobile-empty', mobile && !has);
    }
  }

  // One way to find the dsh sidebar toggle: the class-name fragment (_toggle) first, aria-label as fallback
  // (a dsh update or a language switch can change the aria-label, the class is steadier).
  function findSidebarToggle() {
    return document.querySelector('div[class*="_sidebarCol"] button[class*="_toggle"]') ||
      document.querySelector('div[class*="_sidebarCol"] button[aria-label*="sidebar" i]') ||
      document.querySelector('div[class*="_sidebarCol"] button[class*="_iconButton"]');
  }

  // The real internal state of the dsh sidebar (when collapsed the inner root carries _collapsed).
  // Never trust the width (our CSS sets display:none while collapsed, so it is always 0),
  // and never trust the body dsh-mobile-drawer class - that one is ours, with a 300ms debounce,
  // so flipping the toggle again on it closes a drawer that just opened on a slow device ("the button does nothing").
  function sidebarIsOpen() {
    var col = document.querySelector('div[class*="_sidebarCol"]');
    if (!col) return false;
    return !col.querySelector('[class*="_collapsed"]');
  }
  // User-intent timestamps: an explicit open (lastOpenIntent) cancels a forced collapse in flight,
  // an explicit close (lastCloseIntent) blocks a delayed re-open, so the two async paths stop fighting
  // (old behaviour: tapping the hamburger inside the 6 second forced-collapse window reopened and
  //  re-closed the drawer, which looked like a dead or jumpy touch target).
  var lastOpenIntent = 0;
  var lastCloseIntent = 0;
  // Only click the toggle to expand once the sidebar is confirmed collapsed; retry once at most if nothing happened.
  function openSidebar(attemptsLeft) {
    lastOpenIntent = Date.now();
    if (sidebarIsOpen()) { updateSidebarDrawer(); return; }
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(function () {
      updateSidebarDrawer();
      // The user closed it themselves within 700ms (tapped the scrim or picked a session), so do not reopen
      if (!sidebarIsOpen() && attemptsLeft > 0 && Date.now() - lastCloseIntent > 900) openSidebar(attemptsLeft - 1);
    }, 700);
  }
  // Only click the toggle to collapse once the sidebar is confirmed expanded.
  function closeSidebar() {
    if (!sidebarIsOpen()) { updateSidebarDrawer(); return; }
    lastCloseIntent = Date.now();
    var t = findSidebarToggle();
    if (t) t.click();
    setTimeout(updateSidebarDrawer, 300);
  }
  // Keep forcing the collapse inside the window: after navigation dsh re-expands the sidebar from its persisted state,
  // and the expansion can land 1-2 seconds after the navigation (longer on a real device), so one click may be undone.
  // Check every 400ms inside the window and only click the toggle once the expanded state is confirmed (never blindly).
  // An explicit open inside the window (lastOpenIntent later than the window start) yields immediately.
  function enforceClosed(until, start) {
    if (start === undefined) start = Date.now();
    if (Date.now() > until) return;
    if (lastOpenIntent > start) return;
    closeSidebar();
    setTimeout(function () { enforceClosed(until, start); }, 400);
  }

  // Floating button in the top-left: the rail is not always there, tapping it summons the sidebar drawer (it just fires the built-in dsh toggle)
  var railBtn = null;
  function ensureRailBtn() {
    if (railBtn && railBtn.isConnected) return railBtn;
    railBtn = document.getElementById('dsh-mobile-railbtn');
    if (!railBtn) {
      railBtn = document.createElement('button');
      railBtn.id = 'dsh-mobile-railbtn';
      railBtn.setAttribute('aria-label', 'Open sidebar');
      railBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/></svg>';
      railBtn.addEventListener('click', function () { openSidebar(2); });
      document.body.appendChild(railBtn);
    }
    return railBtn;
  }

  // Sidebar as a drawer: on a phone an expanded sidebar (width >100px) must not squeeze the conversation,
  // so it becomes an overlay drawer plus a scrim; tapping the scrim closes it (the built-in toggle does the work).
  var maskEl = null;
  function ensureMask() {
    if (maskEl && maskEl.isConnected) return maskEl;
    maskEl = document.getElementById('dsh-mobile-drawer-mask');
    if (!maskEl) {
      maskEl = document.createElement('div');
      maskEl.id = 'dsh-mobile-drawer-mask';
      maskEl.addEventListener('click', function () { closeSidebar(); });
      document.body.appendChild(maskEl);
    }
    return maskEl;
  }
  function updateSidebarDrawer() {
    if (window.innerWidth > 700) {
      document.body.classList.remove('dsh-mobile-drawer');
      return;
    }
    var col = document.querySelector('div[class*="_sidebarCol"]');
    var open = false;
    if (col) {
      // While collapsed our CSS hides sidebarCol (display:none, width 0), so the width is useless;
      // dsh puts _collapsed on the inner root node while collapsed, so that is the source of truth.
      open = !col.querySelector('[class*="_collapsed"]');
    }
    document.body.classList.toggle('dsh-mobile-drawer', open);
    if (open) ensureMask();
    if (window.innerWidth <= 700) ensureRailBtn();
  }

  // Tagging the token and timing bars (the class names are build hashes, so match on text):
  //  - the composer summary bar: "1 turns · 1 steps | LLM … | TTFT avg … tok/s"
  //  - the per-message timing line: "04:37 · Ran for 12s · TTFT 1s · 169 tok/s" (_timeEnd)
  // Both are single nowrap lines that overflow the right edge on a narrow screen (leaving a dangling "k/s").
  // Tag the "|" separators too: once a line wraps, an orphaned "|" hangs at the end, so CSS hides it.
  function tagStatsBar() {
    var els = document.querySelectorAll('div, span');
    var cand = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.classList.contains('dsh-mobile-stats')) continue;
      var t = el.textContent || '';
      if (t.length < 8 || t.length > 200) continue;
      if (el.querySelector('textarea, input, button, [class*="_card"]')) continue;
      var composerStats = /turns|steps/.test(t) && /tok\/s|TTFT|LLM/.test(t);
      var msgStats = t.length < 80 && /Ran for|TTFT/.test(t) && /tok\/s/.test(t);
      if (composerStats || msgStats) cand.push(el);
    }
    // Tag only the innermost match: the outer container (the whole message bubble) merely contains the stats text,
    // and tagging it turns every span inside into inline-block nowrap, wrecking the body layout.
    for (var c = 0; c < cand.length; c++) {
      var innermost = true;
      for (var d = 0; d < cand.length; d++) {
        if (d !== c && cand[c].contains(cand[d])) { innermost = false; break; }
      }
      if (innermost) cand[c].classList.add('dsh-mobile-stats');
    }
    var bars = document.querySelectorAll('.dsh-mobile-stats');
    for (var b = 0; b < bars.length; b++) {
      var kids = bars[b].querySelectorAll('*');
      for (var k = 0; k < kids.length; k++) {
        if ((kids[k].textContent || '').trim() === '|') {
          kids[k].classList.add('dsh-mobile-stats-sep');
        }
      }
    }
  }

  // Horizontal clamping for popovers: dsh positions them for a desktop (a menu or bubble, e.g. the model picker aligned
  // right:0 to its trigger), so on a narrow phone the whole layer gets clipped at one edge. When out of bounds, rewrite
  // left/right to pull it back in; dsh resets the inline position on re-render, so clamp again next time without piling up.
  function clampPopovers() {
    if (window.innerWidth > 700) return;
    var vw = window.innerWidth;
    // Note: _popover and _floating class names do not exist in the rc.6/rc.7 DOM (issue #5),
    // so tooltip bubbles are matched with [role="tooltip"] instead of a class name
    var els = document.querySelectorAll(
      'div[class*="_menu"], div[class*="_dropdown"], [role="tooltip"]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var cs = window.getComputedStyle(el);
      if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 20 || r.height < 10) continue;
      var op = el.offsetParent ? el.offsetParent.getBoundingClientRect() : { left: 0, right: vw };
      if (r.left < 6) {
        el.style.right = 'auto';
        el.style.left = Math.round(6 - op.left) + 'px';
      } else if (r.right > vw - 6) {
        el.style.left = 'auto';
        el.style.right = Math.round(op.right - (vw - 6)) + 'px';
      }
    }
  }

  // Tooltips disappear on their own: the dsh Tooltip component shows on mouseenter/focus and
  // hides on mouseleave/blur, but a touch tap synthesises mouseenter/focus and pops the bubble,
  // which then needs a tap elsewhere to go away (a black "command" block stuck on the Bash row, a full-text
  // black box over the composer). Let it appear, then hide it 2.5s later.
  // Only add an inline display:none, never remove the node: React unmounts it on the anchor
  // mouseleave/blur, and deleting nodes gets out of sync with React rendering.
  var TIP_AUTOHIDE_MS = 2500;
  function armTooltipAutohide() {
    var tips = document.querySelectorAll('[role="tooltip"]:not([data-dsh-tip-armed])');
    for (var i = 0; i < tips.length; i++) {
      var tip = tips[i];
      tip.setAttribute('data-dsh-tip-armed', '1');
      (function (el) {
        setTimeout(function () {
          el.style.setProperty('display', 'none', 'important');
        }, TIP_AUTOHIDE_MS);
      })(tip);
    }
  }

  // Bottom spacing in the message flow = real composer height + 16px gap.
  // Background: mobile.css pins the composer to the bottom of the viewport (fixed, out of the flow), so the
  // flow needs the same amount of room at the end to scroll the last message above it.
  // Lesson learned (do not revert):
  //  - the old version wrote the spacing as padding-bottom on scrollBody, which pushes the sticky
  //    composer anchor up from inside: the bigger the padding, the higher the composer floats, and since the
  //    measurement used sc.bottom - seat.top while seat.top moves with the padding, it fed back and ran away;
  //    a composer whose height changed at runtime drifted to the middle of the page.
  //  - an inline padding-bottom without !important is overridden by the !important rules in mobile.css,
  //    which makes the dynamic compensation useless.
  // Current approach: append a separate spacer block at the end of scrollBody (in flow, stable height),
  // and measure only the composer height (it follows its content, not the spacing or the positioning), cutting the feedback loop.
  // The composer summary bar ("N turns · N steps | LLM …", tagged .dsh-mobile-stats by tagStatsBar and
  // not inside a _flowItem) is not inside _composerSeat in some dsh versions: it is a separate node in the
  // document flow below the seat, so once the seat is fixed it is either covered by it or pushed off the viewport
  // (a real device screenshot: the stats line clipped in half at the bottom edge).
  // Pin it to the very bottom of the viewport (CSS .dsh-mobile-composer-stats) and move the seat bottom up
  // by the bar height. Returns the bar height (0 when it is inside the seat or missing). Only the height is measured, so no feedback.
  var pinnedStats = null;
  function pinComposerStats(comp) {
    var bar = null;
    var bars = document.querySelectorAll('div[class*="_centerCol"] .dsh-mobile-stats');
    for (var i = 0; i < bars.length; i++) {
      var b = bars[i];
      if (comp.contains(b)) continue;
      if (b.closest('div[class*="_flowItem"]')) continue;
      // The bar root can be tagged twice (the outer container is also .dsh-mobile-stats), so take the outermost
      while (b.parentElement && b.parentElement.classList &&
        b.parentElement.classList.contains('dsh-mobile-stats') &&
        !comp.contains(b.parentElement)) {
        b = b.parentElement;
      }
      bar = b;
      break;
    }
    if (pinnedStats && pinnedStats !== bar) {
      pinnedStats.classList.remove('dsh-mobile-composer-stats');
      pinnedStats = null;
    }
    if (!bar) {
      comp.style.removeProperty('bottom');
      return 0;
    }
    pinnedStats = bar;
    bar.classList.add('dsh-mobile-composer-stats');
    var h = Math.round(bar.getBoundingClientRect().height);
    comp.style.setProperty('bottom', h + 'px', 'important');
    return h;
  }

  var lastPad = -1;
  var spacerEl = null;
  function fitComposerOverlap() {
    var comp = document.querySelector('div[class*="_centerCol"] div[class*="_composerSeat"]');
    var sc = document.querySelector('div[class*="_centerCol"] [class*="_scrollBody"]');
    if (!comp || !sc) return;
    // The composer on the new-chat hero page stays in the flow (not pinned), so no compensation is needed;
    // clear the old spacer and the pinned stats bar when navigating in from a session page.
    if (comp.querySelector('div[class*="_composerHero"]')) {
      if (spacerEl && spacerEl.isConnected) spacerEl.remove();
      if (pinnedStats) {
        pinnedStats.classList.remove('dsh-mobile-composer-stats');
        pinnedStats = null;
      }
      lastPad = -1;
      return;
    }
    if (!spacerEl || !spacerEl.isConnected || spacerEl.parentElement !== sc) {
      spacerEl = document.getElementById('dsh-mobile-composer-spacer');
      if (!spacerEl) {
        spacerEl = document.createElement('div');
        spacerEl.id = 'dsh-mobile-composer-spacer';
        spacerEl.setAttribute('aria-hidden', 'true');
        // scrollBody is a flex column, so forbid shrinking (mobile.css has the same rule; this is belt and braces)
        spacerEl.style.flex = '0 0 auto';
      }
      sc.appendChild(spacerEl);
    }
    // A dsh re-render can append new nodes to the end of scrollBody (e.g. the stats bar outside the
    // composer), so the spacer must stay the last child or the new content lands below it and is clipped
    if (sc.lastElementChild !== spacerEl) sc.appendChild(spacerEl);
    var statsH = pinComposerStats(comp);
    var want = Math.max(48, Math.round(comp.getBoundingClientRect().height) + statsH + 16);
    if (want !== lastPad) {
      lastPad = want;
      spacerEl.style.height = want + 'px';
      sc.style.scrollPaddingBottom = want + 'px';
    }
  }

  // "Open configuration file" interception: the desktop build points that button at
  // settings.openDocument, and the container has no xdg-open or editor, so it always fails.
  // Route it through the native bridge instead (DshNative.openConfig -> ConfigEditorActivity,
  // which edits settings.yaml inside the container; the dsh-settings-file watcher hot reloads it,
  // so no restart is needed).
  // Capture phase: React 17+ attaches events on the root container, so stopPropagation during
  // document capture is enough to beat the original handler. Without the bridge (dev builds) the
  // original logic runs untouched.
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var btn = t.closest('button, [role="button"]');
    if (!btn) return;
    var txt = (btn.textContent || '').trim();
    if (!/打开配置文件|Open configuration file/i.test(txt)) return;
    if (!window.DshNative || !window.DshNative.openConfig) return;
    ev.preventDefault();
    ev.stopPropagation();
    window.DshNative.openConfig();
  }, true);

  // "Add plugin" on the plugins page: dsh web can configure and list plugins but has no install
  // entry point (on the desktop you run dsh plugin --profile web add <pkg>, which forwards to pnpm).
  // Inject one input plus a button at the bottom of the plugins section and run the same CLI in
  // the container through the native bridge. The result comes back asynchronously through
  // window.__dshOnPluginInstallResult (installing needs the network and may take minutes).
  function ensurePluginAdder() {
    if (!window.DshNative || !window.DshNative.installPlugin) return;
    var dlg = document.querySelector('div[role="dialog"], div[class*="_dialog"]');
    if (!dlg) return;
    var heads = dlg.querySelectorAll('[class*="_heading"]');
    var sec = null;
    for (var i = 0; i < heads.length; i++) {
      var t = (heads[i].textContent || '').trim();
      if (t === '插件' || t === 'Plugins') {
        sec = heads[i].closest('div[class*="_section"]') || heads[i].parentElement;
        break;
      }
    }
    if (!sec || document.getElementById('dsh-mobile-plugin-adder')) return;

    var row = document.createElement('div');
    row.id = 'dsh-mobile-plugin-adder';
    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'npm package name, e.g. dsh-plugin-xxx';
    input.setAttribute('autocapitalize', 'off');
    input.setAttribute('autocorrect', 'off');
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.textContent = 'Add plugin';
    var statusEl = document.createElement('div');
    statusEl.className = 'dsh-mobile-plugin-adder-status';
    btn.addEventListener('click', function () {
      var name = input.value.trim();
      if (!name) {
        statusEl.textContent = 'Enter a plugin package name';
        return;
      }
      btn.disabled = true;
      input.disabled = true;
      statusEl.textContent = 'Installing… (the first install downloads pnpm, so it may take a few minutes)';
      window.__dshOnPluginInstallResult = function (res) {
        btn.disabled = false;
        input.disabled = false;
        if (res && res.ok) {
          statusEl.textContent = 'Installed: ' + name + '. Tap "Restart service" on the settings screen to apply it.';
          input.value = '';
        } else {
          statusEl.textContent = 'Install failed:\n' + ((res && res.output) || 'unknown error').slice(-800);
        }
      };
      try {
        window.DshNative.installPlugin(name);
      } catch (e) {
        btn.disabled = false;
        input.disabled = false;
        statusEl.textContent = 'Native bridge call failed: ' + e;
      }
    });
    row.appendChild(input);
    row.appendChild(btn);
    row.appendChild(statusEl);
    sec.appendChild(row);
  }

  var scheduled = false;
  var observer = new MutationObserver(function () {
    if (scheduled) return;
    scheduled = true;
    setTimeout(function () {
      scheduled = false;
      ensureViewport();
      updateDetailsCol();
      updateSidebarDrawer();
      tagStatsBar();
      clampPopovers();
      fitComposerOverlap();
      ensurePluginAdder();
      armTooltipAutohide();
    }, 300);
  });
  observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
  ensureViewport();
  updateDetailsCol();
  updateSidebarDrawer();
  tagStatsBar();
  clampPopovers();
  fitComposerOverlap();
  ensurePluginAdder();
  armTooltipAutohide();
  window.addEventListener('resize', updateSidebarDrawer);

  // Collapse the drawer after a session or workspace row (_sessionRow and friends) is tapped.
  // dsh leaves the sidebar open on the desktop, but on a phone an open drawer hides the conversation.
  // Keep forcing it closed inside a 6 second window: after navigation dsh may re-expand the sidebar
  // from its persisted state, and on a slow device that can land 2-3 seconds after the navigation,
  // so a single toggle click is easily undone.
  // This does not rely on the body drawer class (which lags behind a debounce).
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var col = t.closest('div[class*="_sidebarCol"]');
    if (!col) return;
    // The inline "..." button and the menu it opens are not "selecting a row": they must never
    // trigger the forced collapse, or the drawer closes and the menu is unmounted the moment it
    // opens, so archive and rename can never be tapped (issue #1).
    if (t.closest('[class*="_rowActions"], [class*="_menu"], [role="menu"], [role="menuitem"], [role="dialog"]')) {
      return;
    }
    if (t.closest('[class*="_sessionRow"], [class*="_workspaceRow"], [class*="_projectRow"], [class*="_newSession"], [data-slot^="sidebar.session"]')) {
      enforceClosed(Date.now() + 6000);
    }
  }, true);

  // If the sidebar starts expanded because that state was persisted, close it: otherwise the app
  // opens with a drawer and a scrim over everything, which looks like a dark curtain and swallows
  // every tap. As soon as the user opens the drawer themselves the startup safety net steps aside.
  // closest is required: the tap target is usually an <svg>/<line> inside the button, so comparing
  // ids directly misses it and the net then closes a drawer the user just opened ("it closes itself").
  var startupEnforce = true;
  document.addEventListener('click', function (ev) {
    if (ev.target && ev.target.closest && ev.target.closest('#dsh-mobile-railbtn, #dsh-mobile-drawer-mask')) {
      startupEnforce = false;
    }
  }, true);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 1500); }, 1200);
  setTimeout(function () { if (startupEnforce) enforceClosed(Date.now() + 2500); }, 3500);

  // Gestures: swipe right from the left edge to summon the drawer, swipe left over the drawer or the
  // scrim to close it. Only judged on touchend (passive listener, so page scrolling is not blocked):
  // a clear horizontal move (|dx|>64 and |dy|<=50) is required, so vertical scrolling and diagonal
  // swipes never trigger it.
  var drawerTouch = null;
  document.addEventListener('touchstart', function (ev) {
    drawerTouch = null;
    if (window.innerWidth > 700) return;
    var t = ev.touches[0];
    if (!t) return;
    if (document.body.classList.contains('dsh-mobile-drawer')) {
      if (ev.target && ev.target.closest && ev.target.closest('div[class*="_sidebarCol"], #dsh-mobile-drawer-mask')) {
        drawerTouch = { x: t.clientX, y: t.clientY, close: true };
      }
    } else if (t.clientX <= 24) {
      drawerTouch = { x: t.clientX, y: t.clientY, close: false };
    }
  }, { passive: true });
  document.addEventListener('touchend', function (ev) {
    if (!drawerTouch) return;
    var t = ev.changedTouches[0];
    var dx = t.clientX - drawerTouch.x;
    var dy = t.clientY - drawerTouch.y;
    var close = drawerTouch.close;
    drawerTouch = null;
    if (Math.abs(dy) > 50) return;
    if (!close && dx > 64) openSidebar(2);
    if (close && dx < -64) closeSidebar();
  }, { passive: true });

  // Safety sweep: the observer only runs on DOM mutations, so after a race the scrim or drawer state
  // can get stuck with a still page. Re-align the derived classes with the real dsh state every 2s.
  setInterval(function () { updateSidebarDrawer(); fitComposerOverlap(); }, 2000);

  // Keep the focused input in view when the soft keyboard appears
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () {
      var el = document.activeElement;
      if (el && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.isContentEditable)) {
        setTimeout(function () {
          try { el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (e) {}
        }, 120);
      }
    });
  }

  // Note: do not intercept touchend to stop double-tap zoom here. The viewport already sets
  // maximum-scale=1 + user-scalable=no, so the system disables it; an extra preventDefault would
  // swallow the second tap of a fast double tap and make buttons feel dead.
})();
