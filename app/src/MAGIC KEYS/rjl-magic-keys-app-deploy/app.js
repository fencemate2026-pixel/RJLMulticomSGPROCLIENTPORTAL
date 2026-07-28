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
let currentKey = "";
let keyVisible = false;

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

async function loadMagicKey() {
  el("loadingState").classList.remove("hidden");
  el("dashboardContent").classList.add("hidden");

  const { data: sessionData } = await client.auth.getSession();
  const session = sessionData.session;
  if (!session) {
    showAuth();
    return;
  }

  const { data, error } = await client.functions.invoke("get-magic-key", {
    method: "POST"
  });

  if (error) {
    el("loadingState").innerHTML = `
      <div>
        <h2>We could not load your key</h2>
        <p>${escapeHtml(error.message || "Please sign out and try again.")}</p>
      </div>`;
    return;
  }

  currentKey = String(data.authorisation_key || "");
  keyVisible = false;
  el("keyDisplay").textContent = "••• •••";
  el("revealButton").textContent = "Reveal key";
  el("siteName").textContent = data.site || "337 Settlement Road, Thomastown";
  el("sidebarName").textContent = data.representative || "Authorised User";
  el("sidebarRole").textContent = data.position || "Representative";
  el("welcomeTitle").textContent = `Welcome, ${(data.representative || "Authorised User").split(" ")[0]}`;
  el("initials").textContent = initials(data.representative);
  el("updatedAt").textContent = formatDate(data.updated_at);

  el("loadingState").classList.add("hidden");
  el("dashboardContent").classList.remove("hidden");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  }[character]));
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

el("revealButton").addEventListener("click", () => {
  keyVisible = !keyVisible;
  el("keyDisplay").textContent = keyVisible && currentKey
    ? currentKey.replace(/(\d{3})(\d{3})/, "$1 $2")
    : "••• •••";
  el("revealButton").textContent = keyVisible ? "Hide key" : "Reveal key";
});

el("copyButton").addEventListener("click", async () => {
  if (!currentKey) return;
  try {
    await navigator.clipboard.writeText(currentKey);
    const original = el("copyButton").textContent;
    el("copyButton").textContent = "Copied";
    setTimeout(() => { el("copyButton").textContent = original; }, 1400);
  } catch {
    window.prompt("Copy your authorisation key:", currentKey);
  }
});

el("signOutButton").addEventListener("click", async () => {
  await client.auth.signOut();
  currentKey = "";
  showAuth();
  showLogin();
});

client.auth.onAuthStateChange(async (event, session) => {
  if (event === "PASSWORD_RECOVERY" || event === "USER_UPDATED") {
    showAuth();
    showPasswordSetup();
    return;
  }

  if (event === "SIGNED_IN" && session) {
    showDashboard();
    await loadMagicKey();
    return;
  }

  if (event === "SIGNED_OUT") {
    showAuth();
  }
});

(async function initialise() {
  const params = new URLSearchParams(window.location.search);
  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const type = params.get("type") || hashParams.get("type");

  const { data } = await client.auth.getSession();
  if (type === "invite" || type === "recovery") {
    showAuth();
    showPasswordSetup();
  } else if (data.session) {
    showDashboard();
    await loadMagicKey();
  } else {
    showAuth();
    showLogin();
  }
})();
