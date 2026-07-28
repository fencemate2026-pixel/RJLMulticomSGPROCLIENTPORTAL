const SUPABASE_URL = "https://ifesjmdhlyurswgajslm.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_2AetVTjWpPNPFkSAIYltWg_slgY9b1R";

if (!window.supabase) {
  throw new Error("Secure authentication library failed to load.");
}

const client = window.supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
  auth: {
    persistSession: false,
    autoRefreshToken: true,
    detectSessionInUrl: true
  }
});

const el = (id) => document.getElementById(id);
const navItems = () => Array.from(document.querySelectorAll(".nav-item"));
const viewSections = () => Array.from(document.querySelectorAll(".view-section"));
const setHidden = (id, hidden) => el(id)?.classList.toggle("hidden", hidden);
const setText = (id, value) => {
  const target = el(id);
  if (target) target.textContent = value;
};
const bind = (id, eventName, handler) => el(id)?.addEventListener(eventName, handler);

let currentKey = "";
let keyVisible = false;
let currentProfile = null;
let activityEntries = [];
let loadPromise = null;
let loginPromise = null;
let resetPromise = null;
let setupPromise = null;

function setMessage(target, text, success = false) {
  if (!target) return;
  target.textContent = text || "";
  target.classList.toggle("success", success);
}

function setButtonBusy(button, busy, busyText) {
  if (!button) return;
  if (!button.dataset.originalText) button.dataset.originalText = button.textContent || "";
  button.disabled = busy;
  button.setAttribute("aria-busy", busy ? "true" : "false");
  button.textContent = busy ? busyText : button.dataset.originalText;
}

function friendlyError(error, context = "general") {
  const raw = String(error?.message || error || "").toLowerCase();

  if (raw.includes("invalid login credentials")) return "The email address or password is incorrect.";
  if (raw.includes("email not confirmed")) return "This email address has not been confirmed yet.";
  if (raw.includes("too many requests") || raw.includes("rate limit")) return "Too many attempts. Wait a few minutes and try again.";
  if (raw.includes("not_authorised") || raw.includes("not authorized") || raw.includes("not authorised")) return "This login is not linked to an authorised Magic Keys property.";
  if (raw.includes("account_inactive") || raw.includes("inactive")) return "This Magic Keys account is inactive. Contact RJL Commercial.";
  if (raw.includes("jwt") || raw.includes("token") || raw.includes("session")) return "Your secure session has expired. Sign in again.";
  if (raw.includes("failed to fetch") || raw.includes("network") || raw.includes("load failed")) return "The secure service could not be reached. Check your connection and try again.";
  if (raw.includes("took too long") || raw.includes("abort")) return "The secure service took too long to respond. Press Retry.";
  if (raw.includes("secure service returned 401")) return "Your secure session has expired. Sign in again.";
  if (raw.includes("secure service returned 403")) return "This login is not authorised for a Magic Keys property.";
  if (raw.includes("secure service returned 429")) return "Too many requests. Wait a few minutes and try again.";
  if (raw.includes("secure service returned 5")) return "The secure verification service is temporarily unavailable.";
  if (raw.includes("no authorisation key") || raw.includes("invalid authorisation key")) return "No valid six-digit authorisation key is available for this account.";

  if (context === "login") return "Sign-in could not be completed. Check your details and try again.";
  if (context === "reset") return "The password reset email could not be sent. Try again shortly.";
  if (context === "setup") return "Your password could not be updated. Request a new secure link and try again.";
  return "The secure request could not be completed. Please try again.";
}

function initials(name = "") {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "AU";
}

function formatDate(value) {
  if (!value) return "Current secure record";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Current secure record";
  try {
    return `Updated ${new Intl.DateTimeFormat("en-AU", { dateStyle: "medium", timeStyle: "short" }).format(date)}`;
  } catch {
    return "Current secure record";
  }
}

function formatTime(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "Just now";
  try {
    return new Intl.DateTimeFormat("en-AU", { dateStyle: "medium", timeStyle: "short" }).format(date);
  } catch {
    return "Just now";
  }
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  }[character]));
}

function pushActivity(title, detail) {
  activityEntries.unshift({ title, detail, time: new Date() });
  activityEntries = activityEntries.slice(0, 12);
  renderActivity();
}

function renderActivity() {
  const wrap = el("activityList");
  if (!wrap) return;
  if (!activityEntries.length) {
    wrap.innerHTML = '<div class="activity-item"><div><strong>No activity yet</strong><span>Your recent secure actions will appear here.</span></div><span>—</span></div>';
    return;
  }
  wrap.innerHTML = activityEntries.map((item) => `
    <div class="activity-item"><div><strong>${escapeHtml(item.title)}</strong><span>${escapeHtml(item.detail)}</span></div><span>${escapeHtml(formatTime(item.time))}</span></div>
  `).join("");
}

function normaliseKey(value) {
  const key = String(value ?? "").trim();
  if (!/^\d{6}$/.test(key)) throw new Error("Invalid authorisation key returned by the secure service.");
  return key;
}

function setKeyDisplays(value) {
  const masked = value && keyVisible ? value.replace(/(\d{3})(\d{3})/, "$1 $2") : "••• •••";
  setText("keyDisplay", masked);
  setText("keyDisplayLarge", masked);
  const text = keyVisible ? "Hide key" : "Reveal key";
  setText("revealButton", text);
  setText("revealButtonAlt", text);
}

function setLoading() {
  setHidden("loadingState", false);
  setHidden("errorState", true);
  setHidden("dashboardContent", true);
}

function stopLoading() {
  setHidden("loadingState", true);
}

function setError(message) {
  stopLoading();
  setHidden("dashboardContent", true);
  setText("errorText", message || "Please sign out and try again.");
  setHidden("errorState", false);
  pushActivity("Key load failed", message || "Unable to retrieve the live authorisation key.");
}

function setContentReady() {
  stopLoading();
  setHidden("errorState", true);
  setHidden("dashboardContent", false);
}

function resetSecureState() {
  currentKey = "";
  currentProfile = null;
  keyVisible = false;
  loadPromise = null;
  setKeyDisplays("");
  window.resetVaultOpening?.();
}

function showAuth() {
  stopLoading();
  setHidden("dashboardView", true);
  setHidden("authView", false);
}

function showDashboard() {
  setHidden("authView", true);
  setHidden("dashboardView", false);
}

function showPasswordSetup() {
  setHidden("loginPanel", true);
  setHidden("setPasswordPanel", false);
}

function showLogin() {
  setHidden("setPasswordPanel", true);
  setHidden("loginPanel", false);
}

function activateSection(targetId) {
  navItems().forEach((button) => button.classList.toggle("active", button.dataset.target === targetId));
  viewSections().forEach((section) => section.classList.toggle("hidden", section.id !== targetId));
}

async function fetchMagicKey(session) {
  if (!session?.access_token) throw new Error("Secure session is unavailable.");
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12000);

  try {
    const response = await fetch(`${SUPABASE_URL}/functions/v1/get-magic-key`, {
      method: "GET",
      signal: controller.signal,
      cache: "no-store",
      headers: {
        Authorization: `Bearer ${session.access_token}`,
        apikey: SUPABASE_PUBLISHABLE_KEY,
        Accept: "application/json"
      }
    });

    let data = null;
    try { data = await response.json(); } catch { data = null; }
    if (!response.ok) throw new Error(data?.error || `Secure service returned ${response.status}`);
    if (!data || typeof data !== "object") throw new Error("The secure service returned an invalid response.");
    return data;
  } catch (error) {
    if (error?.name === "AbortError") throw new Error("The secure service took too long to respond.");
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

function applyProfile(data) {
  currentProfile = data;
  currentKey = normaliseKey(data.authorisation_key);
  keyVisible = false;
  setKeyDisplays(currentKey);

  const fullName = String(data.representative || "Authorised User").trim() || "Authorised User";
  const site = String(data.site || "337 Settlement Road").trim();
  const role = String(data.position || "Authorised Representative").trim();

  setText("siteName", site);
  setText("siteNameAlt", site);
  setText("sidebarName", fullName);
  setText("sidebarRole", role);
  setText("welcomeTitle", `Welcome, ${fullName}`);
  setText("initials", initials(fullName));
  setText("updatedAt", formatDate(data.updated_at));
  setText("updatedAtAlt", formatDate(data.updated_at));
  setText("accessLevel", role);
  setText("accessLevelAlt", role);
}

async function loadMagicKey() {
  if (loadPromise) return loadPromise;

  loadPromise = (async () => {
    setLoading();
    try {
      const { data: sessionData, error: sessionError } = await client.auth.getSession();
      if (sessionError) throw sessionError;
      const session = sessionData?.session;
      if (!session) {
        resetSecureState();
        showAuth();
        showLogin();
        return false;
      }

      const data = await fetchMagicKey(session);
      applyProfile(data);
      setContentReady();
      pushActivity("Key loaded", `Secure key loaded for ${data.representative || "authorised user"}.`);
      await window.playVaultOpening?.();
      return true;
    } catch (error) {
      setError(friendlyError(error));
      return false;
    } finally {
      loadPromise = null;
    }
  })();

  return loadPromise;
}

bind("loginForm", "submit", async (event) => {
  event.preventDefault();
  if (loginPromise) return;

  const form = event.currentTarget;
  const button = form?.querySelector('button[type="submit"]');
  const email = el("email")?.value.trim() || "";
  const password = el("password")?.value || "";

  if (!email || !password) {
    setMessage(el("authMessage"), "Enter your email address and password.");
    return;
  }

  loginPromise = (async () => {
    setButtonBusy(button, true, "Authenticating…");
    setMessage(el("authMessage"), "Authenticating securely…");

    try {
      const { error } = await client.auth.signInWithPassword({ email, password });
      if (error) throw error;

      setMessage(el("authMessage"), "Identity verified.", true);
      showDashboard();
      activateSection("dashboardSection");
      pushActivity("Signed in", `${email} successfully signed in.`);
      const loaded = await loadMagicKey();
      if (!loaded) setMessage(el("authMessage"), "Access verified, but the live key could not be loaded.");
    } catch (error) {
      resetSecureState();
      showAuth();
      showLogin();
      setMessage(el("authMessage"), friendlyError(error, "login"));
    } finally {
      setButtonBusy(button, false, "");
      loginPromise = null;
    }
  })();

  await loginPromise;
});

bind("setPasswordForm", "submit", async (event) => {
  event.preventDefault();
  if (setupPromise) return;

  const form = event.currentTarget;
  const button = form?.querySelector('button[type="submit"]');
  const password = el("newPassword")?.value || "";
  const confirmation = el("confirmPassword")?.value || "";

  if (password.length < 8) {
    setMessage(el("setupMessage"), "Use a password with at least eight characters.");
    return;
  }
  if (password !== confirmation) {
    setMessage(el("setupMessage"), "The passwords do not match.");
    return;
  }

  setupPromise = (async () => {
    setButtonBusy(button, true, "Activating…");
    setMessage(el("setupMessage"), "Activating your account…");
    try {
      const { error } = await client.auth.updateUser({ password });
      if (error) throw error;
      setMessage(el("setupMessage"), "Your account is active. Sign in to continue.", true);
      await client.auth.signOut({ scope: "local" });
      showLogin();
    } catch (error) {
      setMessage(el("setupMessage"), friendlyError(error, "setup"));
    } finally {
      setButtonBusy(button, false, "");
      setupPromise = null;
    }
  })();

  await setupPromise;
});

bind("forgotButton", "click", async () => {
  if (resetPromise) return;
  const email = el("email")?.value.trim() || "";
  if (!email) {
    setMessage(el("authMessage"), "Enter your email address first.");
    return;
  }

  const button = el("forgotButton");
  resetPromise = (async () => {
    setButtonBusy(button, true, "Sending secure link…");
    setMessage(el("authMessage"), "Sending password reset email…");
    try {
      const { error } = await client.auth.resetPasswordForEmail(email, { redirectTo: `${window.location.origin}/?type=recovery` });
      if (error) throw error;
      setMessage(el("authMessage"), "Check your email for the secure password reset link.", true);
    } catch (error) {
      setMessage(el("authMessage"), friendlyError(error, "reset"));
    } finally {
      setButtonBusy(button, false, "");
      resetPromise = null;
    }
  })();

  await resetPromise;
});

bind("togglePassword", "click", () => {
  const input = el("password");
  if (!input) return;
  input.type = input.type === "password" ? "text" : "password";
  setText("togglePassword", input.type === "password" ? "Show" : "Hide");
});

function toggleKeyVisibility() {
  if (!currentKey) return;
  keyVisible = !keyVisible;
  setKeyDisplays(currentKey);
  pushActivity(keyVisible ? "Key revealed" : "Key hidden", keyVisible ? "The live authorisation key was shown on screen." : "The live authorisation key was hidden.");
}

bind("revealButton", "click", toggleKeyVisibility);
bind("revealButtonAlt", "click", toggleKeyVisibility);

async function copyKey() {
  if (!/^\d{6}$/.test(currentKey)) return;
  try {
    await navigator.clipboard.writeText(currentKey);
    [el("copyButton"), el("copyButtonAlt")].filter(Boolean).forEach((button) => {
      const original = button.textContent;
      button.textContent = "Copied";
      window.setTimeout(() => { button.textContent = original; }, 1400);
    });
    pushActivity("Key copied", "The live authorisation key was copied to the clipboard.");
  } catch {
    window.prompt("Copy your authorisation key:", currentKey);
  }
}

bind("copyButton", "click", copyKey);
bind("copyButtonAlt", "click", copyKey);

bind("signOutButton", "click", async () => {
  await client.auth.signOut({ scope: "local" });
  resetSecureState();
  activityEntries = [];
  renderActivity();
  setMessage(el("authMessage"), "");
  showAuth();
  showLogin();
});

bind("retryButton", "click", loadMagicKey);
bind("goSupportButton", "click", () => {
  stopLoading();
  setHidden("errorState", true);
  setHidden("dashboardContent", false);
  activateSection("supportSection");
});

navItems().forEach((button) => button.addEventListener("click", () => activateSection(button.dataset.target)));

client.auth.onAuthStateChange((event) => {
  if (event === "PASSWORD_RECOVERY") {
    showAuth();
    showPasswordSetup();
  }
  if (event === "SIGNED_OUT") {
    resetSecureState();
    showAuth();
    showLogin();
  }
});

(async function initialise() {
  renderActivity();
  resetSecureState();
  showAuth();
  showLogin();

  const params = new URLSearchParams(window.location.search);
  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const type = params.get("type") || hashParams.get("type");

  if (type === "invite" || type === "recovery") {
    showPasswordSetup();
    return;
  }

  await client.auth.signOut({ scope: "local" });
  showAuth();
  showLogin();
})();
