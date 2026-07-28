(() => {
  const overlay = document.getElementById('vaultSequence');
  const statusText = document.getElementById('vaultSequenceStatus');
  const accessText = document.querySelector('.vault-access-text');
  const hudState = document.querySelector('.vault-sequence__hud strong');
  let running = false;
  let queuedAuthorisedSequence = false;

  const wait = (ms) => new Promise((resolve) => window.setTimeout(resolve, ms));

  function resetVault() {
    overlay?.classList.remove('is-active', 'is-opening', 'is-complete');
    overlay?.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('vault-sequence-active');
    running = false;
  }

  function setSequenceCopy(authorised) {
    if (authorised) {
      if (statusText) statusText.textContent = 'Identity verified // releasing security locks';
      if (accessText) accessText.textContent = 'Access granted';
      if (hudState) hudState.textContent = 'ENCRYPTED CONNECTION ACTIVE';
      return;
    }

    if (statusText) statusText.textContent = 'Secure vault online // initialising access terminal';
    if (accessText) accessText.textContent = 'Secure terminal online';
    if (hudState) hudState.textContent = 'AUTHENTICATION REQUIRED';
  }

  async function playVaultOpening(options = {}) {
    const authorised = options.authorised !== false;
    if (!overlay) return;

    if (running) {
      if (authorised) queuedAuthorisedSequence = true;
      return;
    }

    running = true;
    setSequenceCopy(authorised);

    try {
      const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
      document.body.classList.add('vault-sequence-active');
      overlay.setAttribute('aria-hidden', 'false');
      overlay.classList.remove('is-opening', 'is-complete');
      overlay.classList.add('is-active');

      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      overlay.classList.add('is-opening');

      if (reducedMotion) {
        await wait(700);
      } else {
        await wait(1050);
        if (statusText) {
          statusText.textContent = authorised
            ? 'Locking bolts retracted // opening secure vault'
            : 'Security mechanisms online // opening access terminal';
        }
        await wait(1450);
        if (statusText) {
          statusText.textContent = authorised
            ? 'Access granted // loading command centre'
            : 'Secure terminal ready // authorised login required';
        }
        await wait(1250);
      }

      overlay.classList.add('is-complete');
      await wait(reducedMotion ? 220 : 520);
    } finally {
      resetVault();

      if (queuedAuthorisedSequence) {
        queuedAuthorisedSequence = false;
        await wait(120);
        await playVaultOpening({ authorised: true });
      }
    }
  }

  function startInitialSequence() {
    const params = new URLSearchParams(window.location.search);
    const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
    const type = params.get('type') || hashParams.get('type');
    if (type === 'invite' || type === 'recovery') return;

    window.setTimeout(() => {
      playVaultOpening({ authorised: false }).catch(resetVault);
    }, 220);
  }

  window.playVaultOpening = playVaultOpening;
  window.resetVaultOpening = resetVault;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startInitialSequence, { once: true });
  } else {
    startInitialSequence();
  }
})();