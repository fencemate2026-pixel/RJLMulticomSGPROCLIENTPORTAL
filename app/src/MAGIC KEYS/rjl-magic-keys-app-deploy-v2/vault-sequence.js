(() => {
  const overlay = document.getElementById('vaultSequence');
  const statusText = document.getElementById('vaultSequenceStatus');
  let running = false;
  let wrapped = false;

  const wait = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms));

  async function playVaultOpening() {
    if (!overlay || running) return;
    running = true;

    const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    document.body.classList.add('vault-sequence-active');
    overlay.setAttribute('aria-hidden', 'false');
    overlay.classList.remove('is-opening', 'is-complete');
    overlay.classList.add('is-active');

    if (statusText) statusText.textContent = 'Identity verified // releasing security locks';

    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    overlay.classList.add('is-opening');

    if (reducedMotion) {
      await wait(500);
    } else {
      await wait(1050);
      if (statusText) statusText.textContent = 'Locking bolts retracted // opening secure vault';
      await wait(1450);
      if (statusText) statusText.textContent = 'Access granted // loading command centre';
      await wait(1250);
    }

    overlay.classList.add('is-complete');
    await wait(reducedMotion ? 180 : 520);
    overlay.classList.remove('is-active', 'is-opening', 'is-complete');
    overlay.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('vault-sequence-active');
    running = false;
  }

  function installDashboardHook() {
    if (wrapped || typeof window.showDashboard !== 'function') return false;

    const originalShowDashboard = window.showDashboard;
    window.showDashboard = function (...args) {
      const result = originalShowDashboard.apply(this, args);
      playVaultOpening().catch(() => {
        overlay?.classList.remove('is-active', 'is-opening', 'is-complete');
        document.body.classList.remove('vault-sequence-active');
        running = false;
      });
      return result;
    };

    wrapped = true;
    return true;
  }

  window.playVaultOpening = playVaultOpening;

  if (!installDashboardHook()) {
    const timer = window.setInterval(() => {
      if (installDashboardHook()) window.clearInterval(timer);
    }, 40);
    window.setTimeout(() => window.clearInterval(timer), 10000);
  }
})();
