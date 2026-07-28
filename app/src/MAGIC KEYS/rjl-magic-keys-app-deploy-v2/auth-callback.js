const SUPABASE_URL = "https://ifesjmdhlyurswgajslm.supabase.co";
const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_2AetVTjWpPNPFkSAIYltWg_slgY9b1R";
const statusText = document.getElementById("callbackStatus");
const returnLink = document.getElementById("returnLink");

function setStatus(message, failed = false) {
  if (statusText) {
    statusText.textContent = message;
    statusText.classList.toggle("failed", failed);
  }
  if (returnLink) returnLink.classList.toggle("hidden", !failed);
}

(async () => {
  if (!window.supabase) throw new Error("Secure authentication library failed to load.");

  const supabase = window.supabase.createClient(SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: false,
      flowType: "pkce",
      storage: window.sessionStorage
    }
  });

  const params = new URLSearchParams(window.location.search);
  const providerError = params.get("error_description") || params.get("error");
  if (providerError) throw new Error(providerError);

  const code = params.get("code");
  if (!code) throw new Error("Google returned without an authentication code.");

  setStatus("Verifying Google identity and creating your secure session…");
  const { data, error } = await supabase.auth.exchangeCodeForSession(code);
  if (error) throw error;
  if (!data?.session) throw new Error("Google returned without a secure session.");

  window.sessionStorage.setItem("rjl.oauth.resume", "1");
  window.sessionStorage.setItem("rjl.oauth.ready", "1");
  setStatus("Identity verified. Opening Magic Keys…");
  window.location.replace("/");
})().catch((error) => {
  const message = error?.message || "Google sign-in could not be completed.";
  setStatus(message, true);
});
