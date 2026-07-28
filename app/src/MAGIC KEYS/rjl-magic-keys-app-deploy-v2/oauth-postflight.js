(() => {
  const oauthClient = window.__rjlSupabaseClient;
  const authMessage = document.getElementById("authMessage");
  let oauthPromise = null;

  function ensureGoogleControls() {
    const loginPanel = document.getElementById("loginPanel");
    const forgotButton = document.getElementById("forgotButton");
    if (!loginPanel || !forgotButton) return null;

    let googleButton = document.getElementById("googleSignInButton");
    if (googleButton) return googleButton;

    if (!document.getElementById("googleOAuthStyles")) {
      const style = document.createElement("style");
      style.id = "googleOAuthStyles";
      style.textContent = `
        .oauth-divider{display:flex;align-items:center;gap:12px;margin:22px 0 16px;color:#7d8592;font-size:11px;font-weight:800;letter-spacing:.15em;text-transform:uppercase}
        .oauth-divider::before,.oauth-divider::after{content:"";height:1px;flex:1;background:rgba(255,255,255,.12)}
        .google-oauth-button{width:100%;min-height:50px;gap:11px;border:1px solid rgba(255,255,255,.16);color:#f7f7f8;background:linear-gradient(180deg,rgba(28,28,31,.96),rgba(14,14,16,.98));box-shadow:0 14px 30px rgba(0,0,0,.25);font-weight:780}
        .google-oauth-button:hover{border-color:rgba(239,38,53,.55);box-shadow:0 14px 34px rgba(229,31,45,.14)}
        .google-oauth-button:disabled{opacity:.62;cursor:wait;transform:none}
        .google-mark{display:grid;place-items:center;width:26px;height:26px;border-radius:50%;background:#fff;color:#111;font-size:16px;font-weight:900;font-family:Arial,sans-serif;box-shadow:0 0 0 1px rgba(255,255,255,.15)}
        .oauth-note{margin:10px 0 0;text-align:center;color:#777f8b;font-size:11px;line-height:1.5}
      `;
      document.head.appendChild(style);
    }

    const divider = document.createElement("div");
    divider.className = "oauth-divider";
    divider.textContent = "or";

    googleButton = document.createElement("button");
    googleButton.id = "googleSignInButton";
    googleButton.type = "button";
    googleButton.className = "secondary-button google-oauth-button";
    googleButton.innerHTML = '<span class="google-mark" aria-hidden="true">G</span><span>Continue securely with Google</span>';

    const note = document.createElement("p");
    note.className = "oauth-note";
    note.textContent = "Only Google accounts linked to an authorised Magic Keys representative can enter.";

    forgotButton.before(divider, googleButton, note);
    return googleButton;
  }

  const googleButton = ensureGoogleControls();

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
