const SUPABASE_URL = "https://ifesjmdhlyurswgajslm.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_2AetVTjWpPNPFkSAIYltWg_slgY9b1R";

const client = window.supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
  auth: {
    persistSession: false,
    autoRefreshToken: true,
    detectSessionInUrl: true
  }
});

const el = (id) => document.getElementById(id);
const navItems = () => Array.from(document.querySelectorAll('.nav-item'));
const viewSections = () => Array.from(document.querySelectorAll('.view-section'));
let currentKey = "";
let keyVisible = false;
let currentProfile = null;
let activityEntries = [];
let loadPromise = null;

function setMessage(target, text, success = false) {
  if (!target) return;
  target.textContent = text || "";
  target.classList.toggle("success", success);
}

function initials(name = "") {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "AU";
}

function formatDate(value) {
  if (!value) return "Current secure record";
  try {
    return `Updated ${new Intl.DateTimeFormat("en-AU", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value))}`;
  } catch {
    return "Current secure record";
  }
}

function formatTime(value = new Date()) {
  try {
    return new Intl.DateTimeFormat("en-AU", { dateStyle: "medium", timeStyle: "short" }).format(value instanceof Date ? value : new Date(value));
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
  const wrap = el('activityList');
  if (!wrap) return;
  if (!activityEntries.length) {
    wrap.innerHTML = '<div class="activity-item"><div><strong>No activity yet</strong><span>Your recent secure actions will appear here.</span></div><span>—</span></div>';
    return;
  }
  wrap.innerHTML = activityEntries.map((item) => `
    <div class="activity-item"><div><strong>${escapeHtml(item.title)}</strong><span>${escapeHtml(item.detail)}</span></div><span>${escapeHtml(formatTime(item.time))}</span></div>
  `).join('');
}

function setKeyDisplays(value) {
  const masked = value && keyVisible ? value.replace(/(\d{3})(\d{3})/, "$1 $2") : "••• •••";
  el("keyDisplay").textContent = masked;
  el("keyDisplayLarge").textContent = masked;
  const text = keyVisible ? "Hide key" : "Reveal key";
  el("revealButton").textContent = text;
  el("revealButtonAlt").textContent = text;
}

function setLoading() {
  el("loadingState").classList.remove("hidden");
  el("errorState").classList.add("hidden");
  el("dashboardContent").classList.add("hidden");
}

function stopLoading() {
  el("loadingState").classList.add("hidden");
}

function setError(message) {
  stopLoading();
  el("dashboardContent").classList.add("hidden");
  el("errorText").textContent = message || "Please sign out and try again.";
  el("errorState").classList.remove("hidden");
  pushActivity("Key load failed", message || "Unable to retrieve the live authorisation key.");
}

function setContentReady() {
  stopLoading();
  el("errorState").classList.add("hidden");
  el("dashboardContent").classList.remove("hidden");
}

function showAuth() {
  stopLoading();
  el("dashboardView").classList.add("hidden");
  el("authView").classList.remove("hidden");
}

function showDashboard() {
  el("authView").classList.add("hidden");
  el("dashboardView").classList.remove("hidden");
}

function showPasswordSetup() {
  el("loginPanel").classList.add("hidden");
  el("setPasswordPanel").classList.remove("hidden");
}

function showLogin() {
  el("setPasswordPanel").classList.add("hidden");
  el("loginPanel").classList.remove("hidden");
}

function activateSection(targetId) {
  navItems().forEach((button) => button.classList.toggle('active', button.dataset.target === targetId));
  viewSections().forEach((section) => section.classList.toggle('hidden', section.id !== targetId));
}

async function fetchMagicKey(session) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(`${SUPABASE_URL}/functions/v1/get-magic-key`, {
      method: 'GET',
      signal: controller.signal,
      cache: 'no-store',
      headers: {
        Authorization: `Bearer ${session.access_token}`,
        apikey: SUPABASE_PUBLISHABLE_KEY,
        'Content-Type': 'application/json'
      }
    });
    let data = null;
    try { data = await response.json(); } catch { /* response was not JSON */ }
    if (!response.ok) throw new Error(data?.error || `Secure service returned ${response.status}`);
    return data;
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('The secure service took too long to respond. Press Retry.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function applyProfile(data) {
  currentProfile = data;
  currentKey = String(data.authorisation_key || "");
  keyVisible = false;
  setKeyDisplays(currentKey);
  const fullName = String(data.representative || "Authorised User").trim();
  el("siteName").textContent = data.site || "337 Settlement Road";
  el("siteNameAlt").textContent = data.site || "337 Settlement Road, Thomastown";
  el("sidebarName").textContent = fullName;
  el("sidebarRole").textContent = data.position || "Representative";
  el("welcomeTitle").textContent = `Welcome, ${fullName}`;
  el("initials").textContent = initials(fullName);
  el("updatedAt").textContent = formatDate(data.updated_at);
  el("updatedAtAlt").textContent = formatDate(data.updated_at);
  el("accessLevel").textContent = data.position || "Authorised Representative";
  el("accessLevelAlt").textContent = data.position || "Authorised Representative";
}

async function loadMagicKey() {
  if (loadPromise) return loadPromise;
  loadPromise = (async () => {
    setLoading();
    try {
      const { data: sessionData, error: sessionError } = await client.auth.getSession();
      if (sessionError) throw sessionError;
      const session = sessionData.session;
      if (!session) {
        showAuth();
        showLogin();
        return;
      }
      const data = await fetchMagicKey(session);
      if (!data || !data.authorisation_key) throw new Error("No authorisation key was returned for this account.");
      applyProfile(data);
      setContentReady();
      pushActivity("Key loaded", `Secure key loaded for ${data.representative || 'authorised user'}.`);
    } catch (error) {
      const raw = error?.message || "Please sign out and try again.";
      const message = raw.includes('not_authorised') ? 'This login is not linked to a Magic Keys property yet.' : raw;
      setError(message);
    } finally {
      loadPromise = null;
    }
  })();
  return loadPromise;
}

el("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  setMessage(el("authMessage"), "Authenticating…");
  const email = el("email").value.trim();
  const password = el("password").value;
  const { data, error } = await client.auth.signInWithPassword({ email, password });
  if (error) {
    setMessage(el("authMessage"), error.message);
    return;
  }
  setMessage(el("authMessage"), "Access granted.", true);
  showDashboard();
  activateSection('dashboardSection');
  pushActivity("Signed in", `${email} successfully signed in.`);
  await loadMagicKey();
});

el("setPasswordForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const password = el("newPassword").value;
  const confirmation = el("confirmPassword").value;
  if (password !== confirmation) {
    setMessage(el("setupMessage"), "The passwords do not match.");
    return;
  }
  setMessage(el("setupMessage"), "Activating your account…");
  const { error } = await client.auth.updateUser({ password });
  if (error) {
    setMessage(el("setupMessage"), error.message);
    return;
  }
  setMessage(el("setupMessage"), "Your account is active. Sign in to continue.", true);
  await client.auth.signOut({ scope: 'local' });
  showLogin();
});

el("forgotButton").addEventListener("click", async () => {
  const email = el("email").value.trim();
  if (!email) {
    setMessage(el("authMessage"), "Enter your email address first.");
    return;
  }
  setMessage(el("authMessage"), "Sending password reset email…");
  const { error } = await client.auth.resetPasswordForEmail(email, { redirectTo: `${window.location.origin}/` });
  setMessage(el("authMessage"), error ? error.message : "Check your email for the secure password reset link.", !error);
});

el("togglePassword").addEventListener("click", () => {
  const input = el("password");
  input.type = input.type === "password" ? "text" : "password";
  el("togglePassword").textContent = input.type === "password" ? "Show" : "Hide";
});

function toggleKeyVisibility() {
  keyVisible = !keyVisible;
  setKeyDisplays(currentKey);
  pushActivity(keyVisible ? "Key revealed" : "Key hidden", keyVisible ? "The live authorisation key was shown on screen." : "The live authorisation key was hidden.");
}

el("revealButton").addEventListener("click", toggleKeyVisibility);
el("revealButtonAlt").addEventListener("click", toggleKeyVisibility);

async function copyKey() {
  if (!currentKey) return;
  try {
    await navigator.clipboard.writeText(currentKey);
    [el("copyButton"), el("copyButtonAlt")].forEach((button) => {
      const original = button.textContent;
      button.textContent = "Copied";
      setTimeout(() => { button.textContent = original; }, 1400);
    });
    pushActivity("Key copied", "The live authorisation key was copied to the clipboard.");
  } catch {
    window.prompt("Copy your authorisation key:", currentKey);
  }
}

el("copyButton").addEventListener("click", copyKey);
el("copyButtonAlt").addEventListener("click", copyKey);

el("signOutButton").addEventListener("click", async () => {
  await client.auth.signOut({ scope: 'local' });
  currentKey = "";
  currentProfile = null;
  activityEntries = [];
  renderActivity();
  showAuth();
  showLogin();
});

el("retryButton").addEventListener("click", loadMagicKey);
el("goSupportButton").addEventListener("click", () => {
  setContentReady();
  activateSection('supportSection');
});
navItems().forEach((button) => button.addEventListener('click', () => activateSection(button.dataset.target)));

client.auth.onAuthStateChange((event) => {
  if (event === "PASSWORD_RECOVERY") {
    showAuth();
    showPasswordSetup();
  }
  if (event === "SIGNED_OUT") {
    showAuth();
    showLogin();
  }
});

(async function initialise() {
  renderActivity();
  showAuth();
  showLogin();
  const params = new URLSearchParams(window.location.search);
  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const type = params.get("type") || hashParams.get("type");
  if (type === "invite" || type === "recovery") {
    showPasswordSetup();
    return;
  }
  await client.auth.signOut({ scope: 'local' });
  showAuth();
  showLogin();
})();
