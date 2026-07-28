const SUPABASE_URL = "https://ifesjmdhlyurswgajslm.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_2AetVTjWpPNPFkSAIYltWg_slgY9b1R";

const client = window.supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
  auth: {
    persistSession: true,
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

function setMessage(target, text, success = false) {
  target.textContent = text || "";
  target.classList.toggle("success", success);
}

function initials(name = "") {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "AU";
}

function formatDate(value) {
  if (!value) return "Current secure record";
  try {
    return `Updated ${new Intl.DateTimeFormat("en-AU", {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(new Date(value))}`;
  } catch {
    return "Current secure record";
  }
}

function formatTime(value = new Date()) {
  try {
    return new Intl.DateTimeFormat("en-AU", {
      dateStyle: "medium",
      timeStyle: "short"
    }).format(value instanceof Date ? value : new Date(value));
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
    <div class="activity-item">
      <div>
        <strong>${escapeHtml(item.title)}</strong>
        <span>${escapeHtml(item.detail)}</span>
      </div>
      <span>${escapeHtml(formatTime(item.time))}</span>
    </div>
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

function setError(message) {
  el("loadingState").classList.add("hidden");
  el("dashboardContent").classList.add("hidden");
  el("errorText").textContent = message || "Please sign out and try again.";
  el("errorState").classList.remove("hidden");
  pushActivity("Key load failed", message || "Unable to retrieve the live authorisation key.");
}

function setContentReady() {
  el("loadingState").classList.add("hidden");
  el("errorState").classList.add("hidden");
  el("dashboardContent").classList.remove("hidden");
}

function showAuth() {
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

async function fetchMagicKeyViaFunction(session) {
  const { data, error } = await client.functions.invoke("get-magic-key", {
    method: "GET",
    headers: {
      Authorization: `Bearer ${session.access_token}`
    }
  });

  if (error) throw error;
  return data;
}

async function fetchMagicKeyDirect(session) {
  const response = await fetch(`${SUPABASE_URL}/functions/v1/get-magic-key`, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${session.access_token}`,
      apikey: SUPABASE_PUBLISHABLE_KEY,
      'Content-Type': 'application/json'
    }
  });

  let data = null;
  try { data = await response.json(); } catch { /* ignore */ }
  if (!response.ok) {
    const message = data?.error || `Edge Function returned ${response.status}`;
    throw new Error(message);
  }
  return data;
}

async function getMagicKey(session) {
  try {
    return await fetchMagicKeyViaFunction(session);
  } catch (firstError) {
    try {
      return await fetchMagicKeyDirect(session);
    } catch (secondError) {
      throw new Error(secondError.message || firstError.message || "Unable to load the authorisation key.");
    }
  }
}

function applyProfile(data) {
  currentProfile = data;
  currentKey = String(data.authorisation_key || "");
  keyVisible = false;
  setKeyDisplays(currentKey);
  el("siteName").textContent = data.site || "337 Settlement Road";
  el("siteNameAlt").textContent = data.site || "337 Settlement Road, Thomastown";
  el("sidebarName").textContent = data.representative || "Authorised User";
  el("sidebarRole").textContent = data.position || "Representative";
  el("welcomeTitle").textContent = `Welcome, ${(data.representative || "Authorised User").split(" ")[0]}`;
  el("initials").textContent = initials(data.representative);
  el("updatedAt").textContent = formatDate(data.updated_at);
  el("updatedAtAlt").textContent = formatDate(data.updated_at);
  el("accessLevel").textContent = data.position || "Authorised Representative";
  el("accessLevelAlt").textContent = data.position || "Authorised Representative";
}

async function loadMagicKey() {
  setLoading();

  const { data: sessionData } = await client.auth.getSession();
  const session = sessionData.session;
  if (!session) {
    showAuth();
    return;
  }

  try {
    const data = await getMagicKey(session);
    if (!data || !data.authorisation_key) {
      throw new Error("No authorisation key was returned for this account.");
    }
    applyProfile(data);
    setContentReady();
    pushActivity("Key loaded", `Secure key loaded for ${data.representative || 'authorised user'}.`);
  } catch (error) {
    const message = error?.message || "Please sign out and try again.";
    setError(message.includes('not_authorised') ? 'This login is not linked to a Magic Keys property yet.' : message);
  }
}

el("loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  setMessage(el("authMessage"), "Signing you in…");

  const email = el("email").value.trim();
  const password = el("password").value;

  const { error } = await client.auth.signInWithPassword({ email, password });
  if (error) {
    setMessage(el("authMessage"), error.message);
    return;
  }

  pushActivity("Signed in", `${email} successfully signed in.`);
  setMessage(el("authMessage"), "Signed in successfully.", true);
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

  pushActivity("Password created", "The invited account finished setup.");
  setMessage(el("setupMessage"), "Your account is active. Loading your dashboard…", true);
  setTimeout(() => {
    showLogin();
    showDashboard();
    loadMagicKey();
  }, 700);
});

el("forgotButton").addEventListener("click", async () => {
  const email = el("email").value.trim();
  if (!email) {
    setMessage(el("authMessage"), "Enter your email address first.");
    return;
  }

  setMessage(el("authMessage"), "Sending password reset email…");
  const { error } = await client.auth.resetPasswordForEmail(email, {
    redirectTo: `${window.location.origin}/`
  });

  setMessage(
    el("authMessage"),
    error ? error.message : "Check your email for the secure password reset link.",
    !error
  );
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
  await client.auth.signOut();
  currentKey = "";
  currentProfile = null;
  activityEntries = [];
  renderActivity();
  showAuth();
  showLogin();
});

el("retryButton").addEventListener("click", () => loadMagicKey());
el("goSupportButton").addEventListener("click", () => {
  setContentReady();
  activateSection('supportSection');
});

navItems().forEach((button) => button.addEventListener('click', () => activateSection(button.dataset.target)));

client.auth.onAuthStateChange(async (event, session) => {
  if (event === "PASSWORD_RECOVERY" || event === "USER_UPDATED") {
    showAuth();
    showPasswordSetup();
    return;
  }

  if (event === "SIGNED_IN" && session) {
    showDashboard();
    activateSection('dashboardSection');
    await loadMagicKey();
    return;
  }

  if (event === "SIGNED_OUT") {
    showAuth();
  }
});

(async function initialise() {
  renderActivity();
  const params = new URLSearchParams(window.location.search);
  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const type = params.get("type") || hashParams.get("type");

  const { data } = await client.auth.getSession();
  if (type === "invite" || type === "recovery") {
    showAuth();
    showPasswordSetup();
  } else if (data.session) {
    showDashboard();
    activateSection('dashboardSection');
    pushActivity("Session restored", "A saved secure session was restored.");
    await loadMagicKey();
  } else {
    showAuth();
    showLogin();
  }
})();
