(() => {
  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = src;
      script.onload = resolve;
      script.onerror = reject;
      document.body.appendChild(script);
    });
  }

  function showStartupError() {
    document.getElementById("loadingState")?.classList.add("hidden");
    const errorState = document.getElementById("errorState");
    const errorText = document.getElementById("errorText");
    if (errorText) errorText.textContent = "The secure portal failed to start. Refresh the page and try again.";
    errorState?.classList.remove("hidden");
  }

  (async () => {
    try {
      await loadScript("/oauth-preflight.js?v=20260728-google-oauth-1");
      await loadScript("/app.js?v=20260728-reliability-7");
      await loadScript("/oauth-postflight.js?v=20260728-google-oauth-1");
    } catch {
      showStartupError();
    }
  })();
})();
