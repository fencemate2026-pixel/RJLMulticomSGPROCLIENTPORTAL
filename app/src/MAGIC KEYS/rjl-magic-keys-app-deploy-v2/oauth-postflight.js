(() => {
  const oauthClient = window.__rjlSupabaseClient;
  const googleButton = document.getElementById("googleSignInButton");
  const authMessage = document.getElementById("authMessage");
  let oauthPromise = null;

  function writeMessage(text, success = false) {
    if (typeof window.setMessage === "function") {
      window.setMessage(authMessage, text, success);
      return;
    }
    if (!authMessage) return;
    authMessage.textContent = text || "";
    authMessage.classList.toggle("success", success);
  }

  function setBusy(busy) {
    if (typeof window.setButtonBusy === "function") {
      window.setButtonBusy(googleButton, busy, "Opening Google…");
      return;
    }
    if (googleButton) googleButton.disabled = busy;
  }

  async function signInWithGoogle() {
    if (!oauthClient || oauthPromise) return;

    oauthPromise = (async () => {
      setBusy(true);
      writeMessage("Opening secure Google authentication…");

      try {
        const { data, error } = await oauthClient.auth.signInWithOAuth({
          provider: "google",
          options: {
            redirectTo: `${window.location.origin}/auth/callback`,
            skipBrowserRedirect: true,
            queryParams: {
              prompt: "select_account"
            }
          }
        });

        if (error) throw error;
        if (!data?.url) throw new Error("Google authentication did not return a secure redirect URL.");

        window.sessionStorage.setItem("rjl.oauth.started", "1");
        window.location.assign(data.url);
      } catch (error) {
        const message = typeof window.friendlyError === "function"
          ? window.friendlyError(error, "login")
          : (error?.message || "Google sign-in could not be started.");
        writeMessage(message);
        setBusy(false);
      } finally {
        oauthPromise = null;
      }
    })();

    await oauthPromise;
  }

  async function restoreGoogleSession() {
    if (!oauthClient) return;

    const params = new URLSearchParams(window.location.search);
    const oauthError = params.get("oauth_error");
    if (oauthError) {
      writeMessage(oauthError);
      window.history.replaceState({}, document.title, window.location.pathname);
    }

    if (window.sessionStorage.getItem("rjl.oauth.ready") !== "1") return;

    try {
      const { data, error } = await oauthClient.auth.getSession();
      if (error) throw error;
      const session = data?.session;
      if (!session) throw new Error("Google returned without a secure session.");

      window.sessionStorage.removeItem("rjl.oauth.ready");
      window.sessionStorage.removeItem("rjl.oauth.started");

      if (typeof window.showDashboard === "function") window.showDashboard();
      if (typeof window.activateSection === "function") window.activateSection("dashboardSection");
      if (typeof window.pushActivity === "function") {
        window.pushActivity("Signed in with Google", `${session.user?.email || "Authorised user"} successfully signed in.`);
      }

      writeMessage("Google identity verified.", true);
      if (typeof window.loadMagicKey === "function") {
        const loaded = await window.loadMagicKey();
        if (!loaded) writeMessage("Google identity verified, but the live key could not be loaded.");
      }
    } catch (error) {
      window.sessionStorage.removeItem("rjl.oauth.ready");
      window.sessionStorage.removeItem("rjl.oauth.started");
      const message = typeof window.friendlyError === "function"
        ? window.friendlyError(error, "login")
        : (error?.message || "Google sign-in could not be completed.");
      writeMessage(message);
    }
  }

  googleButton?.addEventListener("click", signInWithGoogle);
  restoreGoogleSession();
})();
