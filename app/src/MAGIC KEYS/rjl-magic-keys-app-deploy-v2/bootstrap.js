(() => {
  try {
    Object.keys(window.localStorage || {}).forEach((key) => {
      if (key.startsWith('sb-') || key.includes('supabase')) {
        window.localStorage.removeItem(key);
      }
    });
    Object.keys(window.sessionStorage || {}).forEach((key) => {
      if (key.startsWith('sb-') || key.includes('supabase')) {
        window.sessionStorage.removeItem(key);
      }
    });
  } catch (_) {
    // Storage may be unavailable in hardened browser modes.
  }

  const appScript = document.createElement('script');
  appScript.src = '/app.js';
  appScript.onload = () => {
    const syncWelcomeName = () => {
      const sidebarName = document.getElementById('sidebarName');
      const welcome = document.getElementById('welcomeTitle');
      const name = sidebarName?.textContent?.trim();
      if (welcome && name && name !== 'Authorised User') {
        welcome.textContent = `Welcome, ${name}`;
      }
    };

    syncWelcomeName();
    const sidebarName = document.getElementById('sidebarName');
    if (sidebarName) {
      new MutationObserver(syncWelcomeName).observe(sidebarName, {
        childList: true,
        characterData: true,
        subtree: true
      });
    }
  };
  document.body.appendChild(appScript);
})();
