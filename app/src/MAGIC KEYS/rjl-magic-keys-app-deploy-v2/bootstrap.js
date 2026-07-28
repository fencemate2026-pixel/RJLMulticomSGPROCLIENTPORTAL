(() => {
  const clearSavedSupabaseSession = () => {
    try {
      [window.localStorage, window.sessionStorage].forEach((storage) => {
        if (!storage) return;
        Object.keys(storage).forEach((key) => {
          if (key.startsWith('sb-') || key.toLowerCase().includes('supabase')) {
            storage.removeItem(key);
          }
        });
      });
    } catch (_) {
      // Storage may be unavailable in hardened browser modes.
    }
  };

  clearSavedSupabaseSession();

  const appScript = document.createElement('script');
  appScript.src = `/app.js?v=20260728-3`;
  appScript.onload = () => {
    const loading = document.getElementById('loadingState');
    const dashboard = document.getElementById('dashboardContent');
    const errorState = document.getElementById('errorState');
    const errorText = document.getElementById('errorText');
    const sidebarName = document.getElementById('sidebarName');
    const welcome = document.getElementById('welcomeTitle');
    let watchdog = null;

    const syncWelcomeName = () => {
      const name = sidebarName?.textContent?.trim();
      if (welcome && name && name !== 'Authorised User') {
        welcome.textContent = `Welcome, ${name}`;
      }
    };

    const stopLoadingWhenReady = () => {
      if (dashboard && !dashboard.classList.contains('hidden')) {
        loading?.classList.add('hidden');
        errorState?.classList.add('hidden');
        if (watchdog) window.clearTimeout(watchdog);
        watchdog = null;
      }
    };

    const armWatchdog = () => {
      if (!loading || loading.classList.contains('hidden')) return;
      if (watchdog) window.clearTimeout(watchdog);
      watchdog = window.setTimeout(() => {
        if (!loading.classList.contains('hidden') && dashboard?.classList.contains('hidden')) {
          loading.classList.add('hidden');
          if (errorText) {
            errorText.textContent = 'The secure key request took too long. Select Retry or sign in again.';
          }
          errorState?.classList.remove('hidden');
        }
      }, 15000);
    };

    syncWelcomeName();
    stopLoadingWhenReady();
    armWatchdog();

    if (sidebarName) {
      new MutationObserver(syncWelcomeName).observe(sidebarName, {
        childList: true,
        characterData: true,
        subtree: true
      });
    }

    if (loading) {
      new MutationObserver(armWatchdog).observe(loading, { attributes: true, attributeFilter: ['class'] });
    }

    if (dashboard) {
      new MutationObserver(stopLoadingWhenReady).observe(dashboard, { attributes: true, attributeFilter: ['class'] });
    }
  };
  appScript.onerror = () => {
    const loading = document.getElementById('loadingState');
    const errorState = document.getElementById('errorState');
    const errorText = document.getElementById('errorText');
    loading?.classList.add('hidden');
    if (errorText) errorText.textContent = 'The secure portal failed to start. Refresh the page and try again.';
    errorState?.classList.remove('hidden');
  };
  document.body.appendChild(appScript);
})();